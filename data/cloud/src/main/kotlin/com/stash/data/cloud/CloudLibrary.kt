package com.stash.data.cloud

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.util.Log
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.stash.core.data.db.dao.CloudDao
import com.stash.core.data.db.dao.CloudFolderWithCount
import com.stash.core.data.db.dao.CloudRemoval
import com.stash.core.data.db.entity.CloudAccountEntity
import com.stash.core.data.db.entity.CloudFolderEntity
import com.stash.core.model.CloudProvider
import com.stash.data.cloud.auth.CloudAuthCoordinator
import com.stash.data.cloud.auth.CloudAuthEvent
import com.stash.data.cloud.auth.CloudTokenManager
import com.stash.data.cloud.provider.CloudEntry
import com.stash.data.cloud.provider.CloudStorageProvider
import com.stash.data.cloud.provider.gdrive.GoogleAuthorizer
import com.stash.data.cloud.provider.gdrive.GoogleDrivePicks
import com.stash.data.cloud.provider.gdrive.GooglePickOutcome
import com.stash.data.cloud.provider.gdrive.GooglePickStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import com.stash.data.cloud.scan.CloudScanScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** A connected account as Settings shows it. */
data class CloudAccount(
    val id: Long,
    val provider: CloudProvider,
    val displayName: String,
    val email: String?,
    val connectedAt: Long,
)

/**
 * The one entry point the UI uses for cloud storage: which providers this build offers,
 * connecting and disconnecting accounts, browsing a drive, picking and unpicking folders, and
 * rescans. Everything here is provider-agnostic.
 */
@Singleton
class CloudLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
    private val config: CloudConfig,
    private val dao: CloudDao,
    private val providers: Map<CloudProvider, @JvmSuppressWildcards CloudStorageProvider>,
    private val coordinator: CloudAuthCoordinator,
    private val tokens: CloudTokenManager,
    private val scheduler: CloudScanScheduler,
    private val scanner: com.stash.data.cloud.scan.CloudScanner,
    private val googleAuthorizer: GoogleAuthorizer,
    private val googlePicks: GoogleDrivePicks,
    private val googlePickStore: GooglePickStore,
    private val uploads: com.stash.data.cloud.upload.CloudUploads,
) {
    /**
     * For work that must finish once started even if the screen that asked is closed (filing a
     * pick: account row, token, folder row, pending ids). Lives as long as the app, like this
     * singleton.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Providers this build can connect (empty = the whole feature stays hidden). */
    val enabledProviders: List<CloudProvider> get() = config.enabledProviders

    val isAvailable: Boolean get() = config.anyEnabled

    val accounts: Flow<List<CloudAccount>> = dao.observeAccounts().map { rows ->
        rows.mapNotNull { it.toAccount() }.filter { config.isEnabled(it.provider) }
    }

    val folders: Flow<List<CloudFolderWithCount>> = dao.observeFolders()

    /** Bumps when a sign-in is stored or forgotten: re-check [isSignedIn] on it. */
    val signInChanges: Flow<Long> get() = tokens.changes

    val authEvents: SharedFlow<CloudAuthEvent> get() = coordinator.events

    /**
     * True when [provider]'s music is picked file by file in the provider's own picker (Google
     * Drive) rather than as folders browsed in Stash: Settings shows "Add songs" instead of
     * "Add a folder", and connecting IS the first pick ([googlePickerIntent]).
     */
    fun usesPicker(provider: CloudProvider): Boolean = providers[provider]?.pickedFiles == true

    /**
     * Google's picker for [accountId] (null = a new account: Google asks which one), to launch
     * with an IntentSender request. The result goes to [completeGooglePick].
     */
    suspend fun googlePickerIntent(accountId: Long?): IntentSender {
        check(config.isEnabled(CloudProvider.GOOGLE_DRIVE)) { "Google Drive isn't available in this build" }
        val email = accountId?.let { dao.getAccount(it)?.email }
        return googleAuthorizer.pickerIntent(email)
    }

    /**
     * Files what the picker returned: connects the account on its first pick, queues the new
     * songs and starts their scan. Runs in the app's scope: leaving Settings cancels only the
     * wait for the outcome, never the filing half-way (a token saved without its picks queued).
     */
    suspend fun completeGooglePick(data: Intent?): GooglePickOutcome = appScope.async {
        try {
            googlePicks.accept(googleAuthorizer.pickerResult(data)).also {
                // A reconnect: songs waiting to be saved to Drive go now.
                if (it is GooglePickOutcome.Added) uploads.resume()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never the message: it may echo an API response.
            Log.w("CloudLibrary", "Google Drive pick failed: ${e.javaClass.simpleName}")
            GooglePickOutcome.Failed("Couldn't add songs from Google Drive. Check your connection and try again.")
        }
    }.await()

    /** Opens [provider]'s sign-in page in a Custom Tab (a plain browser when none is installed). */
    fun connect(activityContext: Context, provider: CloudProvider) {
        check(config.isEnabled(provider)) { "${provider.displayName} isn't available in this build" }
        check(!usesPicker(provider)) { "${provider.displayName} connects through its picker" }
        val url = Uri.parse(coordinator.begin(provider))
        try {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(activityContext, url)
        } catch (e: ActivityNotFoundException) {
            activityContext.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** False when the account's sign-in is gone (e.g. after restoring a backup): show "Reconnect". */
    suspend fun isSignedIn(account: CloudAccount): Boolean = tokens.hasToken(account.provider, account.id)

    /** How many of the account's songs a disconnect would remove, and how many stay as downloads. */
    suspend fun disconnectImpact(accountId: Long): Pair<Int, Int> {
        val total = dao.countItems(accountId)
        val downloaded = dao.countDownloadedItems(accountId)
        return (total - downloaded) to downloaded
    }

    /**
     * Disconnect: forgets the tokens on this phone and removes the account's songs, except the
     * downloaded ones, which stay as local files. (The access Stash was granted can also be
     * revoked on the provider's website; nothing is sent to the provider here. Google Drive's token
     * is also dropped from Play services' cache on the phone.)
     */
    suspend fun disconnect(accountId: Long): CloudRemoval {
        val account = dao.getAccount(accountId) ?: return CloudRemoval(0, 0)
        // Stop scans first, and wait out any still inside one of this account's folders, so no
        // scan writes rows for a removed account or refreshes (and re-saves) its token after.
        scheduler.cancelRunning()
        val folderIds = dao.getFolders(accountId).map { it.id }
        val kind = CloudProvider.fromId(account.provider)
        // Stop uploading to it; pending uploads go with the account row. Nothing in Drive is touched.
        if (kind == CloudProvider.GOOGLE_DRIVE) uploads.onAccountRemoved(accountId)
        var forgotten: String? = null
        val removal = withFolderLocks(folderIds) {
            kind?.let { forgotten = tokens.forget(it, accountId) }
            for (id in folderIds) googlePickStore.clear(id)
            dao.removeAccount(accountId)
        }
        if (kind == CloudProvider.GOOGLE_DRIVE) forgotten?.let { clearGoogleToken(it) }
        resumeScans()
        return removal
    }

    /** Best effort: the account is already gone here, whatever Play services says. */
    private suspend fun clearGoogleToken(token: String) {
        try {
            googleAuthorizer.clearToken(token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("CloudLibrary", "Google token not cleared: ${e.javaClass.simpleName}")
        }
    }

    /** The folders and files in [folderId] (null = the top of the drive), folders first. */
    suspend fun browse(accountId: Long, folderId: String?): List<CloudEntry> {
        val account = dao.getAccount(accountId) ?: return emptyList()
        val provider = providers[CloudProvider.fromId(account.provider)] ?: return emptyList()
        return provider.browse(accountId, folderId).sortedWith(compareBy<CloudEntry> { !it.isFolder }.thenBy { it.name.lowercase() })
    }

    /**
     * Picks [folder] as music for [accountId] and scans it. Refused (returns false) when it is
     * already picked or sits inside / contains a folder already picked, so a song is never
     * listed by two folders.
     */
    suspend fun addFolder(accountId: Long, folder: CloudEntry): Boolean {
        val existing = dao.getFolders(accountId)
        if (existing.any { overlaps(it.path, folder.path) }) return false
        val id = dao.insertFolder(
            CloudFolderEntity(
                accountId = accountId, itemId = folder.id, name = folder.name.ifEmpty { "My files" },
                path = folder.path, addedAt = System.currentTimeMillis(),
            ),
        )
        if (id <= 0) return false
        scheduler.scanNow(id)
        scheduler.ensurePeriodic()
        return true
    }

    /** Unpicks a folder; its songs go, downloaded ones stay as local files. */
    suspend fun removeFolder(folderId: Long): CloudRemoval {
        scheduler.cancelRunning()
        val removal = scanner.withFolderLock(folderId) {
            googlePickStore.clear(folderId)
            dao.removeFolder(folderId)
        }
        resumeScans()
        return removal
    }

    /** Other folders' scans were cancelled with the removed one's: they carry on from their cursors. */
    private suspend fun resumeScans() {
        if (dao.getAllFolders().isEmpty()) return
        scheduler.scanNow(null)
        scheduler.ensurePeriodic()
    }

    private suspend fun <T> withFolderLocks(folderIds: List<Long>, block: suspend () -> T): T =
        if (folderIds.isEmpty()) block()
        else scanner.withFolderLock(folderIds.first()) { withFolderLocks(folderIds.drop(1), block) }

    suspend fun rescanAll() = scheduler.scanNow(null, full = true)

    private fun CloudAccountEntity.toAccount(): CloudAccount? =
        CloudProvider.fromId(provider)?.let { CloudAccount(id, it, displayName, email, connectedAt) }

    companion object {
        /** True when one path is the other or holds it ("/Music" and "/Music/Rips"); case-insensitive. */
        internal fun overlaps(a: String, b: String): Boolean {
            val x = a.trimEnd('/').lowercase()
            val y = b.trimEnd('/').lowercase()
            return x == y || y.startsWith("$x/") || x.startsWith("$y/") || x.isEmpty() || y.isEmpty()
        }
    }
}
