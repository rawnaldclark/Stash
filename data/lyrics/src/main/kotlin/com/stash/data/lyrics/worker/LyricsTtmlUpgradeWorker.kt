package com.stash.data.lyrics.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.stash.data.lyrics.LyricsRepository
import com.stash.data.lyrics.ManualFetchResult
import com.stash.data.lyrics.TtmlUpgradeResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Lyrics catch-up worker, two user-started entry points sharing one unique-work name. Nothing
 * enqueues it automatically any more (owner decision 2026-10-21: no automatic library walks for
 * lyrics); a run with neither flag is one the retired automatic pass queued before an update, and
 * it ends at once without asking anything.
 *  - recheck ([KEY_RECHECK], Settings → Playback → Lyrics → "Re-check library"): upgrades stored
 *    lyrics to word-synced TTML, asking every word-synced source (Apple, lrc.red, Binimum; see
 *    [LyricsRepository.recheckTrack]), newest first, from a resumable cursor. A quiet background
 *    job: when the OS stops it (the ~10 min job cap), WorkManager reruns it and it carries on
 *    below the cursor. Reaching the end stamps "last checked" ([LyricsRepository.finishRecheck]).
 *  - manual ([KEY_MANUAL], Library Health button): the Apple-only upgrade PLUS a fetch for
 *    downloaded tracks that have no lyrics yet. Runs as a FOREGROUND service with a cancellable
 *    progress notification (no ~10 min background-job cap), and posts a summary notification.
 *
 * Only ever adds: a hit replaces the row + sidecars, a miss/failure leaves everything as it was.
 * Idempotent and resumable: each run recomputes what's pending. Paced at ~1 track / 3s (the iTunes
 * Search API is ~20 req/min and the lyrics API is a free community service). A single per-track
 * failure is counted and the run moves on ([LyricsRepository] leaves the track pending), but the
 * run STOPS (success, [OUT_BAILED] = true) on an HTTP 429 from the Apple path or after
 * [MAX_CONSECUTIVE_FAILURES] upgrade failures in a row — hammering a rate-limiting or unreachable
 * free service for the rest of the library only makes things worse. What's left stays pending.
 * A bail stops only the upgrade loop: the manual fetch for tracks with no lyrics still runs,
 * and the Apple source's own skip window (opened by a 429 or repeated timeouts) keeps Apple out of
 * it, so LRCLIB/KuGou can still fill those tracks. The re-check counts lrc.red/Binimum's shared
 * skip window (PAUSED) and their 429s (RATE_LIMITED) the same way, and a bailed sweep keeps its
 * cursor, so the next tap carries on instead of starting over.
 */
@HiltWorker
class LyricsTtmlUpgradeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val lyricsRepository: LyricsRepository,
) : CoroutineWorker(context, params) {

    private var foregroundOk = true

    /** Required for expedited work on API < 31; also what [promoteToForeground] posts. */
    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0)

    override suspend fun doWork(): Result {
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val recheck = !manual && inputData.getBoolean(KEY_RECHECK, false)
        if (!manual && !recheck) {
            Log.i(TAG, "Skipping a lyrics upgrade queued by the retired automatic pass")
            return Result.success()
        }
        // The re-check resumes a sweep, so its count starts at what earlier runs already covered.
        val plan = if (recheck) lyricsRepository.recheckPlan() else null
        val toUpgrade = plan?.remaining ?: lyricsRepository.trackIdsPendingTtml()
        val toFetch = if (manual) lyricsRepository.trackIdsMissingLyrics() else emptyList()
        val total = if (plan != null) plan.total else toUpgrade.size + toFetch.size

        if (manual) promoteToForeground(0, total)
        if (recheck) Log.i(TAG, "Library re-check: ${toUpgrade.size} of $total track(s) left")

        var done = if (plan != null) plan.total - plan.remaining.size else 0
        var upgraded = 0
        var fetched = 0
        var notFound = 0
        var failed = 0

        var bailed = false

        fun summary() = workDataOf(
            OUT_TOTAL to total,
            OUT_UPGRADED to upgraded,
            OUT_FETCHED to fetched,
            OUT_NOT_FOUND to notFound,
            OUT_FAILED to failed,
            OUT_BAILED to bailed,
        )

        suspend fun report() {
            setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
            if (manual) promoteToForeground(done, total)
        }

        var consecutiveFailures = 0
        for (trackId in toUpgrade) {
            done++
            val result = if (recheck) lyricsRepository.recheckTrack(trackId) else lyricsRepository.upgradeToTtml(trackId)
            if (recheck) Log.d(TAG, "Re-check trackId=$trackId: $result ($done / $total)")
            // NOT_READY (a timeout) and PAUSED (the skip window) were FAILED before the single-track
            // upgrade told them apart, so the bulk pass still counts them as failures.
            val softFailure = result == TtmlUpgradeResult.FAILED ||
                result == TtmlUpgradeResult.NOT_READY ||
                result == TtmlUpgradeResult.PAUSED
            when (result) {
                TtmlUpgradeResult.UPGRADED -> upgraded++
                TtmlUpgradeResult.NO_TTML -> Unit
                TtmlUpgradeResult.FAILED,
                TtmlUpgradeResult.NOT_READY,
                TtmlUpgradeResult.PAUSED,
                TtmlUpgradeResult.RATE_LIMITED -> failed++
                TtmlUpgradeResult.SKIPPED -> continue          // no network used, no pacing needed
            }
            consecutiveFailures = if (softFailure) consecutiveFailures + 1 else 0
            if (result == TtmlUpgradeResult.RATE_LIMITED || consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                Log.w(TAG, "Stopping the lyrics run early: $result, $consecutiveFailures failure(s) in a row")
                bailed = true
                break
            }
            report()
            delay(REQUEST_SPACING_MS)
        }

        for (trackId in toFetch) {
            done++
            when (lyricsRepository.fetchLyricsNow(trackId)) {
                ManualFetchResult.FETCHED -> fetched++
                ManualFetchResult.NOT_FOUND -> notFound++
                ManualFetchResult.FAILED -> failed++
                ManualFetchResult.SKIPPED -> continue
            }
            report()
            delay(REQUEST_SPACING_MS)
        }

        // A bailed sweep keeps its cursor; one that got to the end starts from the top next time.
        if (recheck && !bailed) lyricsRepository.finishRecheck()
        Log.i(TAG, "Lyrics run finished (${if (recheck) "re-check" else "manual"}): upgraded=$upgraded fetched=$fetched notFound=$notFound failed=$failed of $total bailed=$bailed")
        if (manual) notifyDone(upgraded + fetched, notFound, bailed)
        return Result.success(summary())
    }

    // ── foreground / notifications ──────────────────────────────────────────────────────────────

    private suspend fun promoteToForeground(done: Int, total: Int) {
        if (!foregroundOk) return
        try {
            setForeground(foregroundInfo(done, total))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // e.g. foreground start not allowed from the background: carry on as a plain background job.
            foregroundOk = false
            Log.w(TAG, "Couldn't promote to foreground; continuing in the background", e)
        }
    }

    private fun foregroundInfo(done: Int, total: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        ensureChannel(nm)
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Fetching lyrics")
            .setContentText(if (total > 0) "$done / $total" else "Starting…")
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun notifyDone(updated: Int, notFound: Int, bailed: Boolean) {
        val nm = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(nm)
        val text = buildString {
            append(if (updated == 1) "1 track updated" else "$updated tracks updated")
            if (notFound > 0) append(", $notFound not found")
            if (bailed) append(". Stopped early: the lyrics service isn't responding")
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Lyrics")
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID_DONE, notification) }   // no-op without POST_NOTIFICATIONS
    }

    private fun ensureChannel(nm: NotificationManager?) {
        if (nm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Lyrics", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        private const val TAG = "LyricsTtmlUpgradeWorker"
        const val UNIQUE_WORK_NAME = "lyrics_ttml_upgrade"

        /** Input: true for the Library Health button (also fetches missing lyrics, runs foreground). */
        const val KEY_MANUAL = "manual"

        /** Input: true for Settings' "Re-check library" sweep (every word-synced source, resumable). */
        const val KEY_RECHECK = "recheck"

        /** Request tags, so the two entry points can be told apart (and from the retired auto pass). */
        const val TAG_MANUAL = "lyrics_manual"
        const val TAG_RECHECK = "lyrics_recheck"

        /** Progress keys. */
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"

        /** Output keys (final [Result.success] data). */
        const val OUT_TOTAL = "out_total"
        const val OUT_UPGRADED = "out_upgraded"
        const val OUT_FETCHED = "out_fetched"
        const val OUT_NOT_FOUND = "out_not_found"
        const val OUT_FAILED = "out_failed"
        const val OUT_BAILED = "out_bailed"

        private const val CHANNEL_ID = "lyrics_fetch"
        private const val NOTIFICATION_ID = 9271
        private const val NOTIFICATION_ID_DONE = 9272

        private const val REQUEST_SPACING_MS = 3_000L
        internal const val MAX_CONSECUTIVE_FAILURES = 5
    }
}