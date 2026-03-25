package com.stash.core.auth.spotify

/**
 * Static configuration for the Spotify sp_dc cookie-based authentication flow.
 *
 * Instead of requiring a Spotify Developer account and OAuth client credentials,
 * this approach uses the sp_dc cookie extracted from the user's browser session
 * to obtain web-player access tokens. This is the same mechanism used by open-source
 * tools such as Spotube and SpotDL.
 *
 * As of 2025, Spotify requires a TOTP code to be sent alongside the token request.
 * The TOTP is derived from a fixed cipher using a Spotify-specific key derivation
 * scheme (XOR transform -> hex encode -> Base32 decode -> HMAC-SHA1 TOTP).
 */
object SpotifyAuthConfig {

    /** Endpoint that returns a short-lived web-player access token when given a valid sp_dc cookie. */
    const val TOKEN_ENDPOINT = "https://open.spotify.com/api/token"

    /** Base URL for the Spotify Web API v1. */
    const val WEB_API_BASE = "https://api.spotify.com/v1"

    /** Name of the browser cookie that acts as the long-lived authentication credential. */
    const val SP_DC_COOKIE_NAME = "sp_dc"

    /** TOTP algorithm version expected by the token endpoint. */
    const val TOTP_VERSION = "61"

    /** TOTP time-step interval in seconds (RFC 6238 default). */
    const val TOTP_INTERVAL = 30L

    /** Number of digits in the generated TOTP code. */
    const val TOTP_DIGITS = 6

    /**
     * Cipher bytes for TOTP secret derivation (version 61).
     *
     * These are XOR-transformed with a positional key to produce the raw material
     * that is then hex-encoded and Base32-decoded into the HMAC-SHA1 secret.
     */
    val SECRET_CIPHER = intArrayOf(
        44, 55, 47, 42, 70, 40, 34, 114, 76, 74,
        50, 111, 120, 97, 75, 76, 94, 102, 43, 69,
        49, 120, 118, 80, 64, 78,
    )
}
