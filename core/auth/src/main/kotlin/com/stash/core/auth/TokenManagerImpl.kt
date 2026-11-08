package com.stash.core.auth

import android.util.Log
import com.stash.core.auth.model.AuthService
import com.stash.core.auth.model.AuthState
import com.stash.core.auth.model.ServiceToken
import com.stash.core.auth.model.SpotifyConnectResult
import com.stash.core.auth.model.SpotifyCookieCheck
import com.stash.core.auth.model.UserInfo
import com.stash.core.auth.model.ValidatedSpotifySignIn
import com.stash.core.auth.spotify.SpotifyAccount
import com.stash.core.auth.spotify.SpotifyAuthManager
import com.stash.core.auth.spotify.SpotifyProfile
import com.stash.core.auth.store.EncryptedTokenStore
import com.stash.core.auth.store.StoredSpotifySignIn
import java.util.concurrent.ConcurrentHashMap
import com.stash.core.auth.youtube.YouTubeCookieHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [TokenManager] implementation backed by [EncryptedTokenStore].
 *
 * Observes the encrypted token and user flows for each service and maps them into
 * reactive [AuthState] values. The Spotify access-token getter automatically refreshes
 * expired tokens using the stored sp_dc cookie via [SpotifyAuthManager].
 *
 * Spotify reads answer for the acting account ([SpotifyAccount] in the caller's coroutine
 * context): an extra account's own sign-in when one is set, never the main's (see the
 * "Extra Spotify accounts" section), and the main account's otherwise, exactly as before.
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
        actingExtra()?.let { return getSpotifyExtraAccessToken(it) }
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
            savedRefresh(spDcCookie, refreshed)
        }
    }

    /** Serializes Spotify token refreshes; see [getSpotifyAccessToken]. */
    private val spotifyRefreshLock = Mutex()

    /**
     * Saves a refreshed token (the store keeps the user, without which the UI shows "Not
     * Connected") and returns its access token, but only while the stored sign-in is still the one
     * refreshed. If a Disconnect or a new sign-in landed during the refresh, that wins: nothing is
     * saved and the token, which belongs to an account no longer connected, is never handed out.
     *
     * The answer is then for the sign-in stored now, signed out being null. Null alone would read
     * as "Session expired" to a sync's health probe, a false alarm when a sign-in has just landed.
     */
    private suspend fun savedRefresh(spDcCookie: String, refreshed: ServiceToken): String? {
        if (tokenStore.saveRefreshedSpotifyToken(spDcCookie, refreshed)) return refreshed.accessToken
        Log.i("StashSync", "Spotify token refresh dropped: the sign-in changed meanwhile")
        return tokenStore.spotifyToken.first()?.takeIf { !it.isExpired }?.accessToken
    }

    /**
     * Returns the stored Spotify username/user ID.
     *
     * Checks the stored user profile first (populated during initial connection),
     * then falls back to the token's scope field which contains "username|clientId".
     */
    override suspend fun getSpotifyUsername(): String? {
        actingExtra()?.let { return extraSignIn(it)?.account?.userId }
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
        val token = actingSignIn() ?: return null
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
        val token = actingSignIn() ?: return null
        val spDc = token.refreshToken
        return if (spDc.isNotEmpty()) spDc else null
    }

    /**
     * Forces a Spotify token refresh regardless of expiry status.
     * This obtains a completely new access token from the sp_dc cookie,
     * which should have a fresh rate-limit window on the Spotify side.
     */
    override suspend fun forceRefreshSpotifyAccessToken(): String? {
        actingExtra()?.let { return forceRefreshSpotifyExtraAccessToken(it) }
        return spotifyRefreshLock.withLock {
            val token = tokenStore.spotifyToken.first() ?: return@withLock null
            val spDcCookie = token.refreshToken
            if (spDcCookie.isEmpty()) return@withLock null

            val refreshed = spotifyAuthManager.refreshAccessToken(spDcCookie) ?: return@withLock null
            savedRefresh(spDcCookie, refreshed)
        }
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
        spotifyAccountLookupAtMs = 0L
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
            AuthService.SPOTIFY -> actingSignIn()?.let {
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
     * told (Spotify didn't answer in time, or an old sign-in whose cookie is dead
     * and whose account was never recorded) the sign-in is saved as before, and
     * the log says why: a guard that can't see must not lock someone out of
     * reconnecting their own account.
     * Switching accounts stays possible the deliberate way: Disconnect, then Connect.
     */
    override suspend fun connectSpotifyWithCookie(spDcCookie: String, username: String): SpotifyConnectResult {
        val token = spotifyAuthManager.getAccessToken(spDcCookie) ?: return SpotifyConnectResult.Invalid

        val scopeParts = token.scope.split(SpotifyAuthManager.SCOPE_DELIMITER)
        val clientId = scopeParts.getOrNull(1) ?: ""
        val (incoming, connected) = identifySpotifyAccounts(token.accessToken, clientId)

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
            "checked=${connected != null && incoming != null}")

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
        if (incoming == null) spotifyAccountLookupAtMs = 0L
        return SpotifyConnectResult.Connected
    }

    /**
     * Whose the new sign-in is, and whose the connected one is, as far as can be told within
     * [SPOTIFY_IDENTIFY_BUDGET_MS]: a sign-in must never wait on this for long. Past the budget
     * whatever was learned stands, and the sign-in carries on as if Spotify hadn't said.
     *
     * The work runs detached from the caller, on its dispatcher. A timeout can't interrupt a step
     * stuck in a blocking call (the token exchange's `execute()`, the client token's connection,
     * the bundle download), and a child would hold the caller until that step's own socket timeout.
     * Abandoned work only reads, stops at its next suspension point and writes nothing here.
     *
     * The new sign-in's lookup may download the web player bundle after Spotify changes the
     * `profileAttributes` hash. Nothing else would at that moment: an expired session's sync stops
     * at the health probe, so a fresh process has no scraped hash when Reconnect is tapped, and
     * without one the guard is off exactly when it matters. It only happens on a miss, the budget
     * bounds the wait, and an abandoned download still fills the cache for the next try. The
     * connected account's lookup reuses that cache, so it never downloads.
     *
     * The connected account is the recorded one; otherwise (signed in before Stash recorded it) it
     * is asked of Spotify with the stored cookie, which works when the session wasn't really dead.
     * An unidentified sign-in can't be refused, so that isn't asked then. The sign-ins are never
     * saved here: a refused sign-in must leave the stored one exactly as it was. The one write is
     * whose the stored sign-in is, once Spotify has said so with that sign-in's own cookie
     * ([recordMainAccountLearned]): what [rememberSpotifyAccount] would record, learned sooner.
     *
     * Logs (never who) why a sign-in will go unchecked: a rotated Spotify hash turns the guard off
     * for everyone, and a diagnostics bundle should show it.
     */
    private suspend fun identifySpotifyAccounts(
        accessToken: String,
        clientId: String,
        logAs: String = "connectSpotifyWithCookie",
        unchecked: String = "saved unchecked",
    ): SpotifySignInAccounts {
        val stored = tokenStore.readSpotifySignIn()
        var incoming: SpotifyProfile? = null
        var connected: SpotifyProfile? = stored.account.takeIf { stored.token != null }
        val work = CoroutineScope(currentCoroutineContext().minusKey(Job)).async {
            incoming = spotifyAuthManager.fetchProfile(accessToken, clientId, rescrapeOnMiss = true)
            if (incoming != null && connected == null && stored.token != null) {
                connected = askSpotifyWhoIs(stored.token)
            }
        }
        val inTime = try {
            withTimeoutOrNull(SPOTIFY_IDENTIFY_BUDGET_MS) { work.await() } != null
        } finally {
            work.cancel()
        }
        val found = SpotifySignInAccounts(incoming, connected)
        if (stored.token != null && stored.account == null) recordMainAccountLearned(stored.token, found.connected)
        if (!inTime) Log.w("StashSync", "$logAs: identifying the accounts timed out")
        when {
            found.incoming == null ->
                Log.w("StashSync", "$logAs: $unchecked, new sign-in unidentified")
            stored.token != null && found.connected == null ->
                Log.w("StashSync", "$logAs: $unchecked, connected account unknown")
        }
        return found
    }

    /**
     * Records [learned] as whose the main account's stored sign-in [mainSignIn] is, after Spotify
     * named it with that sign-in's own cookie ([askSpotifyWhoIs]). An older install has no record
     * until [rememberSpotifyAccount] succeeds; meanwhile the account-swap guard has to ask Spotify
     * each time, and [saveSpotifyExtra] can't tell the main account apart. Written only while
     * [mainSignIn] is still the stored sign-in ([EncryptedTokenStore.saveSpotifyAccountFor]), never
     * while an extra account acts (its context names an extra, not the main), and best-effort: a
     * failed write costs the record, never the sign-in in progress.
     */
    private suspend fun recordMainAccountLearned(mainSignIn: ServiceToken, learned: SpotifyProfile?) {
        if (learned == null || actingExtra() != null) return
        try {
            val written = tokenStore.saveSpotifyAccountFor(mainSignIn.refreshToken, learned)
            Log.d("StashSync", "Spotify main account learned from Spotify: recorded=$written")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("StashSync", "couldn't record the Spotify main account: ${e.javaClass.simpleName}")
        }
    }

    /** Whose [stored] sign-in is, asked of Spotify with its cookie; null when it can't say. */
    private suspend fun askSpotifyWhoIs(stored: ServiceToken): SpotifyProfile? {
        val fresh = spotifyAuthManager.getAccessToken(stored.refreshToken) ?: return null
        val clientId = fresh.scope.split(SpotifyAuthManager.SCOPE_DELIMITER).getOrNull(1) ?: return null
        return spotifyAuthManager.fetchProfile(fresh.accessToken, clientId, rescrapeOnMiss = false)
    }

    /**
     * When [rememberSpotifyAccount] last looked up. A failed lookup is tried again after
     * [SPOTIFY_ACCOUNT_RETRY_MS]: one network failure mustn't leave the account unrecorded for the
     * life of a process that can run for days, nor may a lasting failure hit Spotify every sync.
     */
    @Volatile
    private var spotifyAccountLookupAtMs = 0L

    /** The clock behind [spotifyAccountLookupAtMs]; a seam for tests. */
    internal var nowMs: () -> Long = System::currentTimeMillis

    override suspend fun rememberSpotifyAccount() {
        // The main account's record only: an extra's session working says nothing about the main's.
        if (actingExtra() != null) return
        val now = nowMs()
        if (now - spotifyAccountLookupAtMs < SPOTIFY_ACCOUNT_RETRY_MS) return
        val stored = tokenStore.readSpotifySignIn()
        val token = stored.token ?: return
        if (stored.account != null) return
        spotifyAccountLookupAtMs = now
        val accessToken = if (token.isExpired || token.isExpiringSoon) {
            getSpotifyAccessToken() ?: return
        } else {
            token.accessToken
        }
        val clientId = token.scope.split(SpotifyAuthManager.SCOPE_DELIMITER).getOrNull(1)
            ?.takeIf { it.isNotEmpty() } ?: return
        val account = spotifyAuthManager.fetchProfile(accessToken, clientId, rescrapeOnMiss = true)
        if (account == null) {
            Log.w("StashSync", "rememberSpotifyAccount: connected account still unknown, Spotify didn't say")
            return
        }
        // Only for the sign-in it was looked up with: a sign-out or new sign-in meanwhile wins.
        val written = tokenStore.saveSpotifyAccountFor(token.refreshToken, account)
        Log.d("StashSync", "rememberSpotifyAccount: recorded=$written")
    }

    /** The two accounts a Spotify sign-in is judged on; null is "can't be told". */
    private data class SpotifySignInAccounts(val incoming: SpotifyProfile?, val connected: SpotifyProfile?)

    // -- Extra Spotify accounts (plan 2026-11-04 §2.2) -------------------------
    //
    // THE NO-FALLBACK RULE lives here. [actingExtra] picks the account; every per-account read
    // above branches on it first, and the extra branch only ever reads [extraSignIn]: the extra's
    // own blob, and only when it is the row's account. A missing, unreadable, dead or other
    // account's sign-in answers null. Nothing in this section reads `spotifyToken`, the main's.

    /** The extra account the calling coroutine acts as ([SpotifyAccount]), or null for the main account. */
    private suspend fun actingExtra(): SpotifyAccount? = currentCoroutineContext()[SpotifyAccount]

    /** The acting account's stored sign-in: the extra's when one is acting (null when it has none), else the main's. */
    private suspend fun actingSignIn(): ServiceToken? {
        val extra = actingExtra() ?: return tokenStore.spotifyToken.first()
        return extraSignIn(extra)?.token
    }

    /**
     * [account]'s stored sign-in, or null when there is none, or when it is ANOTHER Spotify
     * account's: the sign-in is kept under the row id outside the database, and a restored backup
     * can give that row id to someone else. Treated as signed out, never used.
     */
    private suspend fun extraSignIn(account: SpotifyAccount): StoredSpotifySignIn? {
        val stored = tokenStore.readSpotifyExtraSignIn(account.rowId)
        if (stored.token == null) return null
        if (!account.isAccount(stored.account)) {
            Log.w("StashSync", "Spotify extra account #${account.rowId}: its stored sign-in is another account's, not used")
            return null
        }
        return stored
    }

    /**
     * One refresh lock per extra account, beside the main's [spotifyRefreshLock]: an extra's slow
     * or failing refresh must never hold up the main's (likes, search, the main's sync) nor
     * another extra's. Never removed: there are a handful of extras at most.
     */
    private val extraRefreshLocks = ConcurrentHashMap<Long, Mutex>()

    private fun extraRefreshLock(rowId: Long): Mutex = extraRefreshLocks.getOrPut(rowId) { Mutex() }

    override suspend fun getSpotifyExtraAccessToken(account: SpotifyAccount): String? {
        val token = extraSignIn(account)?.token ?: return null
        if (!token.isExpired && !token.isExpiringSoon) return token.accessToken
        // Single-flight per account, as the main's: whoever waited re-reads and uses what the first saved.
        return extraRefreshLock(account.rowId).withLock {
            val current = extraSignIn(account)?.token ?: return@withLock null
            if (!current.isExpired && !current.isExpiringSoon) return@withLock current.accessToken
            refreshExtra(account, current)
        }
    }

    override suspend fun forceRefreshSpotifyExtraAccessToken(account: SpotifyAccount): String? =
        extraRefreshLock(account.rowId).withLock {
            val current = extraSignIn(account)?.token ?: return@withLock null
            refreshExtra(account, current)
        }

    /**
     * Refreshes [account] from [current]'s cookie and saves it, only while that is still its
     * stored sign-in (a removal or new sign-in meanwhile wins, as [savedRefresh] does for the
     * main). Null when the cookie no longer works: a dead extra, which its sync skips.
     */
    private suspend fun refreshExtra(account: SpotifyAccount, current: ServiceToken): String? {
        val spDcCookie = current.refreshToken
        if (spDcCookie.isEmpty()) return null
        val refreshed = spotifyAuthManager.refreshAccessToken(spDcCookie) ?: return null
        if (tokenStore.saveRefreshedSpotifyExtraToken(account.rowId, spDcCookie, refreshed)) return refreshed.accessToken
        Log.i("StashSync", "Spotify extra account token refresh dropped: its sign-in changed meanwhile")
        return extraSignIn(account)?.token?.takeIf { !it.isExpired }?.accessToken
    }

    override suspend fun hasSpotifyExtraSignIn(account: SpotifyAccount): Boolean = extraSignIn(account) != null

    override suspend fun clearSpotifyExtra(rowId: Long) {
        // No lock needed: a refresh in flight saves only over the sign-in it refreshed, and there is none now.
        tokenStore.clearSpotifyExtra(rowId)
    }

    /**
     * Checks [spDcCookie] with Spotify and identifies its account, within the same budget as the
     * account-swap guard ([identifySpotifyAccounts]), comparing it with the main account. Writes
     * nothing; the token it got travels in the result to [saveSpotifyExtra].
     */
    override suspend fun validateSpotifyCookie(spDcCookie: String): SpotifyCookieCheck {
        if (spDcCookie.isBlank()) return SpotifyCookieCheck.Invalid
        val token = spotifyAuthManager.getAccessToken(spDcCookie) ?: return SpotifyCookieCheck.Invalid
        val clientId = token.scope.split(SpotifyAuthManager.SCOPE_DELIMITER).getOrNull(1).orEmpty()
        val (incoming, main) = identifySpotifyAccounts(
            token.accessToken,
            clientId,
            logAs = "validateSpotifyCookie",
            unchecked = "unchecked",
        )
        val isMainAccount = if (incoming != null && main != null) main.isSameAccount(incoming) else null
        Log.d("StashSync", "validateSpotifyCookie: identified=${incoming != null}, isMain=$isMainAccount")
        return SpotifyCookieCheck.Valid(ValidatedSpotifySignIn(token, incoming), isMainAccount)
    }

    override suspend fun saveSpotifyExtra(account: SpotifyAccount, signIn: ValidatedSpotifySignIn): Boolean {
        val identified = signIn.account
        if (identified == null || !account.isAccount(identified)) {
            Log.w("StashSync", "saveSpotifyExtra: refused, the sign-in isn't the account its row is for")
            return false
        }
        // The main account's recorded account: on an older install, the one validateSpotifyCookie
        // just learned from Spotify and recorded ([recordMainAccountLearned]) counts.
        val main = tokenStore.readSpotifySignIn().account
        if (main != null && main.isSameAccount(identified)) {
            Log.w("StashSync", "saveSpotifyExtra: refused, that is the main account")
            return false
        }
        tokenStore.saveSpotifyExtra(account.rowId, signIn.token, identified)
        return true
    }

    private companion object {
        /** The most a sign-in spends finding out whose accounts are involved (see [identifySpotifyAccounts]). */
        const val SPOTIFY_IDENTIFY_BUDGET_MS = 20_000L

        /** How soon a failed [rememberSpotifyAccount] lookup is tried again. */
        const val SPOTIFY_ACCOUNT_RETRY_MS = 3 * 60 * 60 * 1000L
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
