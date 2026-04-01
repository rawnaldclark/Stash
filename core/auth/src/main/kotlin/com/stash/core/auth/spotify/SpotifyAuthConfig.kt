package com.stash.core.auth.spotify

/**
 * Static configuration for the Spotify sp_dc cookie-based authentication flow
 * and the GraphQL Partner API.
 *
 * Instead of requiring a Spotify Developer account and OAuth client credentials,
 * this approach uses the sp_dc cookie extracted from the user's browser session
 * to obtain web-player access tokens. This is the same mechanism used by open-source
 * tools such as Spotube and SpotDL.
 *
 * The GraphQL Partner API (api-partner.spotify.com) is the same backend used by the
 * official Spotify web player. It requires both an access token (from sp_dc) and a
 * client token (from the clienttoken endpoint).
 *
 * As of 2025, Spotify requires a TOTP code to be sent alongside the token request.
 * The TOTP is derived from a fixed cipher using a Spotify-specific key derivation
 * scheme (XOR transform -> hex encode -> Base32 decode -> HMAC-SHA1 TOTP).
 */
object SpotifyAuthConfig {

    // -- Token endpoints ------------------------------------------------------

    /** Endpoint that returns a short-lived web-player access token when given a valid sp_dc cookie. */
    const val TOKEN_ENDPOINT = "https://open.spotify.com/api/token"

    /** Endpoint for obtaining a client token required by the GraphQL Partner API. */
    const val CLIENT_TOKEN_ENDPOINT = "https://clienttoken.spotify.com/v1/clienttoken"

    // -- GraphQL Partner API --------------------------------------------------

    /** Base endpoint for all GraphQL persisted queries (Spotify web player backend). */
    const val GRAPHQL_ENDPOINT = "https://api-partner.spotify.com/pathfinder/v1/query"

    /**
     * Fallback web player client version. Used only if scraping the live version
     * from open.spotify.com fails. Updated manually as a last resort.
     */
    const val CLIENT_VERSION_FALLBACK = "1.2.87.311.g2db0c2c4"

    /**
     * Persisted query hash for the `libraryV3` operation.
     * Returns the user's library including playlists, liked songs, and episodes.
     * Scraped from the Spotify web player JS bundles; stable for weeks at a time.
     */
    const val HASH_LIBRARY_V3 = "973e511ca44261fda7eebac8b653155e7caee3675abb4fb110cc1b8c78b091c3"

    /**
     * Persisted query hash for the `fetchPlaylist` operation.
     * Returns track contents of a specific playlist by URI.
     * Scraped from the Spotify web player JS bundles; stable for weeks at a time.
     */
    const val HASH_FETCH_PLAYLIST = "32b05e92e438438408674f95d0fdad8082865dc32acd55bd97f5113b8579092b"

    /**
     * Persisted query hash for the `fetchLibraryTracks` operation.
     * Returns the user's Liked Songs (collection:tracks). This is different from
     * fetchPlaylist because Liked Songs isn't a real playlist — it's a "collection".
     */
    const val HASH_FETCH_LIBRARY_TRACKS = "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240"

    /**
     * Persisted query hash for the `home` operation.
     * Returns the Spotify home feed with personalized sections including
     * Daily Mixes, Discover Weekly, Release Radar, etc.
     */
    const val HASH_HOME = "23e37f2e58d82d567f27080101d36609009d8c3676457b1086cb0acc55b72a5d"

    // -- Legacy (kept for reference, no longer used by API client) -------------

    /** Base URL for the Spotify Web API v1 (blocked for sp_dc tokens). */
    const val WEB_API_BASE = "https://api.spotify.com/v1"

    /** Name of the browser cookie that acts as the long-lived authentication credential. */
    const val SP_DC_COOKIE_NAME = "sp_dc"

    // -- TOTP configuration ---------------------------------------------------

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
