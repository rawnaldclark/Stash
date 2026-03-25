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

/** Streaming services supported by Stash. */
enum class AuthService { SPOTIFY, YOUTUBE_MUSIC }

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
