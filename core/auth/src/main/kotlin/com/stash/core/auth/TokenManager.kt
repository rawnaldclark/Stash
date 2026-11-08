package com.stash.core.auth

import com.stash.core.auth.model.AuthService
import com.stash.core.auth.model.AuthState
import com.stash.core.auth.model.ServiceToken
import com.stash.core.auth.model.SpotifyConnectResult
import com.stash.core.auth.model.SpotifyCookieCheck
import com.stash.core.auth.model.UserInfo
import com.stash.core.auth.model.ValidatedSpotifySignIn
import kotlinx.coroutines.flow.StateFlow

/**
 * Central interface for managing OAuth tokens across all connected streaming services.
 *
 * Implementations observe encrypted token storage and expose reactive [AuthState] flows
 * for each service so that UI layers can respond to authentication changes immediately.
 */
interface TokenManager {
    /** Atomic account and credential record; null token inside it means public likes only. */
    val soundCloudSession: kotlinx.coroutines.flow.Flow<com.stash.core.auth.soundcloud.SoundCloudSession?>

    suspend fun connectSoundCloudWithToken(token: String): Boolean

    suspend fun connectSoundCloudPublicProfile(url: String): Boolean

    suspend fun getSoundCloudToken(): String?

    suspend fun hasSoundCloudConnection(): Boolean

    /** Current authentication state for Spotify. */
    val spotifyAuthState: StateFlow<AuthState>

    /** Current authentication state for YouTube Music. */
    val youTubeAuthState: StateFlow<AuthState>

    /**
     * Returns a valid Spotify access token, or null if the user is not authenticated
     * or the token has expired / is expiring soon.
     *
     * **Acting account.** This and every other per-account Spotify read here
     * ([forceRefreshSpotifyAccessToken], [getSpotifyUsername], [getSpDcCookie],
     * [getSpotifyClientId], `isAuthenticated(SPOTIFY)`) answer for the extra account named by a
     * [com.stash.core.auth.spotify.SpotifyAccount] in the caller's coroutine context, and for the
     * main account when there is none (unchanged). With an extra set, none of them ever answers
     * with the main account's sign-in. What each answers for the extra:
     *  - **No sign-in of its own** (none stored, unreadable after a keyset reset, or one stored
     *    under its row that is another account's): every one of them answers null, and
     *    `isAuthenticated(SPOTIFY)` false.
     *  - **A stored sign-in that has died** (its cookie no longer works on Spotify's side): only
     *    this and [forceRefreshSpotifyAccessToken] answer null, once the refresh fails. The
     *    cookie, username and client id still answer with the extra's own stored values, and
     *    `isAuthenticated(SPOTIFY)` true: a stored cookie can't be known dead without a refresh.
     *
     * So whether an extra's sync pass runs is decided by
     * [getSpotifyExtraAccessToken]`(account) != null` (or this, acting as it), never by
     * `isAuthenticated(SPOTIFY)` or [hasSpotifyExtraSignIn], which only say a sign-in is stored.
     */
    suspend fun getSpotifyAccessToken(): String?

    /**
     * Forces a token refresh for Spotify regardless of whether the current token
     * is still valid. Useful when persistent 429 errors suggest the rate limit is
     * tied to the specific access token rather than the IP/account.
     *
     * @return A fresh access token, or null if refresh fails.
     */
    suspend fun forceRefreshSpotifyAccessToken(): String?

    /**
     * Returns the stored Spotify username from the token metadata.
     *
     * The username is extracted from the sp_dc token response and stored
     * in the [ServiceToken.scope] field. It is needed by the spclient
     * endpoints which require the user ID in the URL path.
     *
     * @return The Spotify username, or null if not available.
     */
    suspend fun getSpotifyUsername(): String?

    /**
     * Returns the stored sp_dc cookie value.
     *
     * The sp_dc cookie is the long-lived credential used to obtain
     * short-lived access tokens. It is also needed for fetching the
     * Spotify homepage to extract the user ID.
     *
     * @return The sp_dc cookie string, or null if not available.
     */
    suspend fun getSpDcCookie(): String?

    /**
     * Returns the stored Spotify client ID from the token metadata.
     *
     * The client ID is extracted from the sp_dc token response and stored
     * alongside the username in the [ServiceToken.scope] field. It is needed
     * by the GraphQL Partner API client token acquisition flow.
     *
     * @return The Spotify client ID, or null if not available.
     */
    suspend fun getSpotifyClientId(): String?

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

    /** Persists Spotify credentials and user profile information. */
    suspend fun saveSpotifyAuth(token: ServiceToken, user: UserInfo)

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

    /** Removes all stored credentials for the given [service]. */
    suspend fun clearAuth(service: AuthService)

    /**
     * Returns true if valid (non-expired) credentials exist for the given [service]. For Spotify,
     * the acting account's (see [getSpotifyAccessToken]).
     */
    suspend fun isAuthenticated(service: AuthService): Boolean

    /**
     * Validates an sp_dc cookie against the Spotify web-player token endpoint,
     * identifies whose account it is, and persists the credentials, unless a
     * DIFFERENT account is already connected: then nothing is saved and the
     * connected account is left untouched ([SpotifyConnectResult.DifferentAccount]).
     * The connected account signing in again (an expired session) saves as before.
     *
     * @param spDcCookie The raw sp_dc cookie value from the user's browser.
     * @param username Optional name the user typed in the paste dialog, shown as "Connected as".
     */
    suspend fun connectSpotifyWithCookie(spDcCookie: String, username: String = ""): SpotifyConnectResult

    /**
     * [connectSpotifyWithCookie] for a sign-in [validateSpotifyCookie] already checked: the same
     * account-swap guard (a different account than the connected one is refused, with nothing
     * written; one that can't be told is saved) and the same save, judged on the accounts that
     * check found, with NO second token exchange. Spotify rate-limits that exchange: a second one
     * right after the first can come back 429 and read as an invalid cookie. For a caller that had
     * to know whose the sign-in is before connecting it (Settings, while other Spotify accounts are
     * added). Never [SpotifyConnectResult.Invalid]: the check already found the session working.
     *
     * @param username As [connectSpotifyWithCookie]'s.
     */
    suspend fun connectSpotifyWithSignIn(signIn: ValidatedSpotifySignIn, username: String = ""): SpotifyConnectResult

    /**
     * Learns and records which Spotify account the stored sign-in belongs to, when that isn't
     * known yet (signed in before Stash recorded it). Without it, a later sign-in as another
     * account couldn't be told apart, so the account-swap guard couldn't refuse it. Called while
     * the session is known to work (the sync's auth probe). Best-effort: a failed lookup is tried
     * again a few hours later. The main account's only: acting as an extra, it does nothing (that
     * session working says nothing about the main's).
     */
    suspend fun rememberSpotifyAccount() = Unit

    // -- Extra Spotify accounts (plan 2026-11-04 §2.2) -----------------------------------------
    //
    // An extra is read-only: Stash reads its library, never writes to it. Each has a
    // `spotify_accounts` row (core:data) and one encrypted sign-in under that row's id, named
    // here by a [com.stash.core.auth.spotify.SpotifyAccount] (row id + the row's user id). A
    // stored sign-in that is another account's than the row's counts as none. The main
    // account's sign-in is untouched by all of these.

    /**
     * Checks an sp_dc cookie with Spotify and says whose account it is, saving NOTHING: the main
     * account's sign-in, every extra's, and the account record stay exactly as they were. For
     * adding an extra account or signing one in again; [SpotifyCookieCheck.Valid.isMainAccount]
     * tells the main account apart ("That's your main account").
     */
    suspend fun validateSpotifyCookie(spDcCookie: String): SpotifyCookieCheck

    /**
     * Saves [signIn] as the sign-in of the extra [account] (its `spotify_accounts` row), replacing
     * an older sign-in under that row. Refused, with nothing written, unless Spotify identified
     * the sign-in as the row's account and it is not the main account's recorded account: the
     * sign-in under a row must never act as anyone else.
     *
     * @return true when saved.
     */
    suspend fun saveSpotifyExtra(account: com.stash.core.auth.spotify.SpotifyAccount, signIn: ValidatedSpotifySignIn): Boolean

    /**
     * A valid access token of the extra [account], refreshed when due; null when it has no
     * working sign-in of its own (none, another account's, or one whose refresh fails). The test
     * for whether the account's sync pass runs.
     */
    suspend fun getSpotifyExtraAccessToken(account: com.stash.core.auth.spotify.SpotifyAccount): String?

    /** A new access token of the extra [account] whatever the current one's expiry; null when that fails. */
    suspend fun forceRefreshSpotifyExtraAccessToken(account: com.stash.core.auth.spotify.SpotifyAccount): String?

    /**
     * Whether the extra [account] has a stored sign-in of its own. It may still have stopped
     * working on Spotify's side: whether its sync runs is [getSpotifyExtraAccessToken]` != null`,
     * not this. False after a keyset reset, or when the row's sign-in is another account's (a
     * restored backup): either way the account must sign in again.
     */
    suspend fun hasSpotifyExtraSignIn(account: com.stash.core.auth.spotify.SpotifyAccount): Boolean

    /** Forgets the extra account [rowId]'s sign-in on this phone. The main account's and other extras' stay. */
    suspend fun clearSpotifyExtra(rowId: Long)
}
