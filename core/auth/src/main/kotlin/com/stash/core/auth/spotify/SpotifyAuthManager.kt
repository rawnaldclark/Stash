package com.stash.core.auth.spotify

import com.stash.core.auth.model.ServiceToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
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
 * As of 2025, Spotify's token endpoint requires a TOTP code derived from a fixed
 * cipher. This manager handles TOTP generation automatically via [SpotifyTotp].
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
     * This method fetches Spotify's server time, generates the required TOTP code,
     * and exchanges the cookie for a short-lived access token.
     *
     * @param spDcCookie The raw sp_dc cookie value from the user's browser.
     * @return A [ServiceToken] on success (with the sp_dc stored as the refresh token),
     *         or null if the cookie is invalid, expired, or the request fails.
     */
    suspend fun getAccessToken(spDcCookie: String): ServiceToken? {
        return withContext(Dispatchers.IO) {
            try {
                // Step 1: Fetch Spotify's server time to avoid clock-skew TOTP failures
                val serverTime = getServerTime() ?: (System.currentTimeMillis() / 1000)

                // Step 2: Generate the TOTP code required by the token endpoint
                val totp = SpotifyTotp.generate(serverTime)

                // Step 3: Build the token request with TOTP query parameters
                val url = "${SpotifyAuthConfig.TOKEN_ENDPOINT}" +
                    "?reason=transport" +
                    "&productType=web-player" +
                    "&totp=$totp" +
                    "&totpServer=$totp" +
                    "&totpVer=${SpotifyAuthConfig.TOTP_VERSION}"

                val request = Request.Builder()
                    .url(url)
                    .header("Cookie", "${SpotifyAuthConfig.SP_DC_COOKIE_NAME}=$spDcCookie")
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36" +
                            " (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36",
                    )
                    .header("Accept", "application/json")
                    .header("App-Platform", "WebPlayer")
                    .header("Referer", "https://open.spotify.com/")
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
                    // Store the Spotify username in the scope field (unused for sp_dc auth).
                    // This allows the API client to resolve the user ID without an extra
                    // API call, which is critical since api.spotify.com/v1/me is rate-limited.
                    scope = tokenResponse.username,
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

    /**
     * Fetches the Spotify user profile using the given access token.
     *
     * Makes a single call to api.spotify.com/v1/me using web player headers.
     * This should be called immediately after obtaining a fresh token, before
     * any rate limits have been triggered.
     *
     * @param accessToken A valid Bearer access token.
     * @return The Spotify user ID, or null if the request fails.
     */
    suspend fun fetchUserId(accessToken: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("https://api.spotify.com/v1/me")
                    .get()
                    .header("Authorization", "Bearer $accessToken")
                    .header("Accept", "application/json")
                    .header("App-Platform", "WebPlayer")
                    .header("Origin", "https://open.spotify.com")
                    .header("Referer", "https://open.spotify.com/")
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36" +
                            " (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36",
                    )
                    .header("spotify-app-version", "1.2.52.442.g0e1a5ca5")
                    .build()

                val response = okHttpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    android.util.Log.w("SpotifyAuthManager", "Profile fetch failed: HTTP ${response.code}")
                    response.body?.close()
                    return@withContext null
                }

                val body = response.body?.string() ?: return@withContext null
                // Parse just the "id" field from the response.
                val jsonObj = Json { ignoreUnknownKeys = true }
                    .parseToJsonElement(body)
                    .jsonObject
                jsonObj["id"]?.jsonPrimitive?.content
            } catch (e: Exception) {
                android.util.Log.w("SpotifyAuthManager", "Profile fetch exception: ${e.message}")
                null
            }
        }
    }

    /**
     * Fetch Spotify's server time from the HTTP Date header.
     *
     * Using the server's time rather than the device clock prevents TOTP failures
     * caused by clock skew on the user's device.
     *
     * @return The server time in epoch seconds, or null if the request fails.
     */
    private fun getServerTime(): Long? {
        return try {
            val request = Request.Builder()
                .url("https://open.spotify.com/")
                .head()
                .header("User-Agent", "Mozilla/5.0")
                .build()
            val response = okHttpClient.newCall(request).execute()
            val dateHeader = response.header("Date") ?: return null
            response.close()

            val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("GMT")
            val date = sdf.parse(dateHeader) ?: return null
            date.time / 1000
        } catch (_: Exception) {
            null // Fall back to local time in the caller
        }
    }
}

/**
 * JSON shape returned by Spotify's `/api/token` endpoint.
 *
 * When a valid sp_dc cookie and TOTP code are provided, the endpoint returns a
 * non-anonymous access token with its expiration timestamp in milliseconds.
 */
@Serializable
private data class SpDcTokenResponse(
    @SerialName("accessToken") val accessToken: String = "",
    @SerialName("accessTokenExpirationTimestampMs") val accessTokenExpirationTimestampMs: Long = 0,
    @SerialName("isAnonymous") val isAnonymous: Boolean = true,
    @SerialName("clientId") val clientId: String = "",
    @SerialName("username") val username: String = "",
)
