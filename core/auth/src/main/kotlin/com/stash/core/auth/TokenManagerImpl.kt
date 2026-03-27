package com.stash.core.auth

import com.stash.core.auth.model.AuthService
import com.stash.core.auth.model.AuthState
import com.stash.core.auth.model.ServiceToken
import com.stash.core.auth.model.UserInfo
import com.stash.core.auth.spotify.SpotifyAuthManager
import com.stash.core.auth.store.EncryptedTokenStore
import com.stash.core.auth.youtube.YouTubeDeviceFlowManager
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
    private val youTubeDeviceFlowManager: YouTubeDeviceFlowManager,
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

        // Preserve the existing user info by only updating the token
        tokenStore.saveSpotifyToken(refreshed)
        return refreshed.accessToken
    }

    /**
     * Returns the stored Spotify username/user ID.
     *
     * Checks the stored user profile first (populated during initial connection),
     * then falls back to the token's scope field (where the username from the
     * token response would be stored, if it were available).
     */
    override suspend fun getSpotifyUsername(): String? {
        // Try the stored user profile (set during connectSpotifyWithCookie).
        val user = tokenStore.spotifyUser.first()
        if (user != null && user.id.isNotEmpty() && user.id != "spotify_user") {
            return user.id
        }
        // Fall back to the scope field (token response username, usually empty).
        val token = tokenStore.spotifyToken.first() ?: return null
        val username = token.scope
        return if (username.isNotEmpty()) username else null
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
        tokenStore.saveSpotifyToken(refreshed)
        return refreshed.accessToken
    }

    /**
     * Returns a valid YouTube access token, auto-refreshing via the stored
     * refresh token if the current token is expired or expiring soon.
     */
    override suspend fun getYouTubeAccessToken(): String? {
        val token = tokenStore.youTubeToken.first() ?: return null

        // If the token is still fresh, return it immediately
        if (!token.isExpired && !token.isExpiringSoon) {
            return token.accessToken
        }

        // Token is expired or expiring soon -- refresh using the stored refresh token
        val refreshToken = token.refreshToken
        if (refreshToken.isEmpty()) return null

        val refreshed = youTubeDeviceFlowManager.refreshAccessToken(refreshToken) ?: return null
        tokenStore.saveYouTubeToken(refreshed)
        return refreshed.accessToken
    }

    // -- Mutators -------------------------------------------------------------

    override suspend fun saveSpotifyAuth(token: ServiceToken, user: UserInfo) {
        tokenStore.saveSpotifyToken(token, user)
    }

    override suspend fun saveYouTubeAuth(token: ServiceToken, user: UserInfo) {
        tokenStore.saveYouTubeToken(token, user)
    }

    override suspend fun clearAuth(service: AuthService) {
        when (service) {
            AuthService.SPOTIFY -> tokenStore.clearSpotify()
            AuthService.YOUTUBE_MUSIC -> tokenStore.clearYouTube()
        }
    }

    override suspend fun isAuthenticated(service: AuthService): Boolean {
        return when (service) {
            AuthService.SPOTIFY -> tokenStore.spotifyToken.first()?.let { !it.isExpired } ?: false
            AuthService.YOUTUBE_MUSIC -> tokenStore.youTubeToken.first()?.let { !it.isExpired } ?: false
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
    override suspend fun connectSpotifyWithCookie(spDcCookie: String): Boolean {
        val token = spotifyAuthManager.getAccessToken(spDcCookie) ?: return false

        // The username is already resolved in getAccessToken() and stored in
        // ServiceToken.scope (from JWT decode or token response username field).
        // Also try JWT extraction as a direct fallback.
        val username = token.scope.takeIf { it.isNotEmpty() }
            ?: spotifyAuthManager.extractUsernameFromJwt(token.accessToken)
            ?: ""

        android.util.Log.d("StashSync", "connectSpotifyWithCookie: resolved username='$username'")

        val user = if (username.isNotEmpty()) {
            UserInfo(id = username, displayName = username)
        } else {
            // Last resort: store a placeholder. The resolveUserId() in SpotifyApiClient
            // will try additional strategies at sync time.
            android.util.Log.w("StashSync", "Could not extract username from JWT or token response!")
            UserInfo(id = "spotify_user", displayName = "Spotify User")
        }
        tokenStore.saveSpotifyToken(token, user)
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
