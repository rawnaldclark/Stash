package com.stash.core.auth.spotify

/**
 * Static configuration for the Spotify sp_dc cookie-based authentication flow.
 *
 * Instead of requiring a Spotify Developer account and OAuth client credentials,
 * this approach uses the sp_dc cookie extracted from the user's browser session
 * to obtain web-player access tokens. This is the same mechanism used by open-source
 * tools such as Spotube and SpotDL.
 */
object SpotifyAuthConfig {

    /** Endpoint that returns a short-lived web-player access token when given a valid sp_dc cookie. */
    const val ACCESS_TOKEN_ENDPOINT =
        "https://open.spotify.com/get_access_token?reason=transport&productType=web_player"

    /** Base URL for the Spotify Web API v1. */
    const val WEB_API_BASE = "https://api.spotify.com/v1"

    /** Name of the browser cookie that acts as the long-lived authentication credential. */
    const val SP_DC_COOKIE_NAME = "sp_dc"
}
