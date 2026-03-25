package com.stash.core.auth.youtube

import com.stash.core.auth.model.DeviceCodeState
import com.stash.core.auth.model.ServiceToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the Google OAuth 2.0 Device Authorization Grant flow for YouTube Music.
 *
 * This flow is designed for devices with limited input capabilities (smart TVs,
 * set-top boxes, etc.) and works well on Android when a browser-based redirect
 * is not desirable. The user visits [DeviceCodeState.verificationUrl] on any
 * device, enters the [DeviceCodeState.userCode], and this manager polls for
 * token issuance.
 *
 * Typical usage:
 * 1. Call [requestDeviceCode] and display the returned user code + URL.
 * 2. Call [pollForToken] with the device code; it blocks (suspends) until
 *    the user authorizes, the code expires, or an unrecoverable error occurs.
 * 3. Later, call [refreshAccessToken] when the access token expires.
 */
@Singleton
class YouTubeDeviceFlowManager @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Requests a new device code from Google's device authorization endpoint.
     *
     * @return A [DeviceCodeState] containing the user code and verification URL
     *         to present to the user, or null if the request fails.
     */
    suspend fun requestDeviceCode(): DeviceCodeState? = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", YouTubeAuthConfig.CLIENT_ID)
            .add("scope", YouTubeAuthConfig.SCOPE)
            .build()

        val request = Request.Builder()
            .url(YouTubeAuthConfig.DEVICE_CODE_ENDPOINT)
            .post(body)
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) return@withContext null

        val responseBody = response.body?.string() ?: return@withContext null
        val deviceResponse = json.decodeFromString<DeviceCodeResponse>(responseBody)

        DeviceCodeState(
            deviceCode = deviceResponse.deviceCode,
            userCode = deviceResponse.userCode,
            verificationUrl = deviceResponse.verificationUrl,
            expiresAtEpoch = Instant.now().epochSecond + deviceResponse.expiresIn,
            intervalSeconds = deviceResponse.interval,
        )
    }

    /**
     * Polls the Google token endpoint until the user completes authorization.
     *
     * This is a long-running suspending function that respects the server-mandated
     * polling interval. It will return when one of the following occurs:
     * - The user authorizes the device and a [ServiceToken] is returned.
     * - The device code expires (returns null).
     * - An unrecoverable error occurs (returns null).
     *
     * The `authorization_pending` and `slow_down` error codes are handled
     * automatically: the former continues polling, the latter increases the
     * interval by 5 seconds as required by the spec.
     *
     * @param deviceCode      The device code from [requestDeviceCode].
     * @param intervalSeconds  Minimum polling interval in seconds.
     * @return A [ServiceToken] on success, or null on timeout / error.
     */
    suspend fun pollForToken(
        deviceCode: String,
        intervalSeconds: Int,
    ): ServiceToken? = withContext(Dispatchers.IO) {
        var currentInterval = intervalSeconds.toLong()

        while (true) {
            delay(currentInterval * 1000)

            val body = FormBody.Builder()
                .add("client_id", YouTubeAuthConfig.CLIENT_ID)
                .add("client_secret", YouTubeAuthConfig.CLIENT_SECRET)
                .add("device_code", deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .build()

            val request = Request.Builder()
                .url(YouTubeAuthConfig.TOKEN_ENDPOINT)
                .post(body)
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext null

            if (response.isSuccessful) {
                val tokenResponse = json.decodeFromString<GoogleTokenResponse>(responseBody)
                return@withContext ServiceToken(
                    accessToken = tokenResponse.accessToken,
                    refreshToken = tokenResponse.refreshToken ?: "",
                    expiresAtEpoch = Instant.now().epochSecond + tokenResponse.expiresIn,
                    scope = tokenResponse.scope ?: "",
                )
            }

            // Parse error response to determine whether to continue polling
            val errorResponse = json.decodeFromString<GoogleErrorResponse>(responseBody)
            when (errorResponse.error) {
                "authorization_pending" -> continue
                "slow_down" -> {
                    currentInterval += SLOW_DOWN_INCREMENT_SECONDS
                    continue
                }
                else -> return@withContext null // "expired_token", "access_denied", etc.
            }
        }

        @Suppress("UNREACHABLE_CODE")
        null
    }

    /**
     * Exchanges a refresh token for a fresh access token.
     *
     * @return A [ServiceToken] on success, or null if the refresh fails.
     */
    suspend fun refreshAccessToken(refreshToken: String): ServiceToken? {
        return withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
                .add("client_id", YouTubeAuthConfig.CLIENT_ID)
                .add("client_secret", YouTubeAuthConfig.CLIENT_SECRET)
                .add("refresh_token", refreshToken)
                .add("grant_type", "refresh_token")
                .build()

            val request = Request.Builder()
                .url(YouTubeAuthConfig.TOKEN_ENDPOINT)
                .post(body)
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val responseBody = response.body?.string() ?: return@withContext null
            val tokenResponse = json.decodeFromString<GoogleTokenResponse>(responseBody)

            ServiceToken(
                accessToken = tokenResponse.accessToken,
                refreshToken = tokenResponse.refreshToken ?: refreshToken,
                expiresAtEpoch = Instant.now().epochSecond + tokenResponse.expiresIn,
                scope = tokenResponse.scope ?: "",
            )
        }
    }

    private companion object {
        /** Seconds to add to the polling interval on a "slow_down" response (RFC 8628 s3.5). */
        const val SLOW_DOWN_INCREMENT_SECONDS = 5L
    }
}

// ── JSON response DTOs ───────────────────────────────────────────────────────

/**
 * JSON shape returned by Google's device authorization endpoint.
 */
@Serializable
private data class DeviceCodeResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_url") val verificationUrl: String = "",
    @SerialName("expires_in") val expiresIn: Long = 1800,
    @SerialName("interval") val interval: Int = 5,
)

/**
 * JSON shape returned by Google's token endpoint on success.
 */
@Serializable
private data class GoogleTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "",
    @SerialName("expires_in") val expiresIn: Long = 3600,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("scope") val scope: String? = null,
)

/**
 * JSON shape returned by Google's token endpoint on error during device-flow polling.
 */
@Serializable
private data class GoogleErrorResponse(
    val error: String = "",
    @SerialName("error_description") val errorDescription: String = "",
)
