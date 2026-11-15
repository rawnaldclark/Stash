package com.stash.core.data.social.spotify

import android.util.Log
import com.stash.data.spotify.SpotifyLibrary
import com.stash.data.spotify.SpotifyLibraryWriteResult
import javax.inject.Inject
import javax.inject.Singleton

class SpotifyAuthException : Exception("Spotify access token rejected")
class SpotifyRateLimitException(val retryAfterSeconds: Int?) :
    Exception("Spotify rate-limited; retry after $retryAfterSeconds seconds")
class SpotifyApiException(val code: Int, val body: String?) :
    Exception("Spotify API error $code: $body")

/**
 * Save / remove tracks in the user's Spotify library — the heart button's
 * remote destination for like-mirroring.
 *
 * **v0.9.73:** rewired off the deprecated REST `PUT/DELETE /v1/me/tracks`
 * (removed in Spotify's Feb-2026 Web API change, and hard-429'd for sp_dc
 * web-player tokens — the reason Spotify likes never actually synced) onto
 * the web player's own GraphQL `addToLibrary` / `removeFromLibrary`
 * mutations via [SpotifyLibrary], using the same sp_dc token + client
 * token the app already reads the library with.
 *
 * This stays the seam the [com.stash.core.data.social.LikeDestinationDispatcher]
 * calls: signatures ([saveTracks] / [removeTracks]) and the thrown exception
 * contract ([SpotifyRateLimitException] with Retry-After,
 * [SpotifyAuthException]) are unchanged, so the coordinator's pacing keeps
 * working. Idempotent; full `spotify:track:…` URIs (the GraphQL mutation
 * wants URIs, not the bare ids the old REST endpoint took).
 */
@Singleton
class SpotifyLibraryApiClient @Inject constructor(
    private val spotifyLibrary: SpotifyLibrary,
) {
    suspend fun saveTracks(spotifyUris: List<String>) {
        require(spotifyUris.isNotEmpty()) { "saveTracks: empty list" }
        require(spotifyUris.size <= MAX_URIS) { "Spotify caps at $MAX_URIS URIs per call (got ${spotifyUris.size})" }
        map("saveTracks", spotifyLibrary.addToLibrary(spotifyUris))
    }

    suspend fun removeTracks(spotifyUris: List<String>) {
        require(spotifyUris.isNotEmpty()) { "removeTracks: empty list" }
        require(spotifyUris.size <= MAX_URIS) { "Spotify caps at $MAX_URIS URIs per call (got ${spotifyUris.size})" }
        map("removeTracks", spotifyLibrary.removeFromLibrary(spotifyUris))
    }

    private fun map(verb: String, result: SpotifyLibraryWriteResult) {
        when (result) {
            SpotifyLibraryWriteResult.Success -> Log.d(TAG, "$verb ok")
            is SpotifyLibraryWriteResult.RateLimited ->
                throw SpotifyRateLimitException(result.retryAfterSeconds)
            SpotifyLibraryWriteResult.AuthFailed -> throw SpotifyAuthException()
            is SpotifyLibraryWriteResult.Failed ->
                throw SpotifyApiException(0, result.message)
        }
    }

    companion object {
        private const val TAG = "SpotifyLibraryApiClient"

        /** Batch cap — matches the web player's own library-mutation batching. */
        private const val MAX_URIS = 50
    }
}
