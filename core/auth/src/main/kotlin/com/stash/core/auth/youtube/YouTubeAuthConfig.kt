package com.stash.core.auth.youtube

/**
 * Static configuration for the Google OAuth 2.0 Device Authorization Grant flow
 * used to authenticate YouTube Music on limited-input (TV-type) devices.
 *
 * The [CLIENT_ID] and [CLIENT_SECRET] must be set to valid Google Cloud OAuth
 * credentials of the **TVs and Limited Input devices** type before authentication
 * will work. Create credentials at https://console.cloud.google.com/apis/credentials
 * and enable the YouTube Data API v3.
 */
object YouTubeAuthConfig {

    /** Google Cloud OAuth client ID (TV / limited-input device type). */
    const val CLIENT_ID = "" // TODO: set your Google Cloud OAuth client ID

    /** Client secret paired with [CLIENT_ID]. */
    const val CLIENT_SECRET = "" // TODO: set your Google Cloud OAuth client secret

    /** Endpoint that issues a device code and user code for the device flow. */
    const val DEVICE_CODE_ENDPOINT = "https://oauth2.googleapis.com/device/code"

    /** Endpoint used to exchange a device code for tokens and to refresh tokens. */
    const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"

    /** OAuth scope granting read-only access to the user's YouTube data. */
    const val SCOPE = "https://www.googleapis.com/auth/youtube.readonly"
}
