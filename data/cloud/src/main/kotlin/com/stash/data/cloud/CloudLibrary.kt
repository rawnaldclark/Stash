package com.stash.data.cloud

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
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
) {
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

    /** Opens [provider]'s sign-in page in a Custom Tab (a plain browser when none is installed). */
    fun connect(activityContext: Context, provider: CloudProvider) {
        check(config.isEnabled(provider)) { "${provider.displayName} isn't available in this build" }
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
     * revoked on the provider's website; nothing is sent to the provider here.)
     */
    suspend fun disconnect(accountId: Long): CloudRemoval {
        val account = dao.getAccount(accountId) ?: return CloudRemoval(0, 0)
        // Stop scans first, and wait out any still inside one of this account's folders, so no
        // scan writes rows for a removed account or refreshes (and re-saves) its token after.
        scheduler.cancelRunning()
        val removal = withFolderLocks(dao.getFolders(accountId).map { it.id }) {
            CloudProvider.fromId(account.provider)?.let { tokens.forget(it, accountId) }
            dao.removeAccount(accountId)
        }
        resumeScans()
        return removal
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
        val removal = scanner.withFolderLock(folderId) { dao.removeFolder(folderId) }
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
