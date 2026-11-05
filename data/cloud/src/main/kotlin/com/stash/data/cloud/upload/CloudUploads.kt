package com.stash.data.cloud.upload

import android.util.Log
import com.stash.core.data.db.dao.CloudDao
import com.stash.core.data.db.entity.CloudUploadOrigin
import com.stash.core.data.db.entity.CloudUploadState
import com.stash.core.model.CloudProvider
import com.stash.data.cloud.CloudConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** What a "Save to Google Drive" tap did, for the toast. */
data class SaveRequest(val queued: Int, val alreadyQueued: Int, val notDownloaded: Int)

/** The Google Drive card's upload status. */
data class UploadStatus(
    val backupOn: Boolean,
    val saved: Int,
    val queued: Int,
    val existing: Int,
    val failed: Int,
    val driveFull: Boolean,
    val running: Boolean,
    val wifiOnly: Boolean,
) {
    /** One line for the card: "Backed up 124 songs · uploading 3", "Waiting for Wi-Fi"... null = nothing to say. */
    val line: String?
        get() {
            if (driveFull) return "Your Google Drive is full. Free up space, then try again."
            val parts = mutableListOf<String>()
            if (saved > 0) parts += if (backupOn) "Backed up ${songs(saved)}" else "Saved ${songs(saved)}"
            if (queued > 0) parts += if (running) "uploading $queued" else if (wifiOnly) "waiting for Wi-Fi" else "waiting for a connection"
            if (failed > 0) parts += "$failed couldn't be saved"
            return parts.joinToString(" · ").replaceFirstChar { it.uppercase() }.ifEmpty { null }
        }

    private fun songs(n: Int) = if (n == 1) "1 song" else "$n songs"
}

/**
 * Saving downloads to Google Drive, as the UI sees it: the song/album/playlist "Save to Google
 * Drive" item, the card's "Back up my downloads" switch and "Back up N existing downloads", and
 * the card's status line. The work itself is [CloudUploadWorker]'s.
 *
 * Backup: turning it on files every download that exists now as EXISTING (counted, not sent);
 * from then on, a download that appears without an upload row is queued and sent
 * ([start] watches for them). Turning it off sends what it queued back to EXISTING; songs the
 * user saved by hand still go.
 *
 * Several Drive accounts: one of them takes saves and backup ([saveAccountId]). A song lives in one
 * Drive. Each account has its own queue state: one signed out or full never holds up another's
 * songs ([CloudUploadRun]), and each has its own status line.
 */
@Singleton
class CloudUploads @Inject constructor(
    private val dao: CloudDao,
    private val settings: CloudUploadSettings,
    private val scheduler: CloudUploadRunner,
    private val config: CloudConfig,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watcher: Job? = null

    val available: Boolean get() = config.isEnabled(CloudProvider.GOOGLE_DRIVE)

    /**
     * The Drive account that takes saves and backup: the one the user chose, else the one backup
     * already runs on, else the first connected. Null while no Drive account is connected.
     */
    suspend fun saveAccountId(): Long? = pickSaveAccount(
        settings.saveAccountId.value,
        settings.backupAccountId.value,
        dao.getAccounts().filter { it.provider == CloudProvider.GOOGLE_DRIVE.id }.map { it.id },
    )

    /** [saveAccountId] as it changes, for the Drive cards. */
    val saveAccount: Flow<Long?>
        get() = combine(settings.saveAccountId, settings.backupAccountId, dao.observeAccounts()) { chosen, backup, accounts ->
            pickSaveAccount(chosen, backup, accounts.filter { it.provider == CloudProvider.GOOGLE_DRIVE.id }.map { it.id })
        }.distinctUntilChanged()

    private fun pickSaveAccount(chosen: Long?, backup: Long?, drives: List<Long>): Long? =
        listOfNotNull(chosen, backup).firstOrNull { it in drives } ?: drives.firstOrNull()

    /**
     * Queues [trackIds] for [accountId] (or the [saveAccountId]). Only downloads can go; the rest
     * are counted in [SaveRequest.notDownloaded]. Clears that account's "Drive full" pause: the user
     * is trying again.
     */
    suspend fun save(trackIds: List<Long>, accountId: Long? = null): SaveRequest {
        val account = accountId ?: saveAccountId() ?: return SaveRequest(0, 0, trackIds.size)
        val ids = trackIds.distinct()
        val uploadable = ids.chunked(CloudDao.BIND_CHUNK).flatMap { dao.uploadableTrackIds(it) }
        val queued = dao.queueUploads(uploadable, account, CloudUploadOrigin.MANUAL.name, System.currentTimeMillis())
        clearFull(account)
        if (uploadable.isNotEmpty()) scheduler.kick()
        return SaveRequest(queued, uploadable.size - queued, ids.size - uploadable.size)
    }

    /**
     * The "Save and back up to" choice. If backup is on, it moves along: its waiting songs follow
     * ([CloudDao.moveWaitingBackups]); what is already in the old Drive stays there.
     */
    suspend fun setSaveAccount(accountId: Long) {
        settings.setSaveAccount(accountId)
        if (settings.backupAccountId.value != null) setBackup(accountId, on = true)
    }

    /**
     * "Back up my downloads" on or off for [accountId]. On also makes it the account saves go to:
     * there is one of those. Off applies only to the account backup runs on.
     */
    suspend fun setBackup(accountId: Long, on: Boolean) {
        val now = System.currentTimeMillis()
        val current = settings.backupAccountId.value
        if (on) {
            if (current == accountId) return
            // A run may be in the middle of a song that is about to move: stop it, then start over.
            val moving = current != null
            if (moving) scheduler.cancel()
            dao.moveWaitingBackups(accountId, now)
            // Today's downloads are only counted ("Back up N existing"); new ones are sent.
            dao.insertUploadsForDownloads(accountId, CloudUploadState.EXISTING.name, now)
            settings.setSaveAccount(accountId)
            settings.setBackupAccount(accountId)
            if (moving) kickIfQueued()
        } else {
            if (current != accountId) return
            settings.setBackupAccount(null)
            dao.unqueueBackupUploads(accountId, now)
        }
    }

    /** "Back up N existing downloads" for [accountId]. */
    suspend fun backUpExisting(accountId: Long): Int {
        val n = dao.queueExistingUploads(accountId, System.currentTimeMillis())
        clearFull(accountId)
        if (n > 0) scheduler.kick()
        return n
    }

    /** "Try again" after [accountId]'s Drive was full, or after a reconnect: runs whatever can go. */
    suspend fun resume(accountId: Long) {
        clearFull(accountId)
        kickIfQueued()
    }

    /**
     * Disconnects [accountId] from uploads around [remove], which deletes the account row (its
     * upload rows go with it): the run stops first, since it may be in the middle of one of this
     * account's songs, and starts again after for the accounts that remain. Backup on the removed
     * account turns off rather than moving: Stash never starts sending the user's songs to another
     * account on its own.
     */
    suspend fun <T> removingAccount(accountId: Long, remove: suspend () -> T): T {
        try {
            scheduler.cancel()
            if (settings.backupAccountId.value == accountId) settings.setBackupAccount(null)
            if (settings.saveAccountId.value == accountId) settings.setSaveAccount(null)
            settings.setDriveFull(accountId, false)
            settings.clear(accountId)
            return remove()
        } finally {
            // Also when the screen that asked is closed meanwhile: the other accounts' songs must
            // not wait for the next app start.
            withContext(NonCancellable) { afterAccountRemoved() }
        }
    }

    private suspend fun afterAccountRemoved() {
        try {
            // The songs saved to the removed account have no upload row any more, which reads as
            // "new downloads" while backup is on for another account: count them instead, like any
            // download from before backup, so a disconnect never starts a surprise upload.
            settings.backupAccountId.value?.let {
                dao.insertUploadsForDownloads(it, CloudUploadState.EXISTING.name, System.currentTimeMillis())
            }
            kickIfQueued()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "uploads not restarted after a disconnect: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun clearFull(accountId: Long) {
        if (accountId in settings.fullAccounts.value) settings.setDriveFull(accountId, false)
    }

    /** Starts a run if any account that isn't full has songs waiting. */
    private suspend fun kickIfQueued() {
        if (dao.countQueuedUploads(settings.fullAccounts.value.toList()) > 0) scheduler.kick()
    }

    fun status(accountId: Long): Flow<UploadStatus> = combine(
        dao.observeUploadCounts(accountId),
        settings.backupAccountId,
        settings.fullAccounts,
        scheduler.running,
        scheduler.wifiOnly,
    ) { counts, backup, full, running, wifiOnly ->
        fun n(state: CloudUploadState) = counts.firstOrNull { it.state == state.name }?.count ?: 0
        UploadStatus(
            backupOn = backup == accountId, saved = n(CloudUploadState.DONE), queued = n(CloudUploadState.QUEUED),
            existing = n(CloudUploadState.EXISTING), failed = n(CloudUploadState.FAILED),
            driveFull = accountId in full, running = running, wifiOnly = wifiOnly,
        )
    }

    /**
     * Starts watching for new downloads while backup is on (once, at app start). A download that
     * lands without an upload row is queued for the backup account and the worker is kicked.
     * Also resumes a queue left from before the process died.
     */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun start() {
        if (!available || watcher != null) return
        watcher = scope.launch {
            try {
                kickIfQueued()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "upload queue not resumed: ${e.javaClass.simpleName}")
            }
            // A database hiccup must not end backup until the next app start: watch again.
            while (true) {
                try {
                    settings.backupAccountId
                        .flatMapLatest { account ->
                            // No distinctUntilChanged: if queueing failed, the next change to the
                            // library tries again even when the count is the same.
                            if (account == null) emptyFlow()
                            else dao.observeDownloadsWithoutUpload().filter { it > 0 }.map { account }
                        }
                        // A playlist download lands song by song: one pass a few seconds after the last.
                        .debounce(WATCH_DEBOUNCE_MS)
                        .collect { account -> queueNewDownloads(account) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "backup watcher restarting: ${e.javaClass.simpleName}")
                    delay(WATCH_RESTART_MS)
                }
            }
        }
    }

    private suspend fun queueNewDownloads(accountId: Long) {
        try {
            // The account may have been disconnected meanwhile: then the switch is off already.
            if (dao.getAccount(accountId) == null) return
            dao.insertUploadsForDownloads(accountId, CloudUploadState.QUEUED.name, System.currentTimeMillis())
            if (accountId !in settings.fullAccounts.value) scheduler.kick()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "new downloads not queued for backup: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "CloudUploads"
        private const val WATCH_DEBOUNCE_MS = 3_000L
        private const val WATCH_RESTART_MS = 30_000L

        /** The toast after a "Save to Google Drive" tap on a song, album or playlist. */
        fun message(r: SaveRequest): String {
            val sending = r.queued + r.alreadyQueued
            return buildString {
                append(
                    when {
                        sending == 0 -> "Nothing to save yet."
                        sending == 1 -> "Saving 1 song to Google Drive."
                        else -> "Saving $sending songs to Google Drive."
                    },
                )
                when {
                    r.notDownloaded == 1 -> append(" 1 song isn't downloaded yet.")
                    r.notDownloaded > 1 -> append(" ${r.notDownloaded} songs aren't downloaded yet.")
                }
                if (sending == 0 && r.notDownloaded > 0) {
                    // "Nothing to save yet. 3 songs aren't downloaded yet." reads oddly: say it plainly.
                    clear()
                    append(if (r.notDownloaded == 1) "Download this song first to save it to Google Drive." else "Download these songs first to save them to Google Drive.")
                }
            }
        }
    }
}
