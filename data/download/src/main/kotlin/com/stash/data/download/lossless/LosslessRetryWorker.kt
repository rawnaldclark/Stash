package com.stash.data.download.lossless

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.sync.SingleTrackDownloadEnqueuer
import com.stash.core.model.DownloadMode
import com.stash.core.model.DownloadStatus
import com.stash.core.model.DownloadWaitReason
import com.stash.data.download.lossless.tidal.TidalAac
import kotlinx.coroutines.CancellationException
import com.stash.data.download.lossless.forLibrarySource
import com.stash.data.download.lossless.quota.LosslessQuota
import com.stash.data.download.lossless.tidal.TidalAacResolver
import com.stash.data.download.lossless.relay.LosslessDownloadPurpose
import kotlinx.coroutines.withContext
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * One-shot sweep over rows in [DownloadStatus.WAITING_FOR_LOSSLESS]. For
 * each, calls the lossless [LosslessSourceRegistry]; on success flips the
 * row to [DownloadStatus.PENDING] so the standard
 * [com.stash.core.data.sync.workers.TrackDownloadWorker] picks it up, on
 * failure leaves the row alone for the next trigger.
 *
 * Does not download — it re-resolves, re-queues, and hands each re-queued
 * row to [SingleTrackDownloadEnqueuer] so it downloads now rather than at
 * the next sync. Never writes COMPLETED or FAILED; the download worker owns
 * that once status is PENDING. That download runs in either mode, so the
 * sweep takes a sync's row only while the song should still download (#532):
 * in Download mode while a playlist the sync downloads still holds it, in
 * Stream-only mode only when the user asked for it. The others keep waiting.
 *
 * Runs as a download ([LosslessDownloadPurpose]): when the relay paces
 * downloads, the sweep stops at that row — every row after it would be paced
 * too — and asks WorkManager to retry later (its backoff grows each time), so
 * a waiting library drains as the day's pool allows.
 *
 * The sweep booked for the daily allowance's reset ([KEY_AT_RESET]) resolves nothing: a lookup
 * here and the download's own would count two lossless answers per song against the new day.
 * It re-queues every waiting row as it is and the download makes the one lookup (a row with no
 * lossless anywhere simply waits again). Run before the reset on the relays' clock (still
 * [LosslessQuota.isSpent]), it touches nothing and retries later.
 *
 * By Download quality: in Lossless mode, as above. In AAC 320 mode a waiting row is one Tidal had
 * no AAC of ("Not available as AAC yet"): the sweep asks Tidal for its AAC (never a lossless
 * lookup) and re-queues the rows it now has. In Opus mode nothing waits: a row left waiting from
 * another mode is re-queued as it is and downloads from YouTube. The scheduler also runs a
 * sweep when the mode changes.
 *
 * By the row's own reason ([DownloadWaitReason], kept on the row): one that waited because Tidal
 * was busy is re-queued without a check (its download asks again, the one answer that counts);
 * one waiting for the daily reset is left to the reset sweep.
 *
 * One answer per song: a match this sweep finds is handed to the download through
 * [ResolvedSourceHandoff], which takes it instead of asking the relay a second time (a found
 * answer costs a unit of the day's allowance). At most [MAX_LOOKUPS_PER_PASS] rows are looked up
 * per pass, starting where the last pass stopped ([RetrySweepCursor]); an AAC miss is cached on
 * its own stamp, so a row Tidal lacks costs nothing on the passes after.
 *
 * Enqueued by `LosslessRetryScheduler` (Task 9) under a unique work name
 * so multiple triggers within a short window collapse to a single sweep.
 * Uses the standard download Constraints (network mode pref) so it
 * doesn't fire on metered if the user disabled that.
 *
 * Lives in `:data:download` rather than `:core:data` because
 * [LosslessSourceRegistry] is module-local to `:data:download`, and the
 * dependency graph runs `:data:download` -> `:core:data` (not the other
 * way). Putting the worker in `:core:data` would require a circular
 * module dependency.
 */
@HiltWorker
class LosslessRetryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val downloadQueueDao: DownloadQueueDao,
    private val trackDao: TrackDao,
    private val registry: LosslessSourceRegistry,
    private val singleTrackDownloadEnqueuer: SingleTrackDownloadEnqueuer,
    private val streamingPreference: StreamingPreference,
    private val quota: LosslessQuota,
    private val losslessPrefs: LosslessSourcePreferences,
    private val tidalAacResolver: TidalAacResolver,
    private val handoff: ResolvedSourceHandoff,
    private val cursor: RetrySweepCursor,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val mode = losslessPrefs.downloadModeNow()
        // The allowance's reset matters to every relay lookup; Opus asks none.
        val atReset = mode != DownloadMode.OPUS && inputData.getBoolean(KEY_AT_RESET, false)
        if (atReset) {
            quota.awaitLoaded()
            // Early on the relays' clock: every download would be skipped (or capped) again.
            if (quota.isSpent()) return Result.retry()
        }
        // Every row resolved here downloads at once, so a sync's row that shouldn't
        // download now is left out before it costs a lookup, still waiting: in Stream-only
        // mode one nobody asked for (#474), in Download mode one whose playlist was
        // switched off.
        val waiting = downloadQueueDao.waitingForLosslessTracks(streamOnly = streamingPreference.current())
        if (waiting.isEmpty()) {
            return Result.success(workDataOf(KEY_RESOLVED to 0, KEY_TOTAL to 0))
        }

        // Rows that need a relay lookup are checked at most MAX_LOOKUPS_PER_PASS a pass, starting
        // after the row the last pass stopped at, so the newer rows are reached too.
        val start = cursor.get()
        val ordered = waiting.sortedBy { it.id }.let { rows ->
            rows.filter { it.id > start } + rows.filter { it.id <= start }
        }
        var resolvedCount = 0
        var lookups = 0
        var stoppedAt: Long? = null
        val purpose = LosslessDownloadPurpose()
        for (entry in ordered) {
            val track = trackDao.getById(entry.trackId) ?: continue
            // The user picked this song's audio in Failed Matches (#531). Its
            // download is that video, waiting for Lossy fallback (turning it
            // on requeues every waiting row). A lookup by title would find
            // the recording they rejected, and asked the shared relay again
            // on every trigger, only for the download to wait again.
            if (track.matchPickedAt != null) continue
            val reason = DownloadWaitReason.fromCode(entry.errorMessage)
            when {
                // Opus mode never waits: the row downloads from YouTube now. At the reset every
                // row goes as it is: its download makes the one lookup of the new day.
                mode == DownloadMode.OPUS || atReset -> {
                    requeue(entry.id)
                    resolvedCount++
                    continue
                }
                // Tidal was busy: no verdict to check. The download asks again itself, and its
                // answer is the only one (a check here would be a second).
                reason == DownloadWaitReason.TIDAL_BUSY -> {
                    requeue(entry.id)
                    resolvedCount++
                    continue
                }
                // Waiting for the reset: the reset sweep takes it. Before then, nothing.
                reason == DownloadWaitReason.RESET -> {
                    if (quota.isSpent()) continue
                    requeue(entry.id)
                    resolvedCount++
                    continue
                }
                reason == DownloadWaitReason.FALLBACK -> continue
            }
            if (lookups >= MAX_LOOKUPS_PER_PASS) {
                stoppedAt = entry.id - 1
                break
            }
            lookups++
            val query = queryFor(track)
            if (mode == DownloadMode.AAC) {
                // AAC 320 mode: Tidal's AAC only (?aac=only), never a lossless lookup. A miss is
                // cached on its own stamp (BitchordClient), so the next pass skips it for free.
                when (val aac = tidalAacResolver.resolve(query, aacOnly = true)) {
                    is TidalAac.Found -> {
                        handoff.put(track.id, aac.source, ResolvedSourceHandoff.Kind.TIDAL_AAC)
                        requeue(entry.id)
                        resolvedCount++
                    }
                    TidalAac.NoAac -> Unit
                    // Busy, paced or failed: the resolver booked the next sweep for when it clears.
                    // A spent day: the reset sweep takes every row. Either way, stop here.
                    is TidalAac.Unavailable -> {
                        stoppedAt = entry.id - 1
                        break
                    }
                }
                continue
            }
            // runCatching mirrors DownloadManager's defensive pattern:
            // a single bad source / network blip mustn't halt the whole
            // sweep. Sources are also expected to swallow their own
            // errors and return null, but defense in depth is cheap. A stop
            // is not a failure: it propagates.
            val match = runCatching {
                withContext(purpose) { registry.resolve(query) }
            }.onFailure { if (it is CancellationException) throw it }.getOrNull()
            if (match != null) {
                // The download takes this match instead of asking again: one answer, one unit.
                handoff.put(track.id, match, ResolvedSourceHandoff.Kind.LOSSLESS)
                requeue(entry.id)
                resolvedCount++
            } else if (purpose.pacedRetryAfterSec != null) {
                // The pool is ahead of today's pace: every later row would be paced too.
                cursor.set(entry.id - 1)
                return Result.retry()
            }
        }
        // A pass that stopped early starts with the row it stopped at (ids are its order); one
        // that went all the way round starts from the beginning.
        cursor.set(stoppedAt ?: 0L)
        return Result.success(
            workDataOf(
                KEY_RESOLVED to resolvedCount,
                KEY_TOTAL to waiting.size,
            ),
        )
    }

    /** The catalog query for a library row, as the download makes it. */
    private fun queryFor(track: com.stash.core.data.db.entity.TrackEntity): TrackQuery = TrackQuery(
        artist = track.artist,
        title = track.title,
        album = track.album.takeIf { it.isNotBlank() },
        isrc = track.isrc,
        durationMs = track.durationMs.takeIf { it > 0 },
        spotifyUri = track.spotifyUri,
    ).forLibrarySource(track.source, track.musicVideoType)

    /** Back to PENDING, and its download starts now. */
    private suspend fun requeue(queueId: Long) {
        downloadQueueDao.updateStatus(id = queueId, status = DownloadStatus.PENDING)
        singleTrackDownloadEnqueuer.enqueue(queueId)
    }

    companion object {
        /**
         * Relay lookups one sweep makes at most. Rows past it wait for the next pass, which
         * starts where this one stopped ([RetrySweepCursor]).
         */
        internal const val MAX_LOOKUPS_PER_PASS = 25
        const val UNIQUE_WORK_NAME = "lossless-retry"

        /**
         * Input-data key: true for the sweep booked for the daily allowance's reset, which
         * re-queues without resolving (see the class doc).
         */
        const val KEY_AT_RESET = "lossless_retry_at_reset"

        /** Output-data key: how many WAITING_FOR_LOSSLESS rows were flipped to PENDING this sweep. */
        const val KEY_RESOLVED = "lossless_retry_resolved"

        /**
         * Output-data key: how many WAITING_FOR_LOSSLESS rows the sweep took when it started. A
         * sync's rows that shouldn't download now aren't among them (see [doWork]).
         */
        const val KEY_TOTAL = "lossless_retry_total"
    }
}
