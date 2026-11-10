package com.stash.core.auth

import com.stash.core.auth.model.AuthService
import com.stash.core.auth.model.AuthState
import kotlinx.coroutines.flow.StateFlow

/**
 * Central interface for managing OAuth tokens across all connected streaming services.
 *
 * Implementations observe encrypted token storage and expose reactive [AuthState] flows
 * for each service so that UI layers can respond to authentication changes immediately.
 *
 * **Spotify is not here.** Its sign-ins, tokens and accounts are
 * [com.stash.core.auth.spotify.SpotifyAccounts] (add-ons plan 2026-11-08 §4.3), so a Spotify add-on
 * can implement them later without touching this. [isAuthenticated] and [clearAuth] still accept
 * [AuthService.SPOTIFY] and answer exactly as before, but every Spotify caller goes through
 * `SpotifyAccounts` (`isSpotifyAuthenticated`, `clearSpotifyAuth`).
 */
interface TokenManager {
    /** Atomic account and credential record; null token inside it means public likes only. */
    val soundCloudSession: kotlinx.coroutines.flow.Flow<com.stash.core.auth.soundcloud.SoundCloudSession?>

    suspend fun connectSoundCloudWithToken(token: String): Boolean

    suspend fun connectSoundCloudPublicProfile(url: String): Boolean

    suspend fun getSoundCloudToken(): String?

    suspend fun hasSoundCloudConnection(): Boolean

    /** Current authentication state for YouTube Music. */
    val youTubeAuthState: StateFlow<AuthState>

    /**
     * Returns the stored YouTube Music cookie string for InnerTube authentication,
     * or null if the user has not connected YouTube Music.
     */
    suspend fun getYouTubeCookie(): String?

    /** Current authentication state for Discord Rich Presence. */
    val discordAuthState: StateFlow<AuthState>

    /**
     * Returns the stored Discord account token used to authenticate
     * headless-session Rich Presence requests, or null if not connected.
     */
    suspend fun getDiscordUserToken(): String?

    /**
     * Validates a Discord account token (scraped from the account's own web
     * session via DiscordLoginWebView — NOT a bot token) against the profile
     * endpoint and persists it + the profile on success.
     */
    suspend fun connectDiscordWithToken(token: String): Boolean

    /**
     * Validates and stores a YouTube Music cookie for InnerTube authentication.
     *
     * The cookie must contain a SAPISID or __Secure-3PAPISID value which is
     * used to generate SAPISIDHASH authorization headers.
     *
     * @param cookie The full cookie string from the user's browser session on music.youtube.com.
     * @return true if the cookie was valid and saved, false otherwise.
     */
    suspend fun connectYouTubeWithCookie(cookie: String): Boolean

    /** The YouTube profile requests act as (#664); the account's own when none was picked. */
    suspend fun getYouTubeIdentity(): com.stash.core.auth.model.YouTubeIdentity =
        com.stash.core.auth.model.YouTubeIdentity()

    /**
     * Records which profile of the signed-in Google account YouTube Music uses, with its name and
     * photo for "Connected as". No-op when YouTube Music isn't connected.
     */
    suspend fun setYouTubeProfile(
        identity: com.stash.core.auth.model.YouTubeIdentity,
        displayName: String,
        imageUrl: String?,
    ) = Unit

    /**
     * Removes all stored credentials for the given [service]. Spotify callers use
     * `SpotifyAccounts.clearSpotifyAuth()` (the same effect for [AuthService.SPOTIFY]).
     */
    suspend fun clearAuth(service: AuthService)

    /**
     * Returns true if valid (non-expired) credentials exist for the given [service]. Spotify
     * callers use `SpotifyAccounts.isSpotifyAuthenticated()` (the same answer for
     * [AuthService.SPOTIFY]: the acting account's).
     */
    suspend fun isAuthenticated(service: AuthService): Boolean
}
