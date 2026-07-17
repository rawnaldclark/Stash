package com.stash.core.data.sync.workers

import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import com.stash.core.data.audio.AudioDurationExtractor
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.SyncHistoryDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.mapper.toDomain
import com.stash.core.data.sync.SyncNotificationManager
import com.stash.core.data.sync.SyncStateManager
import com.stash.core.data.sync.TrackDownloadOutcome
import com.stash.core.data.sync.TrackDownloader
import com.stash.core.data.sync.classifier.DownloadFailureClassifier
import com.stash.core.data.sync.classifier.DownloadPhase
import com.stash.core.data.sync.classifier.FailureContext
import com.stash.core.model.DownloadFailureType
import com.stash.core.model.DownloadStatus
import com.stash.core.model.SyncState
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File

/**
 * Third worker in the sync chain. Downloads new tracks discovered by [DiffWorker].
 *
 * Promotes itself to a foreground service with an ongoing progress notification
 * so the system does not kill the work during lengthy downloads.
 *
 * Each track is downloaded through the [TrackDownloader] abstraction which
 * delegates to the full yt-dlp pipeline (search, download, tag, organize).
 *
 * Outputs [KEY_SYNC_ID], [KEY_DOWNLOADED], and [KEY_FAILED] counts.
 */
@HiltWorker
class TrackDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val downloadQueueDao: DownloadQueueDao,
    private val trackDao: TrackDao,
    private val syncHistoryDao: SyncHistoryDao,
    private val syncStateManager: SyncStateManager,
    private val syncNotificationManager: SyncNotificationManager,
    private val trackDownloader: TrackDownloader,
    private val tokenManager: com.stash.core.auth.TokenManager,
    private val audioDurationExtractor: AudioDurationExtractor,
    private val blocklistGuard: com.stash.core.data.blocklist.BlocklistGuard,
    private val streamingPreference: com.stash.core.data.prefs.StreamingPreference,
    private val classifier: DownloadFailureClassifier,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_SYNC_ID = "sync_id"
        const val KEY_DOWNLOADED = "downloaded"
        const val KEY_FAILED = "failed"

        /**
         * Optional input-data key. When present and non-`-1L`, the worker
         * runs in single-track mode against exactly this `download_queue`
         * row instead of the full sync-chain pass. Used by
         * `SingleTrackDownloadEnqueuer` (Task 9.5) to power one-tap retries
         * from the Failed Downloads viewer.
         */
        const val KEY_QUEUE_ID = "queue_id"
        private const val TAG = "TrackDownloadWorker"

        /**
         * Cap on the number of transient interruptions (network drop,
         * storage hiccup, mid-run auth expiry, UNKNOWN) we'll tolerate
         * before escalating a queue row from PENDING to FAILED. Once this
         * many consecutive attempts have ended in non-terminal failure,
         * the row surfaces in the Failed Downloads viewer so the user can
         * see and act on it instead of silently re-retrying forever.
         */
        const val TRANSIENT_RETRY_LIMIT = 10
    }

    /**
     * Called by WorkManager BEFORE [doWork] runs. Returning a [ForegroundInfo]
     * here signals that this is a long-running worker that needs foreground
     * service promotion, and WorkManager will start the foreground service
     * itself before invoking [doWork]. This bypasses the Android 12+ restriction
     * on starting foreground services from the background, because WorkManager
     * is the one starting the service — not an already-backgrounded app.
     *
     * Without this override, the worker is treated as a regular background job
     * and gets killed within ~10 minutes of the app being backgrounded.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo(
            title = "Syncing playlists",
            text = "Preparing downloads…",
            progress = -1f, // indeterminate until we know the track count
        )
    }

    override suspend fun doWork(): Result {
        // Single-track retry path (Task 9). When the enqueuer hands us a
        // specific download_queue row, skip the full sync-chain bookkeeping
        // (orphan sweeps, requeue, parallel fan-out) and just drive that
        // one row through the downloader.
        val singleQueueId = inputData.getLong(KEY_QUEUE_ID, -1L)
        if (singleQueueId != -1L) {
            return runSingleTrackMode(singleQueueId)
        }

        val startedAtMs = System.currentTimeMillis()
        val syncId = inputData.getLong(DiffWorker.KEY_SYNC_ID, -1L)
        if (syncId == -1L) {
            syncStateManager.onError("TrackDownloadWorker: missing sync ID")
            return Result.failure()
        }

        try {
            syncHistoryDao.updateStatus(syncId, SyncState.DOWNLOADING)

            // Determine which services are connected so we only retry their tracks.
            val connectedSources = buildList {
                if (tokenManager.isAuthenticated(com.stash.core.auth.model.AuthService.SPOTIFY)) add("SPOTIFY")
                if (tokenManager.isAuthenticated(com.stash.core.auth.model.AuthService.YOUTUBE_MUSIC)) add("YOUTUBE")
                add("BOTH")
            }
            Log.d(TAG, "Connected sources for retry: $connectedSources")

            // Diagnostic: log the actual queue state before any changes
            val statusCounts = downloadQueueDao.getStatusCounts()
            Log.i(TAG, "Queue status breakdown: ${statusCounts.map { "${it.status}=${it.count}" }}")
            val orphanCounts = downloadQueueDao.getOrphanedTrackCounts()
            Log.i(TAG, "Orphaned undownloaded tracks (no active queue entry): ${orphanCounts.map { "${it.source}=${it.cnt}" }}")

            // Self-healing sweep: drop queue entries whose track has no
            // currently sync-enabled parent playlist. Without this, queues
            // built before the predicate fix (when 1 enabled playlist could
            // pull thousands of orphaned rows) stay bloated forever.
            val sweptOrphans = downloadQueueDao.deleteOrphanedQueueEntries()
            if (sweptOrphans > 0) {
                Log.i(TAG, "Swept $sweptOrphans orphaned queue entries (tracks with no sync-enabled parent playlist)")
            }

            // Reset exhausted retries so tracks get another chance each sync.
            downloadQueueDao.resetExhaustedRetries()

            // Reset stale IN_PROGRESS entries from a previous interrupted run.
            // Safe because this worker is a unique chain — only one runs at a time.
            val resetInProgress = downloadQueueDao.resetStaleInProgress()
            if (resetInProgress > 0) {
                Log.i(TAG, "Reset $resetInProgress stale IN_PROGRESS entries back to PENDING")
            }

            // Streaming mode: do not drain ANY pending downloads — DiffWorker
            // already skipped enqueueing fresh ones (see its v0.9.30 gate),
            // but pre-toggle PENDING rows (queued while the user was in
            // Offline mode) would otherwise drain on every Sync Now and the
            // user sees "downloads happening" despite being in Online mode.
            // Leave them PENDING so a future switch back to Offline naturally
            // resumes them. Housekeeping above (orphan sweep, stale-IP reset)
            // still ran so counters stay accurate. Fall through to finalize
            // with downloaded=0 so the chain closes cleanly.
            if (streamingPreference.current()) {
                Log.i(TAG, "Streaming mode: skipping download drain (PENDING rows preserved)")
                syncStateManager.onDownloading(downloaded = 0, total = 0)
                return Result.success(
                    workDataOf(
                        KEY_SYNC_ID to syncId,
                        KEY_DOWNLOADED to 0,
                        KEY_FAILED to 0,
                    )
                )
            }

            // Re-queue tracks that are undownloaded but have no active queue entry.
            // This catches tracks whose retries were all exhausted and entries cleaned up,
            // or tracks that somehow never got queued.
            //
            // v0.9.30: SKIP this auto-requeue in streaming mode. DiffWorker
            // intentionally does NOT enqueue downloads for synced tracks
            // when streaming is on (the user wants metadata-only sync,
            // tracks play via Kennyy). Without this guard the auto-requeue
            // here would silently undo DiffWorker's skip and download
            // everything anyway. User-initiated downloads via the long-press
            // "Download to library" path still work — they insert into
            // download_queue directly and this worker processes the
            // existing pending rows further down.
            if (!streamingPreference.current()) {
                val unqueuedTrackIds = downloadQueueDao.getUnqueuedTrackIds(connectedSources)
                if (unqueuedTrackIds.isNotEmpty()) {
                    Log.i(TAG, "Re-queuing ${unqueuedTrackIds.size} undownloaded tracks with no active queue entry")
                    Log.i(TAG, "QueueTrace: TrackDownloadWorker.requeue track_ids=${unqueuedTrackIds.take(50)}${if (unqueuedTrackIds.size > 50) "...(${unqueuedTrackIds.size - 50} more)" else ""}")
                    val newEntries = unqueuedTrackIds.map { trackId ->
                        com.stash.core.data.db.entity.DownloadQueueEntity(
                            trackId = trackId,
                            syncId = syncId,
                        )
                    }
                    downloadQueueDao.insertAll(newEntries)
                }
            } else {
                Log.i(TAG, "Streaming mode: skipping auto-requeue of undownloaded tracks")
            }

            // Collect ALL pending items (from any sync) plus retryable failed items.
            val allPending = if (connectedSources.isNotEmpty()) {
                downloadQueueDao.getAllPendingBySources(connectedSources)
            } else {
                downloadQueueDao.getPendingBySyncId(syncId)
            }
            val retryItems = if (connectedSources.isNotEmpty()) {
                downloadQueueDao.getRetryableBySources(connectedSources)
            } else {
                emptyList()
            }
            // Deduplicate (a track could appear in both lists)
            val seen = mutableSetOf<Long>()
            val combined = allPending + retryItems
            val pendingItems = combined.filter { seen.add(it.trackId) }
            val total = pendingItems.size
            Log.d(TAG, "Download queue: ${allPending.size} pending + ${retryItems.size} retry = $total total (deduped)")
            // QueueTrace diagnostic: log per-trackId queue-row counts so we can spot duplicate-queueing patterns.
            val rowsByTrackId = combined.groupBy { it.trackId }
            val duplicates = rowsByTrackId.filter { it.value.size > 1 }
            if (duplicates.isNotEmpty()) {
                Log.w(TAG, "QueueTrace: ${duplicates.size} track_ids have multiple queue rows: ${duplicates.entries.take(10).map { (tid, rows) -> "$tid×${rows.size}" }}${if (duplicates.size > 10) "...(${duplicates.size - 10} more)" else ""}")
            }

            if (total == 0) {
                // Nothing to download; pass through to finalize.
                syncStateManager.onDownloading(downloaded = 0, total = 0)
                return Result.success(
                    workDataOf(
                        KEY_SYNC_ID to syncId,
                        KEY_DOWNLOADED to 0,
                        KEY_FAILED to 0,
                    )
                )
            }

            // The worker is already running as a foreground service because
            // getForegroundInfo() was overridden and WorkManager promoted us
            // before calling doWork(). This setForeground() call UPDATES the
            // notification to show the real track count now that we know it.
            syncStateManager.onDownloading(downloaded = 0, total = total)
            safeSetForeground(createForegroundInfo(downloaded = 0, total = total))

            // ── Parallel download loop ──────────────────────────────────
            //
            // All pending items are launched as concurrent coroutines. The
            // Semaphore(8) inside DownloadManager.downloadTrack() limits
            // how many yt-dlp processes run simultaneously — most coroutines
            // will be suspended at the semaphore, waiting for a slot.
            //
            // Previously this was a sequential for loop, which meant the
            // Semaphore only ever saw one caller and concurrency was always 1.
            // With this change, 8 downloads run in parallel, giving an ~8x
            // speedup (from ~4 tracks/min to ~32 tracks/min).
            //
            // Thread-safe counters (AtomicInteger/AtomicLong) ensure correct
            // tallies despite concurrent updates from multiple coroutines.
            val downloadedCount = AtomicInteger(0)
            val failedCount = AtomicInteger(0)
            val totalBytesDownloaded = AtomicLong(0)
            val firstError = AtomicReference<String?>(null)
            val playlistsChecked = inputData.getInt(DiffWorker.KEY_PLAYLISTS_CHECKED, 0)

            supervisorScope {
                for (queueItem in pendingItems) {
                    launch {
                        try {
                            downloadQueueDao.updateStatus(
                                id = queueItem.id,
                                status = DownloadStatus.IN_PROGRESS,
                            )

                            val trackEntity = trackDao.getById(queueItem.trackId)
                            if (trackEntity == null) {
                                Log.w(TAG, "Track ${queueItem.trackId} not found in DB, skipping")
                                val err = "Track not found in database"
                                val type = classifier.classify(
                                    FailureContext(
                                        phase = DownloadPhase.MATCHING,
                                        errorText = err,
                                        httpStatus = null,
                                        causeChain = emptyList(),
                                    )
                                )
                                downloadQueueDao.markFailed(
                                    queueId = queueItem.id,
                                    errorMessage = err.take(1000),
                                    failureType = type,
                                )
                                failedCount.incrementAndGet()
                                return@launch
                            }

                            // v0.9.15: Last-line defense. The DAO feeders
                            // (getAllPendingBySources etc.) already exclude
                            // blocklisted tracks, but a track could enter the
                            // queue via a path that runs BEFORE this worker
                            // sees it. If it's blocked, drop the queue entry
                            // and skip — never download a blocked identity.
                            if (blocklistGuard.isBlocked(
                                    artist = trackEntity.artist,
                                    title = trackEntity.title,
                                    spotifyUri = trackEntity.spotifyUri,
                                    youtubeId = trackEntity.youtubeId,
                                )) {
                                Log.d(TAG, "Skipping blocked track ${trackEntity.id} (${trackEntity.artist} - ${trackEntity.title})")
                                downloadQueueDao.deleteByTrackId(trackEntity.id)
                                return@launch
                            }

                            if (trackEntity.isDownloaded && trackEntity.filePath != null) {
                                downloadQueueDao.updateStatus(
                                    id = queueItem.id,
                                    status = DownloadStatus.COMPLETED,
                                    completedAt = System.currentTimeMillis(),
                                )
                                downloadedCount.incrementAndGet()
                                return@launch
                            }

                            val track = trackEntity.toDomain()
                            val outcome = trackDownloader.downloadTrack(
                                track = track,
                                preResolvedUrl = queueItem.youtubeUrl,
                            )

                            when (outcome) {
                                is TrackDownloadOutcome.Success -> {
                                    val fileSize = try {
                                        File(outcome.filePath).length()
                                    } catch (_: Exception) { 0L }

                                    // Extract metadata BEFORE markAsDownloaded so we can
                                    // persist bit-depth + sample-rate in the same row write.
                                    // The file is on disk at this point — `outcome.filePath`
                                    // is final, not a temp path.
                                    val meta = audioDurationExtractor.extract(outcome.filePath)

                                    trackDao.markAsDownloaded(
                                        trackId = queueItem.trackId,
                                        filePath = outcome.filePath,
                                        fileSizeBytes = fileSize,
                                        sampleRateHz = meta?.sampleRateHz,
                                        bitsPerSample = meta?.bitsPerSample,
                                    )

                                    if (meta != null) {
                                        // Format: source-of-truth for Library Health.
                                        // Write whenever the codec is known, even when
                                        // MMR couldn't compute a bitrate — variable-
                                        // bitrate codecs (FLAC) routinely return
                                        // bitrate=0 from MMR, and the prior gate
                                        // `bitrateKbps > 0` silently dropped the
                                        // format write for every FLAC download,
                                        // leaving file_format stuck at the legacy
                                        // 'opus' default. The DAO writes both columns
                                        // in one statement; a 0 quality value is
                                        // accepted and means "unknown" to readers
                                        // (Library Health renders it as `—`).
                                        if (meta.format != "unknown") {
                                            runCatching {
                                                trackDao.setFormatAndQuality(
                                                    trackId = queueItem.trackId,
                                                    fileFormat = meta.format,
                                                    qualityKbps = meta.bitrateKbps,
                                                )
                                            }.onFailure { e ->
                                                Log.w(TAG, "setFormatAndQuality failed for ${queueItem.trackId}", e)
                                            }
                                        }

                                        // Duration reconciliation: the file is
                                        // truth. Overwrite the DB when it's missing
                                        // OR when the value diverges from the file
                                        // by >10% — that gap is the signature of
                                        // yt-dlp matching a different cut (live,
                                        // extended) than Spotify's metadata
                                        // implied.
                                        if (meta.durationMs > 0) {
                                            val dbMs = trackEntity.durationMs
                                            val drift = if (dbMs > 0) {
                                                kotlin.math.abs(meta.durationMs - dbMs).toDouble() /
                                                    meta.durationMs.toDouble()
                                            } else 1.0
                                            if (dbMs == 0L || drift > 0.10) {
                                                runCatching {
                                                    trackDao.setDuration(queueItem.trackId, meta.durationMs)
                                                }.onSuccess {
                                                    if (dbMs > 0) {
                                                        Log.i(
                                                            TAG,
                                                            "duration reconciled: ${track.artist} - ${track.title} " +
                                                                "${dbMs}ms → ${meta.durationMs}ms (${(drift * 100).toInt()}% drift)",
                                                        )
                                                    }
                                                }.onFailure { e ->
                                                    Log.w(TAG, "setDuration failed for ${queueItem.trackId}", e)
                                                }
                                            }
                                        }
                                    }
                                    downloadQueueDao.updateStatus(
                                        id = queueItem.id,
                                        status = DownloadStatus.COMPLETED,
                                        completedAt = System.currentTimeMillis(),
                                    )
                                    totalBytesDownloaded.addAndGet(fileSize)
                                    downloadedCount.incrementAndGet()
                                }
                                is TrackDownloadOutcome.Unmatched -> {
                                    val err = "No YouTube match for: ${track.artist} - ${track.title}"
                                    Log.w(TAG, err)
                                    downloadQueueDao.incrementRetryCount(queueItem.id)
                                    downloadQueueDao.updateStatus(
                                        id = queueItem.id,
                                        status = DownloadStatus.FAILED,
                                        failureType = DownloadFailureType.NO_MATCH,
                                        errorMessage = err,
                                        rejectedVideoId = outcome.rejectedVideoId,
                                    )
                                    firstError.compareAndSet(null, err)
                                    failedCount.incrementAndGet()
                                }
                                is TrackDownloadOutcome.Failed -> {
                                    Log.e(TAG, "Download failed for ${track.artist} - ${track.title}: ${outcome.error}")
                                    val type = classifier.classify(
                                        FailureContext(
                                            phase = DownloadPhase.DOWNLOADING,
                                            errorText = outcome.error,
                                            httpStatus = null,
                                            causeChain = emptyList(),
                                        )
                                    )
                                    val truncated = outcome.error.take(1000)
                                    val isTerminal = DownloadFailureClassifier.isTerminal(type)
                                    val exhausted = queueItem.retryCount >= TRANSIENT_RETRY_LIMIT
                                    if (isTerminal || exhausted) {
                                        // Real terminal failure OR transient that won't
                                        // stop happening — surface in the viewer.
                                        // markFailed bumps completed_at; retry_count is
                                        // incremented separately to match prior tally
                                        // semantics for terminal failures.
                                        downloadQueueDao.incrementRetryCount(queueItem.id)
                                        downloadQueueDao.markFailed(
                                            queueId = queueItem.id,
                                            errorMessage = truncated,
                                            failureType = type,
                                        )
                                        firstError.compareAndSet(null, truncated)
                                        failedCount.incrementAndGet()
                                    } else {
                                        // Interruption — bump retry_count, retain
                                        // error_message + failure_type for telemetry,
                                        // but leave status PENDING so the next sync
                                        // re-attempts. revertToPendingAfterInterruption
                                        // increments retry_count itself, so we don't
                                        // also call incrementRetryCount here.
                                        downloadQueueDao.revertToPendingAfterInterruption(
                                            queueId = queueItem.id,
                                            lastErrorMessage = truncated,
                                            lastFailureType = type,
                                        )
                                        Log.i(
                                            TAG,
                                            "Interrupted (type=$type), keeping queue ${queueItem.id} PENDING for next sync: ${truncated.take(120)}",
                                        )
                                    }
                                }
                                is TrackDownloadOutcome.Deferred -> {
                                    // v0.9.17 strict-FLAC: lossless source unavailable
                                    // and yt-dlp fallback off. The DAO row was already
                                    // written to WAITING_FOR_LOSSLESS by
                                    // TrackDownloaderImpl — do NOT increment retries,
                                    // do NOT mark FAILED, do NOT tally as failure.
                                    // LosslessRetryScheduler owns the re-attempt signal.
                                    Log.i(TAG, "Deferred (waiting for lossless): ${track.artist} - ${track.title}")
                                }
                            }
                        } catch (ce: kotlinx.coroutines.CancellationException) {
                            // The worker is being cancelled (user hit "Cancel" on the
                            // notification, or WorkManager pulled the plug). DO NOT mark
                            // the row FAILED — propagate cleanly so the row stays PENDING
                            // (status was set to IN_PROGRESS earlier in this block; the
                            // worker-level resetStaleInProgress() on the next run flips
                            // it back to PENDING). Marking thousands of in-flight rows
                            // FAILED on cancel was the root cause of the "2325 failed
                            // rows after Stop" smoke-test crash.
                            throw ce
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to download track ${queueItem.trackId}", e)
                            // Cycle-safe cause-chain extraction: Throwable.getCause() can
                            // return `this` or form cycles, which would hang generateSequence
                            // and OOM .toList(). Guard with self-reference check + depth cap.
                            val causeChain = generateSequence<Throwable>(e) { it.cause.takeIf { c -> c !== it } }
                                .take(16)
                                .map { it::class.java.simpleName }
                                .toList()
                            val type = classifier.classify(
                                FailureContext(
                                    phase = DownloadPhase.DOWNLOADING,
                                    errorText = e.message,
                                    httpStatus = null,
                                    causeChain = causeChain,
                                )
                            )
                            val truncated = e.message?.take(1000)
                            val isTerminal = DownloadFailureClassifier.isTerminal(type)
                            val exhausted = queueItem.retryCount >= TRANSIENT_RETRY_LIMIT
                            if (isTerminal || exhausted) {
                                // Real terminal failure OR transient that won't stop
                                // happening — surface in Failed Downloads viewer.
                                downloadQueueDao.markFailed(
                                    queueId = queueItem.id,
                                    errorMessage = truncated,
                                    failureType = type,
                                )
                                failedCount.incrementAndGet()
                            } else {
                                // Interruption — leave row at PENDING so next sync
                                // re-attempts. Bump retry_count + record last-error
                                // for telemetry. Does NOT increment failedCount: this
                                // is not a tracked failure in the per-sync tally.
                                downloadQueueDao.revertToPendingAfterInterruption(
                                    queueId = queueItem.id,
                                    lastErrorMessage = truncated,
                                    lastFailureType = type,
                                )
                                Log.i(
                                    TAG,
                                    "Interrupted (type=$type), keeping queue ${queueItem.id} PENDING for next sync: ${truncated?.take(120)}",
                                )
                            }
                        }

                        // Update progress notification after each completed track.
                        val completed = downloadedCount.get() + failedCount.get()
                        syncStateManager.onDownloading(downloaded = completed, total = total)
                        syncNotificationManager.updateProgress(
                            title = "Syncing playlists",
                            text = "Downloaded $completed of $total",
                            progress = 0.25f + 0.70f * (completed.toFloat() / total),
                        )

                        // Flush sync history tallies every 10 tracks for crash resilience.
                        if (completed % 10 == 0) {
                            syncHistoryDao.updateCounts(
                                id = syncId,
                                playlistsChecked = playlistsChecked,
                                newTracksFound = total,
                                tracksDownloaded = downloadedCount.get(),
                                tracksFailed = failedCount.get(),
                                bytesDownloaded = totalBytesDownloaded.get(),
                            )
                        }
                    }
                }
            }
            // supervisorScope waits for all launched coroutines to complete.

            // Final tally flush.
            val finalDownloaded = downloadedCount.get()
            val finalFailed = failedCount.get()

            syncHistoryDao.updateCounts(
                id = syncId,
                playlistsChecked = playlistsChecked,
                newTracksFound = total,
                tracksDownloaded = finalDownloaded,
                tracksFailed = finalFailed,
                bytesDownloaded = totalBytesDownloaded.get(),
            )

            // Store first download error in sync history for on-device debugging.
            val firstErr = firstError.get()
            if (firstErr != null) {
                val summary = "$finalFailed/$total downloads failed. First error: $firstErr"
                syncHistoryDao.updateStatus(
                    id = syncId,
                    status = SyncState.DOWNLOADING,
                    errorMessage = summary.take(1000),
                )
            }

            val durationMs = System.currentTimeMillis() - startedAtMs
            val durationSec = durationMs / 1000.0
            val tpm = if (durationSec > 0) finalDownloaded / (durationSec / 60.0) else 0.0
            Log.i(
                TAG,
                "PerfSummary: ${finalDownloaded} tracks in ${"%.1f".format(durationSec)}s " +
                    "(${"%.1f".format(tpm)} tracks/min); failed=${finalFailed}",
            )

            return Result.success(
                workDataOf(
                    KEY_SYNC_ID to syncId,
                    KEY_DOWNLOADED to finalDownloaded,
                    KEY_FAILED to finalFailed,
                )
            )
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // User cancelled the sync (or system pulled the plug). Mark the
            // sync history row CANCELLED and reset the in-memory phase back to
            // Idle so the UI stops showing a progress card. DO NOT mark the
            // sync FAILED — cancellation isn't a failure (it renders as a
            // neutral receipt, not a red one), and the chain's leftover
            // IN_PROGRESS queue rows will be reset to PENDING by the next
            // run's resetStaleInProgress() sweep.
            Log.i(TAG, "Sync cancelled by user or system")
            syncHistoryDao.updateStatus(
                id = syncId,
                status = SyncState.CANCELLED,
                completedAt = System.currentTimeMillis(),
                errorMessage = "Cancelled",
            )
            syncStateManager.reset()
            throw ce
        } catch (e: Exception) {
            Log.e(TAG, "Download worker failed", e)
            syncHistoryDao.updateStatus(
                id = syncId,
                status = SyncState.FAILED,
                completedAt = System.currentTimeMillis(),
                errorMessage = e.message,
            )
            syncStateManager.onError("Download failed: ${e.message}", e)
            return Result.failure(workDataOf(KEY_SYNC_ID to syncId))
        }
    }

    /**
     * Single-track retry path. Reads exactly one `download_queue` row and
     * drives it through the same [TrackDownloader] the chain uses, then
     * translates the [TrackDownloadOutcome] into a queue update. Used by
     * `SingleTrackDownloadEnqueuer` (Task 9.5) — one OneTimeWorkRequest per
     * row, so concurrency for "Retry all" comes from WorkManager fanning
     * out N independent instances rather than the chain's supervisorScope
     * fan-out.
     *
     * This path deliberately omits the chain-mode bookkeeping (sync history
     * tally, orphan sweep, requeue, foreground-progress fraction over
     * `total`), since the retry is a single sequential invocation and the
     * Failed Downloads viewer drives its own UI off the queue Flow.
     */
    private suspend fun runSingleTrackMode(queueId: Long): Result {
        // Single-track mode does NOT publish into SyncStateManager.phase — that singleton
        // flow is shared with a possibly-running chain sync and overwriting it would
        // stomp the chain's progress counter. The Failed Downloads viewer reads queue
        // rows directly via DownloadQueueDao.getFailedDownloads().
        val entry = downloadQueueDao.getById(queueId)
        if (entry == null) {
            Log.w(TAG, "Single-track mode: queue row $queueId not found")
            return Result.success()
        }
        val trackEntity = trackDao.getById(entry.trackId)
        if (trackEntity == null) {
            Log.w(TAG, "Single-track mode: track ${entry.trackId} not found for queue $queueId")
            return Result.failure()
        }

        val track = trackEntity.toDomain()
        Log.i(
            TAG,
            "Single-track mode: queueId=$queueId trackId=${entry.trackId} (${track.artist} - ${track.title})",
        )

        // Swap the foreground notification to single-track wording so the user
        // sees "Retrying download — Artist – Title" instead of the chain-mode
        // "Syncing playlists / Preparing downloads…" copy.
        safeSetForeground(
            createForegroundInfo(
                title = "Retrying download",
                text = "${track.artist} – ${track.title}",
                progress = -1f, // indeterminate — single-track has no N-of-M progress
            ),
        )

        // Manual retries deliberately do NOT increment retry_count — user-initiated
        // attempts should not consume the auto-retry budget owned by chain mode.

        // Mark the row IN_PROGRESS before invoking the downloader so the Failed
        // Downloads viewer reflects the active state and spam-tapping Retry is
        // idempotent (the row leaves FAILED immediately and re-enqueue paths
        // can no-op on non-FAILED rows). Relies on updateStatus's defaults to
        // null out error_message / completed_at / rejected_video_id and reset
        // failure_type to NONE, matching the chain-mode IN_PROGRESS call.
        downloadQueueDao.updateStatus(
            id = entry.id,
            status = DownloadStatus.IN_PROGRESS,
        )

        val outcome = try {
            trackDownloader.downloadTrack(
                track = track,
                preResolvedUrl = entry.youtubeUrl,
            )
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // Single-track retries are user-initiated, but WorkManager can
            // still cancel us (e.g. the user backgrounded the app for too
            // long). Don't mark the row FAILED — the queue stays in whatever
            // state IN_PROGRESS-with-cancel left it; the next sync's
            // resetStaleInProgress() will flip it back to PENDING.
            throw ce
        } catch (e: Exception) {
            Log.e(TAG, "Single-track downloader threw for queue ${entry.id}", e)
            val truncated = (e.message ?: e::class.simpleName ?: "Unknown error").take(1000)
            // Cycle-safe cause-chain extraction (see chain mode for rationale).
            val causeChain = generateSequence<Throwable>(e) { it.cause.takeIf { c -> c !== it } }
                .take(16)
                .map { it::class.java.simpleName }
                .toList()
            val type = classifier.classify(
                FailureContext(
                    phase = DownloadPhase.DOWNLOADING,
                    errorText = e.message,
                    httpStatus = null,
                    causeChain = causeChain,
                ),
            )
            // Single-track mode is user-initiated, so we DO want feedback
            // even on transient interruptions — surface them. Only the
            // chain-mode path uses revertToPendingAfterInterruption (a
            // sync passing through and leaving the row for the next sweep).
            // A failed manual retry should show the user what went wrong.
            downloadQueueDao.markFailed(
                queueId = entry.id,
                errorMessage = truncated,
                failureType = type,
            )
            // Catastrophe is handled; don't ask WorkManager to retry this row.
            return Result.success()
        }

        when (outcome) {
            is TrackDownloadOutcome.Success -> {
                // Persist the downloaded state on the TRACK row, mirroring chain
                // mode (doWork). Without this the file lands on disk but
                // tracks.is_downloaded / file_path stay unset, so the UI keeps
                // showing the track as not-downloaded and it isn't playable from
                // disk. (Pre-v0.9.63 single-track mode skipped this entirely.)
                val fileSize = try { File(outcome.filePath).length() } catch (_: Exception) { 0L }
                val meta = audioDurationExtractor.extract(outcome.filePath)
                trackDao.markAsDownloaded(
                    trackId = entry.trackId,
                    filePath = outcome.filePath,
                    fileSizeBytes = fileSize,
                    sampleRateHz = meta?.sampleRateHz,
                    bitsPerSample = meta?.bitsPerSample,
                )
                if (meta != null && meta.format != "unknown") {
                    runCatching {
                        trackDao.setFormatAndQuality(
                            trackId = entry.trackId,
                            fileFormat = meta.format,
                            qualityKbps = meta.bitrateKbps,
                        )
                    }.onFailure { e -> Log.w(TAG, "setFormatAndQuality failed for ${entry.trackId}", e) }
                }
                downloadQueueDao.updateStatus(
                    id = entry.id,
                    status = DownloadStatus.COMPLETED,
                    errorMessage = null,
                    completedAt = System.currentTimeMillis(),
                    failureType = DownloadFailureType.NONE,
                    rejectedVideoId = null,
                )
                Log.i(TAG, "Single-track mode: Success for queue ${entry.id}")
            }
            is TrackDownloadOutcome.Failed -> {
                val truncated = outcome.error.take(1000)
                val type = classifier.classify(
                    FailureContext(
                        phase = DownloadPhase.DOWNLOADING,
                        errorText = outcome.error,
                        httpStatus = null,
                        causeChain = emptyList(),
                    ),
                )
                downloadQueueDao.markFailed(
                    queueId = entry.id,
                    errorMessage = truncated,
                    failureType = type,
                )
                Log.w(
                    TAG,
                    "Single-track mode: Failed for queue ${entry.id} (type=$type): ${truncated.take(120)}",
                )
            }
            is TrackDownloadOutcome.Unmatched -> {
                val err = "No YouTube match for: ${track.artist} - ${track.title}"
                downloadQueueDao.updateStatus(
                    id = entry.id,
                    status = DownloadStatus.FAILED,
                    errorMessage = err,
                    completedAt = System.currentTimeMillis(),
                    failureType = DownloadFailureType.NO_MATCH,
                    rejectedVideoId = outcome.rejectedVideoId,
                )
                Log.w(TAG, "Single-track mode: Unmatched for queue ${entry.id}")
            }
            is TrackDownloadOutcome.Deferred -> {
                // v0.9.17 strict-FLAC: lossless source unavailable and
                // yt-dlp fallback off. The DAO row was already moved to
                // WAITING_FOR_LOSSLESS by TrackDownloaderImpl — leave it
                // alone and return success so WorkManager doesn't reschedule.
                Log.i(
                    TAG,
                    "Single-track mode: Deferred for queue ${entry.id} (lossless source unavailable)",
                )
            }
        }
        return Result.success()
    }

    /**
     * Best-effort [setForeground]. On Android 12+ (and stricter on 14/15,
     * which this app targets) calling `setForeground()` re-invokes
     * `startForegroundService()`, which the OS REFUSES with
     * [android.app.ForegroundServiceStartNotAllowedException] when the app is in
     * the background — which it routinely is by the time this worker runs, since
     * the PlaylistFetch + Diff phases ahead of it can take a minute on a large
     * library (screen off / app switched away in the meantime). That exception
     * is only a *notification update* failure, but uncaught it propagated to
     * doWork's top-level catch and FAILED the entire sync with 0 downloads
     * ("sync falls flat"). The actual download work doesn't need the
     * re-promotion — the worker is already executing — so swallow the failure
     * and continue as a normal background job. Mirrors
     * [DiscoveryDownloadWorker.safeUpdateForeground].
     */
    private suspend fun safeSetForeground(info: ForegroundInfo) {
        runCatching { setForeground(info) }
            .onFailure { Log.w(TAG, "setForeground failed (foreground-restricted / backgrounded); continuing without notification update", it) }
    }

    /**
     * Creates [ForegroundInfo] for the ongoing download notification.
     *
     * Overload used when we know the current track counts during the
     * download loop — converts them into a progress fraction and a
     * "Downloading track N of M" body.
     */
    private fun createForegroundInfo(downloaded: Int, total: Int): ForegroundInfo {
        val progress = if (total > 0) {
            val base = 0.25f
            val span = 0.70f
            base + span * (downloaded.toFloat() / total)
        } else {
            -1f // indeterminate
        }
        return createForegroundInfo(
            title = "Syncing playlists",
            text = if (total > 0) "Downloading track $downloaded of $total" else "Preparing downloads…",
            progress = progress,
        )
    }

    /**
     * Builds a [ForegroundInfo] with a "Cancel" action wired to
     * [WorkManager.createCancelPendingIntent] so the user can abort the
     * sync by tapping the notification action. [progress] can be negative
     * to request an indeterminate spinner.
     */
    private fun createForegroundInfo(
        title: String,
        text: String,
        progress: Float,
    ): ForegroundInfo {
        val cancelIntent = WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(id)
        val notification = syncNotificationManager.buildProgressNotification(
            title = title,
            text = text,
            progress = progress,
            cancelIntent = cancelIntent,
        )
        return ForegroundInfo(
            SyncNotificationManager.NOTIFICATION_ID_PROGRESS,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }
}
