package com.stash.core.auth.spotify

import android.util.Base64
import android.util.Log
import com.stash.core.auth.model.ServiceToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
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
 * Also handles acquiring client tokens from Spotify's clienttoken endpoint, which are
 * required by the GraphQL Partner API (api-partner.spotify.com).
 *
 * As of 2025, Spotify's token endpoint requires a TOTP code derived from a fixed
 * cipher. This manager handles TOTP generation automatically via [SpotifyTotp].
 *
 * Typical usage:
 * 1. The user extracts their sp_dc cookie from their browser's DevTools.
 * 2. Call [getAccessToken] to validate the cookie and obtain an access token.
 * 3. When the access token expires, call [refreshAccessToken] with the stored sp_dc
 *    cookie to get a new one.
 * 4. Call [getClientToken] with the clientId from the token response to obtain a
 *    client token for the GraphQL Partner API.
 */
@Singleton
class SpotifyAuthManager @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val TAG = "StashSync"

        /**
         * Delimiter used to pack both the username and clientId into the
         * [ServiceToken.scope] field. Format: "username|clientId".
         */
        const val SCOPE_DELIMITER = "|"

        /**
         * SpotDL's well-known Spotify OAuth client credentials.
         * Used for the client_credentials flow which grants access to the
         * standard Web API (api.spotify.com/v1) for public data without
         * user login. This avoids the 429 blocks that plague sp_dc tokens.
         */
        private const val SPOTDL_CLIENT_ID = "5f573c9620494bae87890c0f08a60293"
        private const val SPOTDL_CLIENT_SECRET = "212476d9b0f3472eaa762d90b19b0ba8"
    }

    /**
     * Acquires an access token via Spotify's client_credentials OAuth2 flow.
     *
     * This uses SpotDL's well-known client ID and secret to obtain a token that
     * works with the standard Spotify Web API (api.spotify.com/v1) for accessing
     * public playlist and track data. Unlike sp_dc-derived tokens, these tokens
     * are NOT subject to 429 rate-limit blocks on the public API.
     *
     * The returned token is valid for 1 hour and grants access to public endpoints
     * only (no user-specific data like Liked Songs or private playlists).
     *
     * @return The access token string, or null if the request fails.
     */
    suspend fun getClientCredentialsToken(): String? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "getClientCredentialsToken: requesting token via client_credentials flow")

            val credentials = Base64.encodeToString(
                "$SPOTDL_CLIENT_ID:$SPOTDL_CLIENT_SECRET".toByteArray(),
                Base64.NO_WRAP,
            )

            val body = FormBody.Builder()
                .add("grant_type", "client_credentials")
                .build()

            val request = Request.Builder()
                .url("https://accounts.spotify.com/api/token")
                .post(body)
                .header("Authorization", "Basic $credentials")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "no body"
                Log.w(
                    TAG,
                    "getClientCredentialsToken: failed HTTP ${response.code}, body=$errorBody",
                )
                return@withContext null
            }

            val responseBody = response.body?.string() ?: return@withContext null
            Log.d(TAG, "getClientCredentialsToken: response (first 300 chars): ${responseBody.take(300)}")

            val jsonObj = json.parseToJsonElement(responseBody).jsonObject
            val token = jsonObj["access_token"]?.jsonPrimitive?.content
            if (token != null) {
                Log.d(TAG, "getClientCredentialsToken: acquired token (${token.take(20)}...)")
            } else {
                Log.w(TAG, "getClientCredentialsToken: no access_token in response")
            }
            token
        } catch (e: Exception) {
            Log.e(TAG, "getClientCredentialsToken: failed", e)
            null
        }
    }

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
     * The returned [ServiceToken.scope] field contains both the username and clientId
     * packed as "username|clientId" so both can be recovered from storage.
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
                Log.d(TAG, "Server time resolved: $serverTime")

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
                if (!response.isSuccessful) {
                    Log.w(TAG, "Token endpoint returned HTTP ${response.code}")
                    return@withContext null
                }

                val body = response.body?.string() ?: return@withContext null
                val tokenResponse = json.decodeFromString<SpDcTokenResponse>(body)
                Log.d(TAG, "Token response: isAnonymous=${tokenResponse.isAnonymous}, " +
                    "username='${tokenResponse.username}', clientId='${tokenResponse.clientId}'")

                // Anonymous tokens mean the cookie was invalid or expired
                if (tokenResponse.isAnonymous) {
                    Log.w(TAG, "Token is anonymous -- sp_dc cookie is invalid or expired")
                    return@withContext null
                }

                // Resolve the Spotify username using multiple strategies:
                // 1. Decode the JWT access token payload (no network call needed)
                // 2. Fall back to the username field in the token response
                val jwtUsername = extractUsernameFromJwt(tokenResponse.accessToken)
                val resolvedUsername = jwtUsername
                    ?: tokenResponse.username.takeIf { it.isNotEmpty() }
                    ?: ""
                Log.d(TAG, "Resolved username: '$resolvedUsername' " +
                    "(jwt='$jwtUsername', response='${tokenResponse.username}')")

                // Pack both username and clientId into the scope field so we can
                // recover the clientId later for client token acquisition.
                val scopeValue = "$resolvedUsername$SCOPE_DELIMITER${tokenResponse.clientId}"
                Log.d(TAG, "Packed scope: '$scopeValue'")

                ServiceToken(
                    accessToken = tokenResponse.accessToken,
                    // Store sp_dc as the "refresh token" -- it IS the long-lived credential
                    refreshToken = spDcCookie,
                    expiresAtEpoch = tokenResponse.accessTokenExpirationTimestampMs / 1000,
                    // Store username|clientId in scope for later retrieval
                    scope = scopeValue,
                )
            } catch (e: Exception) {
                Log.e(TAG, "getAccessToken failed", e)
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
     * Acquires a client token from Spotify's clienttoken endpoint.
     *
     * The client token is required by the GraphQL Partner API
     * (api-partner.spotify.com) alongside the access token. It identifies the
     * "client application" (we masquerade as the web player).
     *
     * @param clientId The client ID from the token response (stored in ServiceToken.scope).
     * @return The client token string, or null if the request fails.
     */
    suspend fun getClientToken(clientId: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val deviceId = UUID.randomUUID().toString()
                val requestBody = buildString {
                    append("""{"client_data":{""")
                    append(""""client_version":"${SpotifyAuthConfig.CLIENT_VERSION}",""")
                    append(""""client_id":"$clientId",""")
                    append(""""js_sdk_data":{""")
                    append(""""device_brand":"unknown",""")
                    append(""""device_model":"unknown",""")
                    append(""""os":"windows",""")
                    append(""""os_version":"NT 10.0",""")
                    append(""""device_id":"$deviceId",""")
                    append(""""device_type":"computer"}}""")
                    append("}")
                }

                Log.d(TAG, "Client token request body: $requestBody")
                Log.d(TAG, "Requesting client token for clientId='$clientId', deviceId='$deviceId'")

                val request = Request.Builder()
                    .url(SpotifyAuthConfig.CLIENT_TOKEN_ENDPOINT)
                    .post(requestBody.toRequestBody("application/json".toMediaType()))
                    .header("Accept", "application/json")
                    .header("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36" +
                            " (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36")
                    .build()

                val response = okHttpClient.newCall(request).execute()
                val responseCode = response.code
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "no body"
                    Log.w(TAG, "Client token request failed: HTTP $responseCode, body=$errorBody")
                    return@withContext null
                }

                val responseBody = response.body?.string() ?: return@withContext null
                Log.d(TAG, "Client token response (first 500 chars): ${responseBody.take(500)}")

                val jsonObj = json.parseToJsonElement(responseBody).jsonObject
                val responseType = jsonObj["response_type"]?.jsonPrimitive?.content
                Log.d(TAG, "Client token response_type: '$responseType'")

                if (responseType == "RESPONSE_GRANTED_TOKEN_RESPONSE") {
                    val token = jsonObj["granted_token"]
                        ?.jsonObject?.get("token")
                        ?.jsonPrimitive?.content
                    Log.d(TAG, "Client token acquired: ${token?.take(20)}...")
                    token
                } else {
                    Log.w(TAG, "Unexpected client token response_type: '$responseType'")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "getClientToken failed", e)
                null
            }
        }
    }

    /**
     * Extracts the Spotify username from a JWT access token without any network call.
     *
     * Spotify sp_dc-derived access tokens are JWTs. The payload (second segment,
     * base64url-encoded) contains a "sub" or "username" field with the Spotify user ID.
     * This is critical because api.spotify.com/v1/me permanently 429-blocks sp_dc tokens.
     *
     * @param accessToken A JWT access token from the sp_dc token exchange.
     * @return The Spotify username/user ID, or null if extraction fails.
     */
    fun extractUsernameFromJwt(accessToken: String): String? {
        return try {
            val parts = accessToken.split(".")
            if (parts.size < 2) {
                Log.w(TAG, "Access token is not a JWT (${parts.size} parts)")
                return null
            }
            val payload = String(
                Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            )
            Log.d(TAG, "JWT payload (first 500 chars): ${payload.take(500)}")
            val jsonObj = json.parseToJsonElement(payload).jsonObject
            val allKeys = jsonObj.keys.joinToString(", ")
            Log.d(TAG, "JWT payload keys: $allKeys")

            val username = jsonObj["sub"]?.jsonPrimitive?.content
                ?: jsonObj["username"]?.jsonPrimitive?.content
            Log.d(TAG, "JWT extracted username: '$username'")
            username?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "JWT username extraction failed", e)
            null
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
 * The clientId is needed for acquiring client tokens for the GraphQL Partner API.
 */
@Serializable
internal data class SpDcTokenResponse(
    @SerialName("accessToken") val accessToken: String = "",
    @SerialName("accessTokenExpirationTimestampMs") val accessTokenExpirationTimestampMs: Long = 0,
    @SerialName("isAnonymous") val isAnonymous: Boolean = true,
    @SerialName("clientId") val clientId: String = "",
    @SerialName("username") val username: String = "",
)
