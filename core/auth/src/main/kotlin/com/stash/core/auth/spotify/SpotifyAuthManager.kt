package com.stash.core.auth.spotify

import android.util.Base64
import android.util.Log
import com.stash.core.auth.model.ServiceToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
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

    /** Cached client version scraped from open.spotify.com. */
    @Volatile
    private var cachedClientVersion: String? = null

    /**
     * The sp_t cookie value captured from the token endpoint response.
     * Used as device_id in client token requests (Spotify ties sessions together
     * using this value — random UUIDs get rejected with HTTP 400).
     */
    @Volatile
    private var spTDeviceId: String? = null

    /** Returns the captured sp_t cookie value, or null if not yet acquired. */
    fun getSpT(): String? = spTDeviceId

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

        /** Regex to extract clientVersion from the Spotify web player HTML/config. */
        private val CLIENT_VERSION_REGEX = Regex(""""clientVersion"\s*:\s*"([^"]+)"""")
    }

    /**
     * Scrapes the current Spotify web player client version from open.spotify.com.
     *
     * The client token endpoint rejects outdated version strings with HTTP 400.
     * This fetches the live page and extracts the version from the embedded config.
     * Falls back to [SpotifyAuthConfig.CLIENT_VERSION_FALLBACK] if scraping fails.
     *
     * Public so [SpotifyApiClient] can use it for GraphQL request headers.
     */
    fun getClientVersion(): String {
        cachedClientVersion?.let { return it }

        return try {
            val request = Request.Builder()
                .url("https://open.spotify.com")
                .header("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36" +
                        " (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36")
                .get()
                .build()

            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""

            // Also capture sp_t cookie from the main page if available
            response.headers("Set-Cookie").forEach { setCookie ->
                if (setCookie.startsWith("sp_t=")) {
                    val value = setCookie.substringAfter("sp_t=").substringBefore(";")
                    if (value.isNotBlank() && spTDeviceId == null) {
                        spTDeviceId = value
                        Log.d(TAG, "Captured sp_t from page")
                    }
                }
            }
            response.close()

            val match = CLIENT_VERSION_REGEX.find(body)
            val version = match?.groupValues?.get(1)

            if (version != null) {
                Log.i(TAG, "Scraped Spotify client version: $version")
                cachedClientVersion = version
                version
            } else {
                Log.w(TAG, "Could not scrape client version, using fallback")
                SpotifyAuthConfig.CLIENT_VERSION_FALLBACK
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to scrape client version: ${e.message}")
            SpotifyAuthConfig.CLIENT_VERSION_FALLBACK
        }
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
                Log.w(TAG, "getClientCredentialsToken: failed HTTP ${response.code}")
                response.body?.close()
                return@withContext null
            }

            val responseBody = response.body?.string() ?: return@withContext null
            val jsonObj = json.parseToJsonElement(responseBody).jsonObject
            val token = jsonObj["access_token"]?.jsonPrimitive?.content
            if (token != null) {
                Log.d(TAG, "getClientCredentialsToken: acquired token (${token.length} chars)")
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

                // Capture sp_t cookie — used as device_id for client token requests.
                // Spotify uses sp_t to tie the session together; random UUIDs get rejected.
                response.headers("Set-Cookie").forEach { setCookie ->
                    if (setCookie.startsWith("sp_t=")) {
                        val value = setCookie.substringAfter("sp_t=").substringBefore(";")
                        if (value.isNotBlank()) {
                            spTDeviceId = value
                            Log.d(TAG, "Captured sp_t device ID (${value.length} chars)")
                        }
                    }
                }

                val body = response.body?.string() ?: return@withContext null
                val tokenResponse = json.decodeFromString<SpDcTokenResponse>(body)
                Log.d(TAG, "Token response: isAnonymous=${tokenResponse.isAnonymous}, " +
                    "hasUsername=${tokenResponse.username.isNotEmpty()}, " +
                    "hasClientId=${tokenResponse.clientId.isNotEmpty()}")

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
                Log.d(TAG, "Resolved username: present=${resolvedUsername.isNotEmpty()} " +
                    "(fromJwt=${jwtUsername != null}, fromResponse=${tokenResponse.username.isNotEmpty()})")

                // Pack both username and clientId into the scope field so we can
                // recover the clientId later for client token acquisition.
                val scopeValue = "$resolvedUsername$SCOPE_DELIMITER${tokenResponse.clientId}"
                Log.d(TAG, "Packed scope: ${scopeValue.length} chars")

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
                val clientVersion = getClientVersion()
                // Use sp_t cookie as device_id if available, fall back to random UUID.
                // Spotify's backend expects the sp_t value to tie the session together.
                val deviceId = spTDeviceId ?: UUID.randomUUID().toString()
                Log.d(TAG, "getClientToken: using deviceId source=${if (spTDeviceId != null) "sp_t" else "random"}")
                val requestBody = buildJsonObject {
                    putJsonObject("client_data") {
                        put("client_version", clientVersion)
                        put("client_id", clientId)
                        putJsonObject("js_sdk_data") {
                            put("device_brand", "unknown")
                            put("device_model", "unknown")
                            put("os", "windows")
                            put("os_version", "NT 10.0")
                            put("device_id", deviceId)
                            put("device_type", "computer")
                        }
                    }
                }.toString()

                Log.d(TAG, "Requesting client token: clientIdLen=${clientId.length}, " +
                    "deviceIdSource=${if (spTDeviceId != null) "sp_t" else "random"}")

                // Use HttpURLConnection instead of OkHttp for this endpoint.
                // Spotify fingerprints TLS ClientHello (JA3/JA4) and rejects OkHttp's
                // fingerprint with HTTP 400. Android's HttpURLConnection uses the
                // system BoringSSL which has a Chrome-like TLS fingerprint.
                val url = java.net.URL(SpotifyAuthConfig.CLIENT_TOKEN_ENDPOINT)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36" +
                        " (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36")

                conn.outputStream.use { it.write(requestBody.toByteArray()) }

                val responseCode = conn.responseCode
                if (responseCode != 200) {
                    Log.w(TAG, "Client token request failed: HTTP $responseCode")
                    conn.disconnect()
                    return@withContext null
                }

                val responseBody = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                Log.d(TAG, "Client token response: ${responseBody.length} chars")

                val jsonObj = json.parseToJsonElement(responseBody).jsonObject
                val responseType = jsonObj["response_type"]?.jsonPrimitive?.content
                Log.d(TAG, "Client token response_type: '$responseType'")

                if (responseType == "RESPONSE_GRANTED_TOKEN_RESPONSE") {
                    val token = jsonObj["granted_token"]
                        ?.jsonObject?.get("token")
                        ?.jsonPrimitive?.content
                    Log.d(TAG, "Client token acquired: ${token?.length ?: 0} chars")
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
            Log.d(TAG, "JWT payload decoded, extracting username")
            val jsonObj = json.parseToJsonElement(payload).jsonObject

            val username = jsonObj["sub"]?.jsonPrimitive?.content
                ?: jsonObj["username"]?.jsonPrimitive?.content
            Log.d(TAG, "JWT username extraction: found=${username?.isNotEmpty() == true}")
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
