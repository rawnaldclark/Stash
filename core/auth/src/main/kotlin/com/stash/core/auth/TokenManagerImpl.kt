package com.stash.core.auth

import android.util.Log
import com.stash.core.auth.model.AuthService
import com.stash.core.auth.model.AuthState
import com.stash.core.auth.model.ServiceToken
import com.stash.core.auth.model.SpotifyConnectResult
import com.stash.core.auth.model.UserInfo
import com.stash.core.auth.spotify.SpotifyAuthManager
import com.stash.core.auth.spotify.SpotifyProfile
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val soundCloudAccounts: com.stash.core.auth.soundcloud.SoundCloudAccountManager,
) : TokenManager {

    override val soundCloudSession get() = soundCloudAccounts.session
    override suspend fun connectSoundCloudWithToken(token: String) = soundCloudAccounts.connect(token)
    override suspend fun connectSoundCloudPublicProfile(url: String) = soundCloudAccounts.connectPublicProfile(url)
    override suspend fun getSoundCloudToken(): String? = soundCloudAccounts.accessToken()
    override suspend fun hasSoundCloudConnection(): Boolean = soundCloudSession.first() != null

    /**
     * Dedicated scope for collecting store flows; survives until the process dies.
     * Main only for publishing the states: the store's `map { decrypt }` runs in
     * the collector's context, so each observer applies `flowOn(Default)`.
     * Without it the first emission built the Tink keyset (a SharedPreferences
     * read plus Keystore binder calls) on Main during startup, and every auth
     * write (each Spotify refresh) re-ran six decrypts + JSON parses there.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // -- Spotify state --------------------------------------------------------

    private val _spotifyAuthState = MutableStateFlow<AuthState>(AuthState.NotConnected)
    override val spotifyAuthState: StateFlow<AuthState> = _spotifyAuthState.asStateFlow()

    // -- YouTube Music state --------------------------------------------------

    private val _youTubeAuthState = MutableStateFlow<AuthState>(AuthState.NotConnected)
    override val youTubeAuthState: StateFlow<AuthState> = _youTubeAuthState.asStateFlow()

    // -- Discord state ----------------------------------------------------

    private val _discordAuthState = MutableStateFlow<AuthState>(AuthState.NotConnected)
    override val discordAuthState: StateFlow<AuthState> = _discordAuthState.asStateFlow()

    init {
        observeSpotify()
        observeYouTube()
        observeDiscord()
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

        // If the token is still fresh, return it immediately
        if (!token.isExpired && !token.isExpiringSoon) {
            return token.accessToken
        }

        // Token is expired or expiring soon -- refresh using the sp_dc cookie.
        // One refresh at a time: a sync, the auto-save scrobbler and like
        // mirroring all ask at once near expiry, and each used to hit the
        // (TOTP-guarded, rate-limited) token endpoint and rewrite the store.
        // Whoever waited re-reads, and uses the token the first caller saved.
        return spotifyRefreshLock.withLock {
            val current = tokenStore.spotifyToken.first() ?: return@withLock null
            if (!current.isExpired && !current.isExpiringSoon) return@withLock current.accessToken

            val spDcCookie = current.refreshToken
            if (spDcCookie.isEmpty()) return@withLock null
            val refreshed = spotifyAuthManager.refreshAccessToken(spDcCookie) ?: return@withLock null

            // Preserve the existing user info when saving the refreshed token.
            // Without this, the user gets cleared and the UI shows "Not Connected".
            val existingUser = tokenStore.spotifyUser.first()
            tokenStore.saveSpotifyToken(refreshed, existingUser)
            refreshed.accessToken
        }
    }

    /** Serializes Spotify token refreshes; see [getSpotifyAccessToken]. */
    private val spotifyRefreshLock = Mutex()

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
    override suspend fun forceRefreshSpotifyAccessToken(): String? = spotifyRefreshLock.withLock {
        val token = tokenStore.spotifyToken.first() ?: return@withLock null
        val spDcCookie = token.refreshToken
        if (spDcCookie.isEmpty()) return@withLock null

        val refreshed = spotifyAuthManager.refreshAccessToken(spDcCookie) ?: return@withLock null
        val existingUser = tokenStore.spotifyUser.first()
        tokenStore.saveSpotifyToken(refreshed, existingUser)
        refreshed.accessToken
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
        // Whose account this is isn't known here, so a recorded one (another sign-in's) goes.
        tokenStore.saveSpotifyConnection(token, user, account = null)
        spotifyAccountLookupTried = false
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

    override suspend fun getYouTubeIdentity(): com.stash.core.auth.model.YouTubeIdentity {
        val token = tokenStore.youTubeToken.first() ?: return com.stash.core.auth.model.YouTubeIdentity()
        return com.stash.core.auth.model.YouTubeIdentity.decode(token.scope)
    }

    override suspend fun setYouTubeProfile(
        identity: com.stash.core.auth.model.YouTubeIdentity,
        displayName: String,
        imageUrl: String?,
    ) {
        val token = tokenStore.youTubeToken.first() ?: return
        tokenStore.saveYouTubeToken(
            token.copy(scope = identity.encode()),
            UserInfo(id = identity.pageId ?: "youtube_user", displayName = displayName, imageUrl = imageUrl),
        )
    }

    override suspend fun clearAuth(service: AuthService) {
        when (service) {
            AuthService.SPOTIFY -> tokenStore.clearSpotify()
            AuthService.YOUTUBE_MUSIC -> tokenStore.clearYouTube()
            AuthService.DISCORD -> tokenStore.clearDiscord()
            AuthService.SOUNDCLOUD -> soundCloudAccounts.disconnect()
        }
    }

    override suspend fun isAuthenticated(service: AuthService): Boolean {
        return when (service) {
            AuthService.SOUNDCLOUD -> soundCloudSession.first()?.oauthToken != null
            AuthService.SPOTIFY -> tokenStore.spotifyToken.first()?.let {
                it.refreshToken.isNotEmpty()  // sp_dc cookie is the long-lived credential
            } ?: false
            AuthService.YOUTUBE_MUSIC -> tokenStore.youTubeToken.first()?.let {
                it.refreshToken.isNotEmpty()
            } ?: false
            AuthService.DISCORD -> tokenStore.discordToken.first()?.let {
                it.accessToken.isNotEmpty()
            } ?: false
        }
    }

    // -- Discord ------------------------------------------------------------

    /**
     * Discord has no client-side refresh flow worth persisting — the account
     * token itself IS the long-lived credential.
     * [com.stash.core.data.discord.DiscordRpcClient] exchanges it for
     * short-lived OAuth access tokens in-memory per app session. Stored as
     * the ServiceToken's accessToken; expiresAtEpoch is set far out since
     * there's nothing to expire client-side.
     */
    override suspend fun getDiscordUserToken(): String? {
        val token = tokenStore.discordToken.first() ?: return null
        return token.accessToken.takeIf { it.isNotEmpty() }
    }

    override suspend fun connectDiscordWithToken(token: String): Boolean {
        if (token.isBlank()) return false
        val result = runCatching {
            com.stash.core.auth.discord.DiscordProfileValidator.validateAndFetchProfile(token)
        }
        result.exceptionOrNull()?.let {
            Log.w("StashSync", "connectDiscordWithToken: profile validation failed", it)
        }
        val profile = result.getOrNull() ?: return false

        val username = (profile["username"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.content ?: "Discord User"
        val avatarHash = (profile["avatar"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        val userId = (profile["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        val avatarUrl = if (avatarHash != null && userId.isNotEmpty()) {
            "https://cdn.discordapp.com/avatars/$userId/$avatarHash.png"
        } else null

        val serviceToken = ServiceToken(
            accessToken = token,
            refreshToken = token, // decryptToken() treats an empty refreshToken as "not stored" — Discord has no separate refresh token, so duplicate it here rather than special-casing the shared decrypt path.
            expiresAtEpoch = java.time.Instant.now().epochSecond + 365L * 24 * 3600,
        )
        tokenStore.saveDiscordToken(
            serviceToken,
            UserInfo(id = userId, displayName = username, imageUrl = avatarUrl),
        )
        return true
    }

    private fun observeDiscord() {
        scope.launch {
            tokenStore.discordToken
                .combine(tokenStore.discordUser) { token, user -> token to user }
                .flowOn(Dispatchers.Default)
                .collect { (token, user) ->
                    _discordAuthState.value = when {
                        token != null && user != null -> AuthState.Connected(user)
                        else -> AuthState.NotConnected
                    }
                }
        }
    }

    /**
     * Validates the sp_dc cookie by attempting to obtain an access token,
     * identifies whose account it is, and persists the credentials, unless a
     * different account is already connected (the account-swap guard below).
     *
     * IMPORTANT: We do NOT call api.spotify.com/v1/me here. That endpoint
     * permanently 429-blocks sp_dc tokens. The account comes from the web
     * player's own `profileAttributes` query ([SpotifyAuthManager.fetchProfile]),
     * as the token is opaque and the token response carries no username.
     *
     * **The account-swap guard.** Signing in again used to overwrite whatever
     * was stored. Signing in as ANOTHER account (the card's Reconnect, the Sync
     * tab's Re-authenticate and the paste dialog all land here) silently
     * replaced the connected one, and in Refresh mode the next sync then hid its
     * whole library. Now a different account is refused and NOTHING is written:
     * the connected account's cookie, token and playlists stay as they were.
     * Refused only when both accounts are known and differ. When either can't be
     * told (Spotify didn't answer, or an old sign-in whose cookie is dead and
     * whose account was never recorded) the sign-in is saved as before: a guard
     * that can't see must not lock someone out of reconnecting their own account.
     * Switching accounts stays possible the deliberate way: Disconnect, then Connect.
     */
    override suspend fun connectSpotifyWithCookie(spDcCookie: String, username: String): SpotifyConnectResult {
        val token = spotifyAuthManager.getAccessToken(spDcCookie) ?: return SpotifyConnectResult.Invalid

        val scopeParts = token.scope.split(SpotifyAuthManager.SCOPE_DELIMITER)
        val clientId = scopeParts.getOrNull(1) ?: ""
        val incoming = spotifyAuthManager.fetchProfile(token.accessToken, clientId)

        // An unidentified sign-in can't be refused, so the connected account isn't looked up then.
        val connected = incoming?.let { connectedSpotifyAccount() }
        if (connected != null && incoming != null && !connected.isSameAccount(incoming)) {
            Log.w("StashSync", "connectSpotifyWithCookie: refused, a different account than the connected one")
            return SpotifyConnectResult.DifferentAccount(
                connectedName = connected.displayName.ifBlank { connected.userId },
                attemptedName = incoming.displayName.ifBlank { incoming.userId },
            )
        }

        // Use the user-provided username first, then try token/JWT extraction,
        // then the account Spotify named (the token is usually opaque).
        val resolvedUsername = username.takeIf { it.isNotEmpty() }
            ?: scopeParts.firstOrNull()?.takeIf { it.isNotEmpty() }
            ?: spotifyAuthManager.extractUsernameFromJwt(token.accessToken)
            ?: incoming?.userId
            ?: ""

        Log.d("StashSync", "connectSpotifyWithCookie: hasResolvedUsername=${resolvedUsername.isNotEmpty()}, " +
            "hasProvidedUsername=${username.isNotEmpty()}, identified=${incoming != null}, " +
            "sameAccount=${connected != null && incoming != null}")

        // Re-pack the scope with the resolved username + clientId
        val updatedToken = token.copy(
            scope = "$resolvedUsername${SpotifyAuthManager.SCOPE_DELIMITER}$clientId"
        )

        val user = if (resolvedUsername.isNotEmpty()) {
            // "Connected as": the name typed in the paste dialog, else Spotify's display name.
            val shownName = username.takeIf { it.isNotEmpty() } ?: incoming?.displayName ?: resolvedUsername
            UserInfo(id = resolvedUsername, displayName = shownName)
        } else {
            Log.w("StashSync", "No username available — playlists may not sync")
            UserInfo(id = "spotify_user", displayName = "Spotify User")
        }
        tokenStore.saveSpotifyConnection(updatedToken, user, incoming)
        // Spotify didn't say whose this is: the next sync's probe may look it up again.
        if (incoming == null) spotifyAccountLookupTried = false
        return SpotifyConnectResult.Connected
    }

    /**
     * The connected Spotify account, or null when signed out or it can't be told. The recorded
     * account when there is one; otherwise (signed in before Stash recorded it) asked of Spotify
     * with the stored cookie, which works when the session wasn't really dead. Nothing is saved
     * here: a refused sign-in must leave the stored one exactly as it was.
     */
    private suspend fun connectedSpotifyAccount(): SpotifyProfile? {
        val stored = tokenStore.spotifyToken.first() ?: return null
        tokenStore.spotifyAccount.first()?.let { return it }
        val fresh = spotifyAuthManager.getAccessToken(stored.refreshToken) ?: return null
        val clientId = fresh.scope.split(SpotifyAuthManager.SCOPE_DELIMITER).getOrNull(1) ?: return null
        return spotifyAuthManager.fetchProfile(fresh.accessToken, clientId)
    }

    /** One [rememberSpotifyAccount] lookup per process: a failing one isn't retried every sync. */
    @Volatile
    private var spotifyAccountLookupTried = false

    override suspend fun rememberSpotifyAccount() {
        if (spotifyAccountLookupTried) return
        val stored = tokenStore.spotifyToken.first() ?: return
        if (tokenStore.spotifyAccount.first() != null) return
        spotifyAccountLookupTried = true
        val accessToken = getSpotifyAccessToken() ?: return
        val clientId = getSpotifyClientId() ?: return
        val account = spotifyAuthManager.fetchProfile(accessToken, clientId) ?: return
        // Only for the sign-in it was looked up with: a sign-out or new sign-in meanwhile wins.
        val written = tokenStore.saveSpotifyAccountFor(stored.refreshToken, account)
        Log.d("StashSync", "rememberSpotifyAccount: recorded=$written")
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
                .flowOn(Dispatchers.Default)
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
                .flowOn(Dispatchers.Default)
                .collect { (token, user) ->
                    _youTubeAuthState.value = when {
                        token != null && user != null -> AuthState.Connected(user)
                        else -> AuthState.NotConnected
                    }
                }
        }
    }
}
