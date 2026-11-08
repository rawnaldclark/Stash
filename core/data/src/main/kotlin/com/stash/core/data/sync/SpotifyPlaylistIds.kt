package com.stash.core.data.sync

import com.stash.core.auth.spotify.SpotifyProfile

/**
 * Spotify playlist ids that depend on the account (plan 2026-11-04 §2.3). Playlists and saved
 * albums keep their plain Spotify keys whatever the account (one row when two accounts share
 * one); only Liked Songs, which every account has, needs one id per account.
 */
object SpotifyPlaylistIds {

    /**
     * The MAIN account's Liked Songs row. Fixed since the first sync, and the only Liked Songs
     * like mirroring reads ("is it liked there already?", the complete-fetch note): hearts go to
     * the main account, so an extra account's likes must never count as the main's.
     */
    const val MAIN_LIKED_SONGS = "spotify_liked_songs"

    /**
     * An extra account's Liked Songs: its own row beside the main's. [userId] must already be the
     * normalised key (`spotify_accounts.user_id`, [SpotifyProfile.accountKeyOf]): an id in another
     * shape would make a second Liked Songs row for the same account, so it fails loudly instead.
     */
    fun extraLikedSongs(userId: String): String = "$MAIN_LIKED_SONGS:${requireAccountKey(userId)}"

    /**
     * The `spotify_account` tag of an extra account's rows and snapshots
     * (`playlists.spotify_account`, `remote_playlist_snapshots.spotify_account`): its normalised
     * user id. The main account has no tag (NULL). Build every tag through here, so one in another
     * shape (which SQLite would compare as a different account) fails loudly.
     */
    fun accountTag(userId: String): String = requireAccountKey(userId)

    private fun requireAccountKey(userId: String): String {
        require(SpotifyProfile.isAccountKey(userId)) {
            "a Spotify account id must be normalised (SpotifyProfile.accountKeyOf) before it keys a row"
        }
        return userId
    }
}
