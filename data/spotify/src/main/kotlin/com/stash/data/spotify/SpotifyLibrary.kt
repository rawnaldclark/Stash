package com.stash.data.spotify

import com.stash.core.model.SyncResult
import com.stash.data.spotify.model.SpotifyPlaylistItem

/**
 * The signed-in Spotify account's library, read and written on its behalf: playlists, Liked
 * Songs, saved albums, the generated mixes, search, like and unlike, and an artist's photos. The
 * Spotify library half of the add-ons plan (2026-11-08 §4.3); the sign-ins behind it are
 * [com.stash.core.auth.spotify.SpotifyAccounts].
 *
 * Callers code against this, never against the class behind it. Today [SpotifyApiClient]
 * implements it, unchanged. In phase 3 an adapter to the Spotify phone add-on implements it
 * instead, and the sync, the like outbox and the artist page don't change.
 *
 * **Acting account.** Every call acts as the account named by a
 * [com.stash.core.auth.spotify.SpotifyAccount] in the caller's coroutine context (an extra
 * account's pass in the sync), and as the main account when there is none. No account parameter
 * is threaded through: an implementation must read the acting account from the context.
 *
 * Each member's contract (what an empty, partial or failed read looks like) is the one documented
 * on [SpotifyApiClient]'s implementation, which the callers rely on.
 */
interface SpotifyLibrary {

    /**
     * How many of Spotify's answers to the signed-in account's own requests have been HTTP 429
     * since the app started, whichever account was acting. Only ever grows: a caller compares two
     * readings, as the sync does around the main account's pass.
     */
    val rateLimitedAnswers: Int

    /** One page of the library root, or of the folder [folderUri]: playlists and folder URIs. */
    suspend fun getUserPlaylists(
        limit: Int = DEFAULT_LIMIT,
        offset: Int = 0,
        folderUri: String? = null,
    ): SpotifyLibraryPage

    /**
     * One page of the saved albums in [order] (one of the response's sort orders; the sync asks
     * for [SAVED_ALBUMS_ORDER]). [SpotifySavedAlbumsPage.EMPTY], by identity, when the read failed.
     */
    suspend fun getSavedAlbumsPage(
        limit: Int = DEFAULT_LIMIT,
        offset: Int = 0,
        order: String? = SAVED_ALBUMS_ORDER,
    ): SpotifySavedAlbumsPage

    /** Every song of album [albumId] in the album's order; null when no page could be read. */
    suspend fun getAlbumTracks(albumId: String): SpotifyAlbumTracklist?

    /** A playlist's songs; [SpotifyPlaylistTracks.partial] when the walk ended early. */
    suspend fun getPlaylistTracks(playlistId: String): SyncResult<SpotifyPlaylistTracks>

    /** One page of Liked Songs; paginate on [SpotifyLikedSongsPage.rawItemCount]. */
    suspend fun getLikedSongs(
        limit: Int = DEFAULT_LIMIT,
        offset: Int = 0,
    ): SyncResult<SpotifyLikedSongsPage>

    /** The home feed's Spotify-made mixes (Daily Mixes, Discover Weekly, Release Radar and the like). */
    suspend fun getDailyMixes(): SyncResult<List<SpotifyPlaylistItem>>

    /** The account's yearly and all-time "Your Top Songs" playlists, Spotify's own ids only. */
    suspend fun getYearlyMixes(): SyncResult<List<SpotifyPlaylistItem>>

    /** Track search through the web player's own query; an empty list on any failure (never throws for a 429). */
    suspend fun searchTracksGraphQL(query: String, limit: Int = 10): List<SpotifyTrackCandidate>

    /** Saves full `spotify:track:` [uris] to Liked Songs (the heart). Idempotent. */
    suspend fun addToLibrary(uris: List<String>): SpotifyLibraryWriteResult

    /** Removes full `spotify:track:` [uris] from Liked Songs (un-heart). */
    suspend fun removeFromLibrary(uris: List<String>): SpotifyLibraryWriteResult

    /** An artist's photos and monthly listeners, by exact [name] match; null when not signed in or none. */
    suspend fun getArtistVisuals(name: String): SpotifyArtistVisuals?

    companion object {
        /** Page size when a caller doesn't name one: the web player's own. */
        const val DEFAULT_LIMIT = 50

        /**
         * The order the sync lists saved albums in: newest save first, an order that holds while the
         * listener plays (one of the response's `availableSortOrders`, captured 2026-11-02).
         */
        const val SAVED_ALBUMS_ORDER = "Recently Added"
    }
}
