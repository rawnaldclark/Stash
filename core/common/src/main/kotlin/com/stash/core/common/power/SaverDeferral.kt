package com.stash.core.common.power

import kotlinx.coroutines.withTimeoutOrNull

/**
 * When a refresh that keeps the app CORRECT (not merely warm) may still run under Battery Saver.
 *
 * Warm-ups are simply skipped while Saver is on. A refresh like the signed lossless config is
 * different: the relay key rotates through it, so a phone that keeps Battery Saver on all the time
 * must still pick a new one up. Such a refresh is deferred under Saver only while its last SUCCESS
 * is recent, and runs once that success is [maxAgeMs] old.
 */
object SaverDeferral {

    /** Why [startWhenDue] started the refresh: for the log, and for tests. */
    enum class Start {
        /** Battery Saver was off. */
        SAVER_OFF_AT_START,

        /** It never succeeded, or its last success is [maxAgeMs] old (or "in the future"). */
        STALE,

        /** Nothing cached to use meanwhile: without a fetch the feature has nothing at all. */
        NO_CACHE,

        /** Battery Saver turned off while it waited. */
        SAVER_TURNED_OFF,

        /** It waited until the last success turned [maxAgeMs] old, Saver still on. */
        MAX_AGE_REACHED,
    }

    /**
     * True when the refresh should run now.
     *
     * @param saverOn Battery Saver is on right now.
     * @param lastRunMs Wall-clock time of the refresh's last success, or null when it never had one.
     * @param nowMs Wall-clock time now.
     * @param maxAgeMs How old the last success may get under Saver before it runs anyway.
     */
    fun due(saverOn: Boolean, lastRunMs: Long?, nowMs: Long, maxAgeMs: Long): Boolean {
        if (!saverOn || lastRunMs == null) return true
        val age = nowMs - lastRunMs
        // A last run "in the future" (the clock was set back) can't be trusted to be recent: run.
        return age < 0 || age >= maxAgeMs
    }

    /**
     * Calls [startRefreshing] (e.g. the lossless config's own fetch-and-repeat loop) as soon as it is
     * due, and returns why:
     * - at once when Battery Saver is off, or the last success ([lastSuccessMs]) is missing or
     *   [maxAgeMs] old ([due]);
     * - otherwise [loadCached] first, and at once if that left nothing to use ([hasCached] false):
     *   a first launch whose fetch failed (a captive portal) must not wait out a day under Saver;
     * - otherwise when Battery Saver turns off, or when the last success turns [maxAgeMs] old,
     *   whichever comes first, so a process that stays alive under Saver still refreshes.
     *
     * Suspends until [startRefreshing] has been called. [nowMs] is wall-clock time.
     */
    suspend fun startWhenDue(
        saver: BatterySaver,
        lastSuccessMs: Long?,
        nowMs: () -> Long,
        maxAgeMs: Long,
        loadCached: suspend () -> Unit,
        hasCached: () -> Boolean,
        startRefreshing: () -> Unit,
    ): Start {
        val saverOn = saver.isOn.value
        val start = when {
            !saverOn -> Start.SAVER_OFF_AT_START
            due(saverOn, lastSuccessMs, nowMs(), maxAgeMs) -> Start.STALE
            else -> {
                loadCached()
                if (!hasCached()) {
                    Start.NO_CACHE
                } else {
                    // due() was false, so lastSuccessMs is known, in the past and under maxAgeMs old.
                    val remaining = (lastSuccessMs!! + maxAgeMs - nowMs()).coerceAtLeast(0L)
                    val turnedOff = withTimeoutOrNull(remaining) { saver.awaitOff() }
                    if (turnedOff != null) Start.SAVER_TURNED_OFF else Start.MAX_AGE_REACHED
                }
            }
        }
        startRefreshing()
        return start
    }
}
