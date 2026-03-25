package com.stash.core.auth.spotify

/**
 * Static configuration for the Spotify OAuth 2.0 PKCE authorization flow.
 *
 * The [CLIENT_ID] must be set to a valid Spotify Developer application client ID
 * before authentication will work. Register an app at
 * https://developer.spotify.com/dashboard and add [REDIRECT_URI] as an allowed
 * redirect URI.
 */
object SpotifyAuthConfig {

    /** Spotify Developer application client ID. Must be filled in by the developer. */
    const val CLIENT_ID = "" // TODO: set your Spotify app client ID

    /** Deep-link URI registered in AndroidManifest for the OAuth callback. */
    const val REDIRECT_URI = "com.stash.app://spotify-callback"

    /** Spotify Accounts authorization endpoint. */
    const val AUTH_ENDPOINT = "https://accounts.spotify.com/authorize"

    /** Spotify Accounts token exchange endpoint. */
    const val TOKEN_ENDPOINT = "https://accounts.spotify.com/api/token"

    /**
     * OAuth scopes requested during authorization.
     *
     * - `user-read-private`        Read the user's subscription and profile info.
     * - `user-library-read`        Read the user's saved tracks / albums.
     * - `playlist-read-private`    Read private playlists.
     * - `user-read-recently-played` Read recently played tracks.
     */
    val SCOPES: List<String> = listOf(
        "user-read-private",
        "user-library-read",
        "playlist-read-private",
        "user-read-recently-played",
    )
}
