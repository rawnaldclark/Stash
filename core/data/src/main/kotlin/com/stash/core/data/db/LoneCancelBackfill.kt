package com.stash.core.data.db

import android.content.Context
import android.content.SharedPreferences
import com.stash.core.data.db.dao.DownloadQueueDao

/**
 * One-time pass (2026-11-09): a song cancelled on its own before [com.stash.core.data.db.dao.KEPT_OUT_MARK]
 * existed gets the mark, so it stays out as it always did.
 *
 * Until this build every cancel looked alike: SKIPPED, no error message. Clear queue's cancel now
 * only stops that attempt (the song downloads again with its playlist), while the X on one song in
 * Downloads keeps it out. Without this pass every song cancelled by the X since #433 (2026-08-18)
 * would download again at the next sync. Two kinds of cancel were ever written, both through
 * `markCancelledIfActive`, and the data tells them apart:
 *
 * - Before [CLEAR_QUEUE_SINCE_MS] no build had Clear queue, so every cancel is the X.
 * - After it, Clear queue cancels every waiting and running row in one loop, one write each,
 *   milliseconds apart, while the X cancels one row per tap. On the Pixel 5 (2026-11-09), three
 *   Clear queues of 2, 45 and 52 rows had 4 to 114 ms between rows, and separate cancels were 4 s
 *   or more apart. A cancel with another one within [BURST_WINDOW_MS] is a row of a Clear queue
 *   and stays as it is; any other is lone and gets the mark. Clear queue asks to confirm first, so
 *   an X can't land within a second of one.
 *
 * What the data can't tell: a Clear queue that caught a single song reads as lone and stays out
 * (as every cancel did before this build; a tap on the song brings it back), and two X taps less
 * than a second apart read as a Clear queue.
 *
 * Runs once per install: a done flag in [prefs]. Only cancels from before the first run are looked
 * at ([CUTOFF_KEY], kept across re-arms), so a Clear queue of one song made by this build is never
 * marked. The update is idempotent, so a run cut short before the flag is written does no harm. A
 * restored backup (a swap) brings its old cancels back with the flag still set, so
 * [DatabaseBackupManager] calls [rearm].
 */
class LoneCancelBackfill(
    private val downloadQueueDao: DownloadQueueDao,
    private val prefs: SharedPreferences,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** Marks the lone legacy cancels, unless this has run (then null). Returns how many it marked. */
    suspend fun runOnce(): Int? {
        if (prefs.getBoolean(DONE_KEY, false)) return null
        val cutoff = prefs.getLong(CUTOFF_KEY, 0L).takeIf { it > 0L }
            ?: now().also { prefs.edit().putLong(CUTOFF_KEY, it).commit() }
        val marked = downloadQueueDao.markLoneCancelsKeptOut(
            before = cutoff,
            clearQueueSince = CLEAR_QUEUE_SINCE_MS,
            burstWindowMs = BURST_WINDOW_MS,
        )
        prefs.edit().putBoolean(DONE_KEY, true).apply()
        return marked
    }

    /** Asks for another run (a restored backup); the first run's cutoff stays. Written at once: a restore restarts the app. */
    fun rearm() {
        prefs.edit().remove(DONE_KEY).commit()
    }

    companion object {
        /**
         * The first commit with Clear queue (8008e294, 2026-10-29T15:03:28Z, merged as #732 on
         * 2026-10-30 and released in v0.9.122): no build could write one before it.
         */
        const val CLEAR_QUEUE_SINCE_MS = 1_793_286_208_000L

        /** Two cancels this close are one Clear queue (see the class doc for the measurements). */
        const val BURST_WINDOW_MS = 1_000L

        /** The app's one-time-migration flags file, where the flags live. */
        const val PREFS_NAME = "stash_migrations"

        /** The done flag, in [PREFS_NAME]. */
        const val DONE_KEY = "lone_cancels_kept_out"

        /** When the pass first ran: only cancels finished before it are looked at, on every run. */
        const val CUTOFF_KEY = "lone_cancels_kept_out_cutoff"

        /** Over the app's flags file, for code that has a Context but no injection. */
        fun forContext(context: Context, downloadQueueDao: DownloadQueueDao): LoneCancelBackfill = LoneCancelBackfill(
            downloadQueueDao,
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        )
    }
}
