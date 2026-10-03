package com.stash.data.download.lossless

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.stash.core.data.audio.AudioDurationExtractor
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.lossless.LosslessUpgrader
import com.stash.core.model.Track
import com.stash.core.model.UpgradeResult
import com.stash.data.download.DownloadManager
import com.stash.data.download.TrackDownloadResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import com.stash.data.download.lossless.relay.LosslessDownloadPurpose
import kotlinx.coroutines.withContext

/**
 * Bridges the Now Playing dialog to [DownloadManager.tryLosslessDownload].
 * Maps the nullable result onto the user-facing [UpgradeResult] tri-state.
 *
 * Conservative mapping: any non-Success outcome becomes [NoMatch].
 * The user doesn't need to distinguish "registry returned null" from
 * "lossless URL came back 404" — both are "no FLAC for you right
 * now." Thrown exceptions become [Error] so the snackbar can say
 * "Couldn't check lossless sources" rather than the misleading
 * "no match."
 *
 * **Persists DB row + deletes old file on Success.** [DownloadManager.tryLosslessDownload]
 * writes the FLAC file to disk via [com.stash.data.download.TrackFinalizer] but
 * deliberately does no DB writes — its KDoc explicitly delegates that to the
 * caller. The sync pipeline gets this right because [com.stash.core.data.sync.workers.TrackDownloadWorker]
 * runs the persist block after Success; the upgrade path was bypassing it
 * entirely, leaving Now Playing reading a stale `'opus'` row while the new
 * FLAC sat orphaned on disk. The post-Success block here mirrors the worker's
 * canonical sequence: `markAsDownloaded` → `setFormatAndQuality` → conditional
 * `setDuration` → best-effort old-file delete, plus `clearMatchPick` (#531).
 */
@Singleton
class LosslessUpgraderImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadManager: DownloadManager,
    private val trackDao: TrackDao,
    private val audioExtractor: AudioDurationExtractor,
    private val losslessPrefs: LosslessSourcePreferences,
) : LosslessUpgrader {

    override suspend fun isLosslessEnabled(): Boolean = losslessPrefs.enabledNow()

    override suspend fun upgradeToLossless(track: Track, sweep: Boolean): UpgradeResult = runCatching {
        // Capture old path BEFORE the download — the row's current file_path
        // gets overwritten by markAsDownloaded, so reading post-persist would
        // give us the new path and we'd skip the delete. May be null for stub
        // tracks that never downloaded; null-safe at the delete site.
        val oldPath = track.filePath

        val purpose = if (sweep) LosslessDownloadPurpose() else null
        val result = if (purpose != null) {
            withContext(purpose) { downloadManager.tryLosslessDownload(track, forced = true) }
        } else {
            downloadManager.tryLosslessDownload(track, forced = true)
        }
        when (result) {
            is TrackDownloadResult.Success -> {
                persistUpgrade(track, result.filePath, oldPath)
                UpgradeResult.Upgraded
            }
            null -> if (purpose?.pacedRetryAfterSec != null) UpgradeResult.Paced else UpgradeResult.NoMatch
            is TrackDownloadResult.Unmatched,
            is TrackDownloadResult.Failed,
            TrackDownloadResult.Deferred -> UpgradeResult.NoMatch
        }
    }.getOrElse { e ->
        Log.w(TAG, "upgradeToLossless threw for ${track.id}", e)
        UpgradeResult.Error
    }

    /**
     * Mirrors the canonical post-Success block from
     * [com.stash.core.data.sync.workers.TrackDownloadWorker]. Each DAO call is
     * `runCatching`-wrapped so a single failure doesn't crash the upgrade —
     * the user already has a valid FLAC on disk; the worst case here is a
     * partial DB update (e.g., file_path written but format not), which the
     * next sync run can reconcile.
     */
    private suspend fun persistUpgrade(track: Track, newPath: String, oldPath: String?) {
        val fileSize = runCatching { File(newPath).length() }.getOrDefault(0L)
        val meta = runCatching { audioExtractor.extract(newPath) }
            .onFailure { e -> Log.w(TAG, "extract failed for $newPath", e) }
            .getOrNull()

        runCatching {
            trackDao.markAsDownloaded(
                trackId = track.id,
                filePath = newPath,
                fileSizeBytes = fileSize,
                sampleRateHz = meta?.sampleRateHz,
                bitsPerSample = meta?.bitsPerSample,
            )
        }.onFailure { e -> Log.w(TAG, "markAsDownloaded failed for ${track.id}", e) }

        // A song whose audio the user picked in Failed Matches (#531) now
        // plays the FLAC: forget the pick, or a later re-download would fetch
        // the picked video again. Automatic sweeps skip picked songs, so every
        // saved upgrade of one was asked for (Now Playing, or a Library batch).
        runCatching { trackDao.clearMatchPick(track.id) }
            .onFailure { e -> Log.w(TAG, "clearMatchPick failed for ${track.id}", e) }

        if (meta != null && meta.format != "unknown") {
            runCatching {
                trackDao.setFormatAndQuality(
                    trackId = track.id,
                    fileFormat = meta.format,
                    qualityKbps = meta.bitrateKbps,
                )
            }.onFailure { e -> Log.w(TAG, "setFormatAndQuality failed for ${track.id}", e) }
        }

        // Duration reconciliation: write only if DB is empty or drift > 10%,
        // mirroring TrackDownloadWorker. Lossless sources occasionally serve
        // a different cut (album vs single edit) than Spotify's duration
        // implied — let the file be the truth in that case.
        if (meta != null && meta.durationMs > 0) {
            val dbMs = track.durationMs
            val drift = if (dbMs > 0) {
                kotlin.math.abs(meta.durationMs - dbMs).toDouble() / meta.durationMs.toDouble()
            } else 1.0
            if (dbMs == 0L || drift > 0.10) {
                runCatching { trackDao.setDuration(track.id, meta.durationMs) }
                    .onFailure { e -> Log.w(TAG, "setDuration failed for ${track.id}", e) }
            }
        }

        // Delete the old file if it's a different path than the new one.
        // Same-path is rare but possible (lossless source landed on the exact
        // same filename) — skip the delete in that case, otherwise we'd be
        // deleting the file we just wrote. Null oldPath = the row had no
        // prior file (defensive; shouldn't happen for a track currently
        // playing).
        if (!oldPath.isNullOrEmpty() && oldPath != newPath) {
            deleteTrackFile(oldPath)
        }
    }

    /**
     * SAF-aware delete. content:// URIs (user-chosen external-storage tree)
     * route through [DocumentFile]; raw paths route through [File]. Best-
     * effort: failures swallowed, since the upgrade has already succeeded
     * from the user's perspective and an orphaned old file is harmless
     * compared to surfacing a confusing error.
     */
    private fun deleteTrackFile(path: String): Boolean = runCatching {
        if (path.startsWith("content://")) {
            DocumentFile.fromSingleUri(context, Uri.parse(path))?.delete() == true
        } else {
            File(path).delete()
        }
    }.onFailure { e -> Log.w(TAG, "deleteTrackFile failed for $path", e) }
        .getOrDefault(false)

    private companion object {
        const val TAG = "LosslessUpgrader"
    }
}
