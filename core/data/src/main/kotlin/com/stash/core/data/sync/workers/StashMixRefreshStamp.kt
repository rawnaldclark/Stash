package com.stash.core.data.sync.workers

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.TimeUnit

/**
 * When the last full Stash Mix refresh finished, and whether a cold start should start another one.
 *
 * A full refresh (every active mix, Last.fm personas, a similar-track call per seed artist, then a discovery drain) used to
 * be enqueued on every cold start. Since idle-stop the process restarts several times a day, so the same network work ran
 * over and over, and it was a large share of the Last.fm proxy's traffic. Now a cold start enqueues it only when
 * [coldStartRefreshDue] says so: no full refresh in [COLD_START_MIN_GAP_MS] or yet this UTC day, or a mix that needs
 * building.
 *
 * Only the cold-start call is gated. The daily periodic job, "Refresh this mix", the Stash Mixes switch and the
 * post-download chain run as before. A full run of the worker (not a single-mix or a re-link run) stamps the time
 * ([markFullRefresh]) only when its network part worked, so a periodic run in the night also covers the morning's cold
 * start, but one that ran offline doesn't hold the next online cold start back.
 *
 * Backed by its own SharedPreferences file. [nowMs] is the wall clock; tests pass their own.
 */
class StashMixRefreshStamp internal constructor(
    private val prefs: SharedPreferences,
    private val nowMs: () -> Long,
) {

    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        System::currentTimeMillis,
    )

    /** Wall time (ms) the last full refresh finished; 0 when none is on record. */
    fun lastFullRefreshAt(): Long = prefs.getLong(KEY_LAST_FULL_REFRESH_AT, 0L)

    /** Records a full refresh that has just finished. */
    fun markFullRefresh() {
        prefs.edit().putLong(KEY_LAST_FULL_REFRESH_AT, nowMs()).apply()
    }

    /**
     * Whether this cold start should enqueue a full refresh. True when:
     *  - [mixNeedsBuilding]: an active mix has no songs (a new install, a reseed, a newly activated recipe) or the recipes
     *    were retuned this launch. Those are built now, never a day later.
     *  - no full refresh is on record (first launch after installing or updating to this version);
     *  - the last one finished at least [COLD_START_MIN_GAP_MS] ago;
     *  - it finished on an earlier UTC day: the mixes' order rotates once per UTC day (the worker seeds it with
     *    `now / DAY_MS`), so a new day is due a new order even when yesterday's refresh ran late in the evening;
     *  - the stamp is in the future (the clock was set back): a stamp that can't be trusted must not hold mixes back.
     */
    fun coldStartRefreshDue(mixNeedsBuilding: Boolean): Boolean {
        if (mixNeedsBuilding) return true
        val last = lastFullRefreshAt()
        if (last <= 0L) return true
        val now = nowMs()
        return now < last || now - last >= COLD_START_MIN_GAP_MS || now / DAY_MS != last / DAY_MS
    }

    companion object {
        internal const val PREFS_NAME = "stash_mix_refresh"
        internal const val KEY_LAST_FULL_REFRESH_AT = "last_full_refresh_at"

        /**
         * At most about once a day. 20 h rather than 24 so a daily habit (Stash opened at about the same time each day)
         * still gets that day's refresh when yesterday's ran a little later.
         */
        val COLD_START_MIN_GAP_MS: Long = TimeUnit.HOURS.toMillis(20)

        /** One UTC day, the period the worker's rotation seed (`now / DAY_MS`) changes on. */
        private val DAY_MS: Long = TimeUnit.DAYS.toMillis(1)
    }
}
