package com.stash.core.auth

import android.util.Log
import com.stash.core.auth.model.AuthService
import com.stash.core.auth.model.AuthState
import com.stash.core.auth.model.ServiceToken
import com.stash.core.auth.model.UserInfo
import com.stash.core.auth.spotify.SpotifyAuthManager
import com.stash.core.auth.store.EncryptedTokenStore
import com.stash.core.auth.youtube.YouTubeCookieHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [TokenManager] implementation backed by [EncryptedTokenStore].
 *
 * Observes the encrypted token and user flows for each service and maps them into
 * reactive [AuthState] values. The Spotify access-token getter automatically refreshes
 * expired tokens using the stored sp_dc cookie via [SpotifyAuthManager].
 */
@Singleton
class TokenManagerImpl @Inject constructor(
    private val tokenStore: EncryptedTokenStore,
    private val spotifyAuthManager: SpotifyAuthManager,
    private val youTubeCookieHelper: YouTubeCookieHelper,
) : TokenManager {

    /** Dedicated scope for collecting store flows; survives until the process dies. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // -- Spotify state --------------------------------------------------------

    private val _spotifyAuthState = MutableStateFlow<AuthState>(AuthState.NotConnected)
    override val spotifyAuthState: StateFlow<AuthState> = _spotifyAuthState.asStateFlow()

    // -- YouTube Music state --------------------------------------------------

    private val _youTubeAuthState = MutableStateFlow<AuthState>(AuthState.NotConnected)
    override val youTubeAuthState: StateFlow<AuthState> = _youTubeAuthState.asStateFlow()

    init {
        observeSpotify()
        observeYouTube()
    }

    // -- Access-token getters -------------------------------------------------

    /**
     * Returns a valid Spotify access token, auto-refreshing via the stored sp_dc
     * cookie if the current token is expired or expiring soon.
     *
     * The sp_dc cookie is stored in [ServiceToken.refreshToken].
     */
    override suspend fun getSpotifyAccessToken(): String? {
        val token = tokenStore.spotifyToken.first() ?: return null
        val spDcCookie = token.refreshToken

        // If the token is still fresh, return it immediately
        if (!token.isExpired && !token.isExpiringSoon) {
            return token.accessToken
        }

        // Token is expired or expiring soon -- refresh using the sp_dc cookie
        if (spDcCookie.isEmpty()) return null
        val refreshed = spotifyAuthManager.refreshAccessToken(spDcCookie) ?: return null

        // Preserve the existing user info when saving the refreshed token.
        // Without this, the user gets cleared and the UI shows "Not Connected".
        val existingUser = tokenStore.spotifyUser.first()
        tokenStore.saveSpotifyToken(refreshed, existingUser)
        return refreshed.accessToken
    }

    /**
     * Returns the stored Spotify username/user ID.
     *
     * Checks the stored user profile first (populated during initial connection),
     * then falls back to the token's scope field which contains "username|clientId".
     */
    override suspend fun getSpotifyUsername(): String? {
        // Try the stored user profile (set during connectSpotifyWithCookie).
        val user = tokenStore.spotifyUser.first()
        if (user != null && user.id.isNotEmpty() && user.id != "spotify_user") {
            return user.id
        }
        // Fall back to the scope field which now contains "username|clientId".
        val token = tokenStore.spotifyToken.first() ?: return null
        val scope = token.scope
        if (scope.isEmpty()) return null
        // Extract username (before the delimiter)
        val username = scope.split(SpotifyAuthManager.SCOPE_DELIMITER).firstOrNull() ?: scope
        return username.takeIf { it.isNotEmpty() }
    }

    /**
     * Returns the stored Spotify client ID from the token's scope field.
     *
     * The scope field contains "username|clientId" as packed by [SpotifyAuthManager].
     * The client ID is needed for acquiring client tokens for the GraphQL Partner API.
     */
    override suspend fun getSpotifyClientId(): String? {
        val token = tokenStore.spotifyToken.first() ?: return null
        val scope = token.scope
        if (scope.isEmpty()) return null
        val parts = scope.split(SpotifyAuthManager.SCOPE_DELIMITER)
        val clientId = if (parts.size >= 2) parts[1] else null
        Log.d("StashSync", "getSpotifyClientId: hasScope=${scope.isNotEmpty()}, hasClientId=${clientId?.isNotEmpty() == true}")
        return clientId?.takeIf { it.isNotEmpty() }
    }

    /**
     * Returns the stored sp_dc cookie from the token's refreshToken field.
     */
    override suspend fun getSpDcCookie(): String? {
        val token = tokenStore.spotifyToken.first() ?: return null
        val spDc = token.refreshToken
        return if (spDc.isNotEmpty()) spDc else null
    }

    /**
     * Forces a Spotify token refresh regardless of expiry status.
     * This obtains a completely new access token from the sp_dc cookie,
     * which should have a fresh rate-limit window on the Spotify side.
     */
    override suspend fun forceRefreshSpotifyAccessToken(): String? {
        val token = tokenStore.spotifyToken.first() ?: return null
        val spDcCookie = token.refreshToken
        if (spDcCookie.isEmpty()) return null

        val refreshed = spotifyAuthManager.refreshAccessToken(spDcCookie) ?: return null
        val existingUser = tokenStore.spotifyUser.first()
        tokenStore.saveSpotifyToken(refreshed, existingUser)
        return refreshed.accessToken
    }

    /**
     * Returns the stored YouTube Music cookie string for InnerTube authentication.
     * The cookie is stored in the ServiceToken's refreshToken field.
     */
    override suspend fun getYouTubeCookie(): String? {
        val token = tokenStore.youTubeToken.first() ?: return null
        return token.refreshToken.takeIf { it.isNotEmpty() }
    }

    // -- Mutators -------------------------------------------------------------

    override suspend fun saveSpotifyAuth(token: ServiceToken, user: UserInfo) {
        tokenStore.saveSpotifyToken(token, user)
    }

    /**
     * Validates a YouTube Music cookie and stores it for InnerTube authentication.
     * The cookie must contain SAPISID or __Secure-3PAPISID for SAPISIDHASH auth.
     */
    override suspend fun connectYouTubeWithCookie(cookie: String): Boolean {
        if (cookie.isBlank()) return false
        val sapiSid = youTubeCookieHelper.extractSapiSid(cookie) ?: return false
        if (sapiSid.isBlank()) return false

        // Store the cookie in a ServiceToken: refreshToken = cookie, accessToken = placeholder
        val token = ServiceToken(
            accessToken = "cookie_auth",
            refreshToken = cookie.trim(),
            expiresAtEpoch = java.time.Instant.now().epochSecond + 365L * 24 * 3600,
        )
        val user = UserInfo(id = "youtube_user", displayName = "YouTube User")
        tokenStore.saveYouTubeToken(token, user)
        return true
    }

    override suspend fun clearAuth(service: AuthService) {
        when (service) {
            AuthService.SPOTIFY -> tokenStore.clearSpotify()
            AuthService.YOUTUBE_MUSIC -> tokenStore.clearYouTube()
        }
    }

    override suspend fun isAuthenticated(service: AuthService): Boolean {
        return when (service) {
            AuthService.SPOTIFY -> tokenStore.spotifyToken.first()?.let {
                it.refreshToken.isNotEmpty()  // sp_dc cookie is the long-lived credential
            } ?: false
            AuthService.YOUTUBE_MUSIC -> tokenStore.youTubeToken.first()?.let {
                it.refreshToken.isNotEmpty()
            } ?: false
        }
    }

    /**
     * Validates the sp_dc cookie by attempting to obtain an access token,
     * extracts the user ID from the JWT (no network call), and persists
     * the credentials.
     *
     * IMPORTANT: We do NOT call api.spotify.com/v1/me here. That endpoint
     * permanently 429-blocks sp_dc tokens. Instead, the username is extracted
     * from the JWT access token payload and/or the token endpoint response.
     */
    override suspend fun connectSpotifyWithCookie(spDcCookie: String, username: String): Boolean {
        val token = spotifyAuthManager.getAccessToken(spDcCookie) ?: return false

        // Use the user-provided username first, then try token/JWT extraction as fallback
        val scopeParts = token.scope.split(SpotifyAuthManager.SCOPE_DELIMITER)
        val resolvedUsername = username.takeIf { it.isNotEmpty() }
            ?: scopeParts.firstOrNull()?.takeIf { it.isNotEmpty() }
            ?: spotifyAuthManager.extractUsernameFromJwt(token.accessToken)
            ?: ""

        Log.d("StashSync", "connectSpotifyWithCookie: hasResolvedUsername=${resolvedUsername.isNotEmpty()}, " +
            "hasProvidedUsername=${username.isNotEmpty()}")

        // Re-pack the scope with the resolved username + clientId
        val clientId = scopeParts.getOrNull(1) ?: ""
        val updatedToken = token.copy(
            scope = "$resolvedUsername${SpotifyAuthManager.SCOPE_DELIMITER}$clientId"
        )

        val user = if (resolvedUsername.isNotEmpty()) {
            UserInfo(id = resolvedUsername, displayName = resolvedUsername)
        } else {
            Log.w("StashSync", "No username available — playlists may not sync")
            UserInfo(id = "spotify_user", displayName = "Spotify User")
        }
        tokenStore.saveSpotifyToken(updatedToken, user)
        return true
    }

    // -- Internal observers ---------------------------------------------------

    /**
     * Combines the Spotify token and user flows to derive the current [AuthState].
     * A non-null user with a non-null token maps to [AuthState.Connected]; otherwise
     * the state falls back to [AuthState.NotConnected].
     */
    private fun observeSpotify() {
        scope.launch {
            tokenStore.spotifyToken
                .combine(tokenStore.spotifyUser) { token, user -> token to user }
                .collect { (token, user) ->
                    _spotifyAuthState.value = when {
                        token != null && user != null -> AuthState.Connected(user)
                        else -> AuthState.NotConnected
                    }
                }
        }
    }

    /** Same as [observeSpotify] but for YouTube Music. */
    private fun observeYouTube() {
        scope.launch {
            tokenStore.youTubeToken
                .combine(tokenStore.youTubeUser) { token, user -> token to user }
                .collect { (token, user) ->
                    _youTubeAuthState.value = when {
                        token != null && user != null -> AuthState.Connected(user)
                        else -> AuthState.NotConnected
                    }
                }
        }
    }
}
