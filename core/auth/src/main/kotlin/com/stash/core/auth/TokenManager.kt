package com.stash.core.auth

import com.stash.core.auth.model.AuthService
import com.stash.core.auth.model.AuthState
import com.stash.core.auth.model.ServiceToken
import com.stash.core.auth.model.UserInfo
import kotlinx.coroutines.flow.StateFlow

/**
 * Central interface for managing OAuth tokens across all connected streaming services.
 *
 * Implementations observe encrypted token storage and expose reactive [AuthState] flows
 * for each service so that UI layers can respond to authentication changes immediately.
 */
interface TokenManager {

    /** Current authentication state for Spotify. */
    val spotifyAuthState: StateFlow<AuthState>

    /** Current authentication state for YouTube Music. */
    val youTubeAuthState: StateFlow<AuthState>

    /**
     * Returns a valid Spotify access token, or null if the user is not authenticated
     * or the token has expired / is expiring soon.
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
     * Returns a valid YouTube Music access token, or null if the user is not authenticated
     * or the token has expired / is expiring soon.
     */
    suspend fun getYouTubeAccessToken(): String?

    /** Persists Spotify credentials and user profile information. */
    suspend fun saveSpotifyAuth(token: ServiceToken, user: UserInfo)

    /** Persists YouTube Music OAuth credentials and user profile information. */
    suspend fun saveYouTubeAuth(token: ServiceToken, user: UserInfo)

    /** Removes all stored credentials for the given [service]. */
    suspend fun clearAuth(service: AuthService)

    /** Returns true if valid (non-expired) credentials exist for the given [service]. */
    suspend fun isAuthenticated(service: AuthService): Boolean

    /**
     * Validates an sp_dc cookie against the Spotify web-player token endpoint,
     * fetches the user profile, and persists all credentials on success.
     *
     * @param spDcCookie The raw sp_dc cookie value from the user's browser.
     * @return true if the cookie was valid and auth was saved, false otherwise.
     */
    suspend fun connectSpotifyWithCookie(spDcCookie: String, username: String = ""): Boolean
}
