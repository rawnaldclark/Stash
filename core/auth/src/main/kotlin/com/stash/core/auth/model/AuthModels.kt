package com.stash.core.auth.model

import java.time.Instant

/**
 * Holds OAuth tokens for a connected streaming service.
 *
 * @property accessToken  Short-lived bearer token used to authenticate API requests.
 * @property refreshToken Long-lived token used to obtain a new [accessToken] when the current one expires.
 * @property expiresAtEpoch Absolute expiration time in epoch seconds.
 * @property scope OAuth scope string granted by the authorization server.
 */
data class ServiceToken(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpoch: Long,
    val scope: String = "",
) {
    /** Whether the token has already expired. */
    val isExpired: Boolean
        get() = Instant.now().epochSecond >= expiresAtEpoch

    /** Whether the token will expire within the next 5 minutes. */
    val isExpiringSoon: Boolean
        get() = Instant.now().epochSecond >= expiresAtEpoch - EXPIRY_BUFFER_SECONDS

    private companion object {
        const val EXPIRY_BUFFER_SECONDS = 300L
    }
}

/**
 * Basic profile information for the authenticated user on a streaming service.
 */
data class UserInfo(
    val id: String,
    val displayName: String,
    val imageUrl: String? = null,
)

/** What became of a Spotify sign-in handed to `TokenManager.connectSpotifyWithCookie`. */
sealed interface SpotifyConnectResult {
    /** Saved: a first sign-in, or the connected account signing in again (an expired session). */
    data object Connected : SpotifyConnectResult

    /** The cookie gave no signed-in session (invalid, expired, or Spotify refused). Nothing saved. */
    data object Invalid : SpotifyConnectResult

    /**
     * A different Spotify account than the connected one. Nothing saved: the connected account's
     * sign-in, and so its library, stay exactly as they were. Saving it would have replaced that
     * account silently, and in Refresh mode the next sync would hide its whole library.
     * Switching accounts stays deliberate: sign out first, then sign in.
     *
     * @property connectedName The connected account's name, never blank.
     * @property attemptedName The new sign-in's account name, never blank. Can equal
     *   [connectedName]: two accounts may share a display name.
     */
    data class DifferentAccount(val connectedName: String, val attemptedName: String) : SpotifyConnectResult
}

/**
 * A Spotify sign-in Spotify accepted but Stash has NOT saved ([com.stash.core.auth.TokenManager.validateSpotifyCookie]),
 * carried to [com.stash.core.auth.TokenManager.saveSpotifyExtra] so saving it needs no second (TOTP-guarded,
 * rate-limited) token exchange. Only core:auth can make one, so a saved extra always went through validation.
 *
 * It holds a live credential: [toString] says nothing about it, and it should live no longer than the
 * add-an-account flow that asked for it.
 *
 * @property account Whose account the cookie signs in to, as Spotify's own profile names it. Null when
 *   Spotify didn't say in time; such a sign-in can't be saved as an extra (its rows are tagged by this id).
 * @property connected The main (connected) account as the check found it: null when none was connected
 *   or it couldn't be told. [com.stash.core.auth.TokenManager.connectSpotifyWithSignIn]'s account-swap
 *   guard judges by it, as [com.stash.core.auth.TokenManager.connectSpotifyWithCookie]'s judges by its own
 *   lookup.
 */
class ValidatedSpotifySignIn internal constructor(
    internal val token: ServiceToken,
    val account: com.stash.core.auth.spotify.SpotifyProfile?,
    internal val connected: com.stash.core.auth.spotify.SpotifyProfile? = null,
) {
    override fun toString(): String = "ValidatedSpotifySignIn(identified=${account != null})"
}

/** What checking a Spotify cookie without saving it found ([com.stash.core.auth.TokenManager.validateSpotifyCookie]). */
sealed interface SpotifyCookieCheck {
    /** The cookie gave no signed-in session (invalid, expired, or Spotify refused). */
    data object Invalid : SpotifyCookieCheck

    /**
     * A working sign-in, not saved.
     *
     * @property signIn What [com.stash.core.auth.TokenManager.saveSpotifyExtra] saves.
     * @property isMainAccount True when it is the main (connected) account, false when it is another account,
     *   null when either can't be told (Spotify didn't say in time, or no main account is connected).
     */
    data class Valid(val signIn: ValidatedSpotifySignIn, val isMainAccount: Boolean?) : SpotifyCookieCheck {
        /** Whose account it is; null when Spotify didn't say (see [ValidatedSpotifySignIn.account]). */
        val account: com.stash.core.auth.spotify.SpotifyProfile? get() = signIn.account
    }
}

/** Streaming services supported by Stash. */
enum class AuthService { SPOTIFY, YOUTUBE_MUSIC, DISCORD, SOUNDCLOUD }

/** Represents the authentication lifecycle for a given [AuthService]. */
sealed class AuthState {
    data object NotConnected : AuthState()
    data object Connecting : AuthState()
    data class Connected(val user: UserInfo) : AuthState()
    data class Error(val message: String) : AuthState()
}

/**
 * State for the OAuth 2.0 Device Authorization Grant flow (used by YouTube Music on TV / limited-input devices).
 *
 * @property deviceCode  Server-issued device code exchanged for tokens after user approval.
 * @property userCode    Short code displayed to the user for entry at [verificationUrl].
 * @property verificationUrl URL the user must visit to approve the device.
 * @property expiresAtEpoch  Absolute expiration time (epoch seconds) after which the codes are invalid.
 * @property intervalSeconds Minimum polling interval (seconds) when checking for user approval.
 */
data class DeviceCodeState(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresAtEpoch: Long,
    val intervalSeconds: Int,
)

/**
 * Which profile of the signed-in Google account YouTube Music requests act as (#664). A Google
 * account can own Brand Account profiles, each its own library; [pageId] is a brand profile's
 * 21-digit id (null = the account's own profile) and [authUser] the index of the Google account in
 * the browser session that owns it (`X-Goog-AuthUser`: a page id is only valid with its owner's).
 *
 * Kept in the YouTube token's scope field ([encode] / [decode]), so a new sign-in starts over and
 * a disconnect forgets it, like the rest of the sign-in.
 */
data class YouTubeIdentity(val pageId: String? = null, val authUser: Int = 0) {
    fun encode(): String = listOfNotNull(pageId?.let { "pageId=$it" }, "authuser=$authUser").joinToString(";")

    companion object {
        fun decode(scope: String): YouTubeIdentity {
            val fields = scope.split(';').mapNotNull { part ->
                part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() }
            }.toMap()
            return YouTubeIdentity(
                pageId = fields["pageId"]?.takeIf { id -> id.isNotEmpty() && id.all(Char::isDigit) },
                authUser = fields["authuser"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            )
        }
    }
}
