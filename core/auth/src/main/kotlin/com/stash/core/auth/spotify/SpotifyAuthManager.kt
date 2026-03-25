package com.stash.core.auth.spotify

import com.stash.core.auth.model.ServiceToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Spotify authentication using the sp_dc cookie approach.
 *
 * Instead of OAuth PKCE (which requires a Spotify Developer account with Premium),
 * this manager exchanges the user's sp_dc browser cookie for a short-lived web-player
 * access token. The sp_dc cookie itself acts as the long-lived credential (stored in
 * the [ServiceToken.refreshToken] field) and can be reused to obtain fresh access
 * tokens whenever they expire.
 *
 * Typical usage:
 * 1. The user extracts their sp_dc cookie from their browser's DevTools.
 * 2. Call [getAccessToken] to validate the cookie and obtain an access token.
 * 3. When the access token expires, call [refreshAccessToken] with the stored sp_dc
 *    cookie to get a new one.
 */
@Singleton
class SpotifyAuthManager @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Exchange an sp_dc cookie for a web-player access token.
     *
     * The sp_dc cookie is obtained by the user from their browser DevTools:
     * 1. Log into open.spotify.com
     * 2. Open DevTools (F12) > Application > Cookies
     * 3. Copy the value of the "sp_dc" cookie
     *
     * @param spDcCookie The raw sp_dc cookie value from the user's browser.
     * @return A [ServiceToken] on success (with the sp_dc stored as the refresh token),
     *         or null if the cookie is invalid, expired, or the request fails.
     */
    suspend fun getAccessToken(spDcCookie: String): ServiceToken? {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(SpotifyAuthConfig.ACCESS_TOKEN_ENDPOINT)
                    .header("Cookie", "${SpotifyAuthConfig.SP_DC_COOKIE_NAME}=$spDcCookie")
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    )
                    .get()
                    .build()

                val response = okHttpClient.newCall(request).execute()
                if (!response.isSuccessful) return@withContext null

                val body = response.body?.string() ?: return@withContext null
                val tokenResponse = json.decodeFromString<SpDcTokenResponse>(body)

                // Anonymous tokens mean the cookie was invalid or expired
                if (tokenResponse.isAnonymous) return@withContext null

                ServiceToken(
                    accessToken = tokenResponse.accessToken,
                    // Store sp_dc as the "refresh token" -- it IS the long-lived credential
                    refreshToken = spDcCookie,
                    expiresAtEpoch = tokenResponse.accessTokenExpirationTimestampMs / 1000,
                    scope = "",
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Refresh the access token using the stored sp_dc cookie.
     *
     * Functionally identical to [getAccessToken] -- the sp_dc cookie IS the refresh
     * mechanism. A new short-lived access token is fetched from the same endpoint.
     *
     * @param spDcCookie The stored sp_dc cookie value.
     * @return A fresh [ServiceToken], or null on failure.
     */
    suspend fun refreshAccessToken(spDcCookie: String): ServiceToken? {
        return getAccessToken(spDcCookie)
    }
}

/**
 * JSON shape returned by Spotify's `/get_access_token` endpoint.
 *
 * When a valid sp_dc cookie is provided, the endpoint returns a non-anonymous
 * access token with its expiration timestamp in milliseconds.
 */
@Serializable
private data class SpDcTokenResponse(
    @SerialName("accessToken") val accessToken: String = "",
    @SerialName("accessTokenExpirationTimestampMs") val accessTokenExpirationTimestampMs: Long = 0,
    @SerialName("isAnonymous") val isAnonymous: Boolean = true,
    @SerialName("clientId") val clientId: String = "",
)
