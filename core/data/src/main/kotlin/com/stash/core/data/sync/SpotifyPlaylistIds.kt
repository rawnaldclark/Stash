package com.stash.core.data.sync

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

    /** An extra account's Liked Songs: its own row beside the main's ([userId] in `SpotifyProfile.accountKey` form). */
    fun extraLikedSongs(userId: String): String {
        require(userId.isNotBlank()) { "an extra account's Liked Songs needs its user id" }
        return "$MAIN_LIKED_SONGS:$userId"
    }
}
