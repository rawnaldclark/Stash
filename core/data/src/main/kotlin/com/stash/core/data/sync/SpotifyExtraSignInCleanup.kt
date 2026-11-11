package com.stash.core.data.sync

import android.util.Log
import com.stash.core.auth.spotify.SpotifyAccounts
import com.stash.core.auth.store.EncryptedTokenStore
import com.stash.core.data.db.dao.SpotifyAccountDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Forgets the extra Spotify accounts' sign-ins that have no account row any more (plan 2026-11-04
 * §2.2): a sign-in is stored under its `spotify_accounts` row id outside the database, so it can
 * outlive its row. A full backup restore replaces the database (and restarts the app) without the
 * row, and a removal cut short between its two steps leaves the sign-in behind. Such a sign-in
 * is never used, but it is a live Spotify session on disk that nobody can see or remove, and a
 * later row given the same id would find it.
 *
 * Runs once at app start, which also covers a restore: a full restore restarts the app, and a
 * merge restore only adds rows, so it can't orphan anything.
 *
 * Only sign-ins with NO row are cleared. One stored under a row that is now another account (a
 * restored backup can give a row id to someone else) is left: it is never used (the token manager
 * treats it as no sign-in), signing that row in again replaces it, and removing the row clears it.
 * Telling it apart would mean decrypting it, and a decrypt that fails for a moment (a busy
 * Keystore) must never cost a working sign-in.
 */
@Singleton
class SpotifyExtraSignInCleanup @Inject constructor(
    private val tokenStore: EncryptedTokenStore,
    private val spotifyAccounts: SpotifyAccounts,
    private val spotifyAccountDao: SpotifyAccountDao,
) {

    /**
     * Clears every stored extra sign-in whose row id has no `spotify_accounts` row.
     *
     * Race-safe with adding and removing an account by its order alone: the stored sign-ins are
     * read BEFORE the rows. Adding writes the row before its sign-in, so a sign-in read here
     * always finds its row; removing clears the sign-in before the row, so a row gone never
     * leaves a sign-in this misses for long. A database that can't be read throws before
     * anything is cleared.
     *
     * @return The row ids whose sign-ins were cleared (none, normally).
     */
    suspend fun clearOrphans(): Set<Long> {
        val stored = tokenStore.spotifyExtraRowIds()
        if (stored.isEmpty()) return emptySet()
        val rows = spotifyAccountDao.getAll().mapTo(HashSet()) { it.id }
        val orphans = stored - rows
        // Logs the count only: a row id names nobody, but there is nothing more to say.
        orphans.forEach { spotifyAccounts.clearSpotifyExtra(it) }
        if (orphans.isNotEmpty()) Log.i(TAG, "forgot ${orphans.size} extra Spotify sign-in(s) with no account row")
        return orphans
    }

    private companion object {
        const val TAG = "SpotifyAccounts"
    }
}
