package com.stash.core.data.db

import android.content.SharedPreferences
import com.stash.core.data.db.dao.PlaylistDao

/**
 * One-time cleanup (2026-11-02): mixes that carry a heart nobody gave them.
 *
 * Favorite (#686, v0.9.119) is the Home pin, `pinned_to_home_at`. Before it existed,
 * switching a mix on in the Sync tab (v0.9.106 to v0.9.121) stamped that pin as a side
 * effect, and the resets that later switched mixes off left it. v0.9.119 then showed every
 * one of those stamps as a heart. A mix pinned before v0.9.119 was published can't be a
 * favorite the user chose, so its pin goes. Later pins stay: they may be real favorites.
 *
 * Runs once per install: a done flag in [prefs]. The update is idempotent, so a run cut
 * short before the flag is written does no harm when it runs again.
 */
class MixPinCleanup(
    private val playlistDao: PlaylistDao,
    private val prefs: SharedPreferences,
) {

    /** What one run did, for the log: counts only. */
    data class Result(val cleared: Int, val kept: Int)

    /** Clears the stale mix pins, unless this has run before (then null). */
    suspend fun runOnce(): Result? {
        if (prefs.getBoolean(DONE_KEY, false)) return null
        val cleared = playlistDao.clearMixPinsBefore(FAVORITE_RELEASED_AT_MS)
        val kept = playlistDao.countPinnedMixes()
        prefs.edit().putBoolean(DONE_KEY, true).apply()
        return Result(cleared = cleared, kept = kept)
    }

    companion object {
        /**
         * When v0.9.119, the first release with Favorite, was published on GitHub:
         * 2026-10-23T11:51:53Z. No release install could favorite a mix before it.
         */
        const val FAVORITE_RELEASED_AT_MS = 1_792_756_313_000L

        /** The done flag, in the app's `stash_migrations` preferences. */
        const val DONE_KEY = "pre_favorite_mix_pins_cleared"
    }
}
