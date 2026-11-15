package com.stash.core.data.sync

import android.util.Log
import androidx.room.withTransaction
import com.stash.core.auth.spotify.SpotifyAccounts
import com.stash.core.data.db.StashDatabase
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/** What [SpotifyExtraAccountRemover.remove] did. */
sealed interface SpotifyAccountRemoval {
    /** Removed: its sign-in forgotten, its account row gone, [hiddenRows] of its rows hidden. */
    data class Removed(val hiddenRows: Int) : SpotifyAccountRemoval

    /** There is no such account (already removed): nothing was done. */
    data object NotFound : SpotifyAccountRemoval

    /**
     * Its sign-in couldn't be forgotten on this phone: nothing was changed (the account, its rows
     * and its sign-in are as they were), so Settings can say so and offer to try again.
     */
    data object SignInNotForgotten : SpotifyAccountRemoval
}

/**
 * Removes an extra Spotify account (plan 2026-11-04 §3.5): the data side of the account card's
 * "Remove <name>", for Settings to call after its confirm.
 *
 *  - Its sign-in is forgotten on this phone FIRST. Should that fail, nothing else is done: a
 *    sign-in left behind with its account row gone could act again for a row a restored backup
 *    brings back under the same id, with no sign-in from the listener.
 *  - Then, in one transaction: its rows are hidden (`is_active = 0`), only rows the sync filed for
 *    it (its tag, source SPOTIFY), and no Undo shows them again; its `spotify_accounts` row is
 *    deleted. A playlist the main account owns stays, even one this account also followed.
 *  - Nothing else: no download is cancelled (Disconnect cancels by song source, which would take
 *    the main account's too), no song is deleted. A downloaded song stays on the phone and in
 *    Songs: the orphan sweep keeps songs in any playlist row, hidden ones included.
 *  - Its rows keep their tag, so adding the same account again re-adopts them, Download switches
 *    included: each comes back when its listing names it again.
 *
 * A sync running meanwhile can't bring the rows back: from the moment the sign-in is gone its pass
 * reads nothing (and is noted signed out), and the diff checks the account in the transaction of
 * each write, so a snapshot of a removed account is dropped.
 */
@Singleton
class SpotifyExtraAccountRemover @Inject constructor(
    private val database: StashDatabase,
    private val spotifyAccounts: SpotifyAccounts,
) {

    /**
     * Removes the extra account [rowId].
     *
     * @throws Exception only when the database write fails after the sign-in was forgotten: the
     *   account then stays, signed out ("Sign in again"), and removing it again finishes the job.
     */
    suspend fun remove(rowId: Long): SpotifyAccountRemoval {
        val accounts = database.spotifyAccountDao()
        if (accounts.getById(rowId) == null) return SpotifyAccountRemoval.NotFound
        try {
            spotifyAccounts.clearSpotifyExtra(rowId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "extra Spotify account #$rowId: couldn't forget its sign-in, nothing removed", e)
            return SpotifyAccountRemoval.SignInNotForgotten
        }
        val hidden = database.withTransaction {
            // Read again: removed meanwhile (a second tap) means the other removal did it all.
            val row = accounts.getById(rowId) ?: return@withTransaction null
            val tag = SpotifyPlaylistIds.accountTag(row.userId)
            val hidden = database.playlistDao().hideSpotifyAccountRows(tag)
            // No Undo of an earlier sync brings them back without an account to sync them.
            database.syncUndoDao().keepSpotifyAccountRowsHidden(tag)
            accounts.delete(rowId)
            hidden
        } ?: return SpotifyAccountRemoval.NotFound
        Log.i(TAG, "removed extra Spotify account #$rowId: $hidden row(s) hidden")
        return SpotifyAccountRemoval.Removed(hidden)
    }

    private companion object {
        const val TAG = "SpotifyAccounts"
    }
}
