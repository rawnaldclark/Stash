package com.stash.core.common.power

/**
 * When a refresh that keeps the app CORRECT (not merely warm) may still run under Battery Saver.
 *
 * Warm-ups are simply skipped while Saver is on. A refresh like the signed lossless config is
 * different: the relay key rotates through it, so a phone that keeps Battery Saver on all the time
 * must still pick a new one up. Such a refresh is deferred under Saver only while its last run is
 * recent, and runs once that run is [maxAgeMs] old.
 */
object SaverDeferral {

    /**
     * True when the refresh should run now.
     *
     * @param saverOn Battery Saver is on right now.
     * @param lastRunMs Wall-clock time the refresh last started, or null when it never has.
     * @param nowMs Wall-clock time now.
     * @param maxAgeMs How old the last run may get under Saver before it runs anyway.
     */
    fun due(saverOn: Boolean, lastRunMs: Long?, nowMs: Long, maxAgeMs: Long): Boolean {
        if (!saverOn || lastRunMs == null) return true
        val age = nowMs - lastRunMs
        // A last run "in the future" (the clock was set back) can't be trusted to be recent: run.
        return age < 0 || age >= maxAgeMs
    }
}
