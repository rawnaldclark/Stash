package com.stash.core.data.sync

import android.util.Log
import androidx.room.withTransaction
import com.stash.core.auth.TokenManager
import com.stash.core.data.db.StashDatabase
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Removes an extra Spotify account (plan 2026-11-04 §3.5): the data side of the account card's
 * "Remove <name>", for Settings to call after its confirm.
 *
 *  - Its rows are hidden (`is_active = 0`): only rows the sync filed for it (its tag, source
 *    SPOTIFY). A playlist the main account owns stays, even one this account also followed.
 *  - Its `spotify_accounts` row is deleted and its sign-in forgotten on this phone.
 *  - Nothing else: no download is cancelled (Disconnect cancels by song source, which would take
 *    the main account's too), no song is deleted. A downloaded song stays on the phone and in
 *    Songs: the orphan sweep keeps songs in any playlist row, hidden ones included.
 *  - Its rows keep their tag, so adding the same account again re-adopts them, Download switches
 *    included: each comes back when its listing names it again.
 *
 * A sync running meanwhile can't bring the rows back: the diff drops a snapshot whose account is
 * gone by then.
 */
@Singleton
class SpotifyExtraAccountRemover @Inject constructor(
    private val database: StashDatabase,
    private val tokenManager: TokenManager,
) {

    /**
     * Removes the extra account [rowId]. Returns how many of its rows were hidden, or null when
     * there is no such account (already removed).
     */
    suspend fun remove(rowId: Long): Int? {
        val accounts = database.spotifyAccountDao()
        val hidden = database.withTransaction {
            val row = accounts.getById(rowId) ?: return@withTransaction null
            val hidden = database.playlistDao().hideSpotifyAccountRows(row.userId)
            accounts.delete(rowId)
            hidden
        } ?: return null
        // After the row is gone, so a sync can never pick the account up between the two. Its id
        // is never given out again (AUTOINCREMENT), so a sign-in left behind by a failure here
        // can't act for anyone; it is still dropped on the next keyset reset.
        try {
            tokenManager.clearSpotifyExtra(rowId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "removed extra Spotify account #$rowId, but couldn't forget its sign-in", e)
        }
        Log.i(TAG, "removed extra Spotify account #$rowId: $hidden row(s) hidden")
        return hidden
    }

    private companion object {
        const val TAG = "SpotifyAccounts"
    }
}
