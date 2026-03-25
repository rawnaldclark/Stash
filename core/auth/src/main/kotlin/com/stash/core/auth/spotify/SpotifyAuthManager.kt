package com.stash.core.auth.spotify

import android.net.Uri
import com.stash.core.auth.model.ServiceToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.CodeVerifierUtil
import net.openid.appauth.ResponseTypeValues
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the Spotify OAuth 2.0 Authorization Code flow with PKCE.
 *
 * Uses the AppAuth library to build standards-compliant authorization requests and
 * OkHttp for the token exchange / refresh calls (AppAuth's built-in token service
 * can be unreliable on some Android versions).
 *
 * Typical usage:
 * 1. Call [buildAuthRequest] to get an [AuthorizationRequest] and launch it via
 *    an AppAuth [net.openid.appauth.AuthorizationService].
 * 2. When the redirect returns, parse the result with [AuthorizationResponse.fromIntent]
 *    and pass it to [exchangeCodeForToken].
 * 3. Later, call [refreshAccessToken] when the access token expires.
 */
@Singleton
class SpotifyAuthManager @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val serviceConfig = AuthorizationServiceConfiguration(
        Uri.parse(SpotifyAuthConfig.AUTH_ENDPOINT),
        Uri.parse(SpotifyAuthConfig.TOKEN_ENDPOINT),
    )

    /**
     * Builds an AppAuth [AuthorizationRequest] configured for Spotify PKCE.
     *
     * The returned request includes a cryptographically random code verifier and
     * S256 challenge so that the authorization code cannot be intercepted and
     * replayed without the verifier.
     */
    fun buildAuthRequest(): AuthorizationRequest {
        val codeVerifier = CodeVerifierUtil.generateRandomCodeVerifier()
        val codeChallenge = CodeVerifierUtil.deriveCodeVerifierChallenge(codeVerifier)

        return AuthorizationRequest.Builder(
            serviceConfig,
            SpotifyAuthConfig.CLIENT_ID,
            ResponseTypeValues.CODE,
            Uri.parse(SpotifyAuthConfig.REDIRECT_URI),
        )
            .setCodeVerifier(
                codeVerifier,
                codeChallenge,
                CodeVerifierUtil.getCodeVerifierChallengeMethod(),
            )
            .setScope(SpotifyAuthConfig.SCOPES.joinToString(" "))
            .build()
    }

    /**
     * Exchanges the authorization code from [response] for an access + refresh token pair.
     *
     * @return A [ServiceToken] on success, or null if the exchange fails.
     */
    suspend fun exchangeCodeForToken(response: AuthorizationResponse): ServiceToken? {
        return withContext(Dispatchers.IO) {
            val codeVerifier = response.request.codeVerifier ?: return@withContext null
            val authCode = response.authorizationCode ?: return@withContext null

            val body = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", authCode)
                .add("redirect_uri", SpotifyAuthConfig.REDIRECT_URI)
                .add("client_id", SpotifyAuthConfig.CLIENT_ID)
                .add("code_verifier", codeVerifier)
                .build()

            executeTokenRequest(body)
        }
    }

    /**
     * Exchanges a refresh token for a fresh access token.
     *
     * Spotify may optionally rotate the refresh token, so the returned [ServiceToken]
     * always carries the latest refresh token (falling back to the original if the
     * response does not include a new one).
     *
     * @return A [ServiceToken] on success, or null if the refresh fails.
     */
    suspend fun refreshAccessToken(refreshToken: String): ServiceToken? {
        return withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", SpotifyAuthConfig.CLIENT_ID)
                .build()

            executeTokenRequest(body, fallbackRefreshToken = refreshToken)
        }
    }

    // ── Internal helpers ─────────────────────────────────────────────────

    /**
     * Sends a POST request to the Spotify token endpoint and parses the JSON response.
     *
     * @param formBody         The form-encoded request body (grant_type, code, etc.).
     * @param fallbackRefreshToken Optional refresh token to use when the response does not
     *                             include one (common during refresh flows).
     */
    private fun executeTokenRequest(
        formBody: FormBody,
        fallbackRefreshToken: String = "",
    ): ServiceToken? {
        val request = Request.Builder()
            .url(SpotifyAuthConfig.TOKEN_ENDPOINT)
            .post(formBody)
            .build()

        val httpResponse = okHttpClient.newCall(request).execute()
        if (!httpResponse.isSuccessful) return null

        val responseBody = httpResponse.body?.string() ?: return null
        val tokenResponse = json.decodeFromString<SpotifyTokenResponse>(responseBody)

        return ServiceToken(
            accessToken = tokenResponse.accessToken,
            refreshToken = tokenResponse.refreshToken ?: fallbackRefreshToken,
            expiresAtEpoch = Instant.now().epochSecond + tokenResponse.expiresIn,
            scope = tokenResponse.scope ?: "",
        )
    }
}

/**
 * JSON shape returned by Spotify's `/api/token` endpoint.
 *
 * Uses [SerialName] annotations so that Kotlin property names follow conventions
 * while still mapping to the snake_case JSON keys.
 */
@Serializable
private data class SpotifyTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "",
    @SerialName("expires_in") val expiresIn: Long = 3600,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("scope") val scope: String? = null,
)
