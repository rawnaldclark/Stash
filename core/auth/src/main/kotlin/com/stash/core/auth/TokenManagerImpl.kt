package com.stash.core.auth

import com.stash.core.auth.model.AuthService
import com.stash.core.auth.model.AuthState
import com.stash.core.auth.model.ServiceToken
import com.stash.core.auth.model.UserInfo
import com.stash.core.auth.store.EncryptedTokenStore
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
 * reactive [AuthState] values. Access-token getters perform expiry checks so callers
 * receive null when a refresh is needed (the refresh itself is handled by each
 * service-specific auth manager).
 */
@Singleton
class TokenManagerImpl @Inject constructor(
    private val tokenStore: EncryptedTokenStore,
) : TokenManager {

    /** Dedicated scope for collecting store flows; survives until the process dies. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ── Spotify state ────────────────────────────────────────────────────

    private val _spotifyAuthState = MutableStateFlow<AuthState>(AuthState.NotConnected)
    override val spotifyAuthState: StateFlow<AuthState> = _spotifyAuthState.asStateFlow()

    // ── YouTube Music state ──────────────────────────────────────────────

    private val _youTubeAuthState = MutableStateFlow<AuthState>(AuthState.NotConnected)
    override val youTubeAuthState: StateFlow<AuthState> = _youTubeAuthState.asStateFlow()

    init {
        observeSpotify()
        observeYouTube()
    }

    // ── Access-token getters ─────────────────────────────────────────────

    override suspend fun getSpotifyAccessToken(): String? {
        val token = tokenStore.spotifyToken.first() ?: return null
        if (token.isExpired || token.isExpiringSoon) return null
        return token.accessToken
    }

    override suspend fun getYouTubeAccessToken(): String? {
        val token = tokenStore.youTubeToken.first() ?: return null
        if (token.isExpired || token.isExpiringSoon) return null
        return token.accessToken
    }

    // ── Mutators ─────────────────────────────────────────────────────────

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

    // ── Internal observers ───────────────────────────────────────────────

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
