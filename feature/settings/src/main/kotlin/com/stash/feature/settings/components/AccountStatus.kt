package com.stash.feature.settings.components

import com.stash.core.auth.model.AuthState

/** How an [AccountConnectionCard]'s status line is coloured. */
enum class AccountStatusTone { CONNECTED, EXPIRED, ERROR, NEUTRAL }

/** The status line under an account's name, and which action leads. */
internal data class AccountStatus(
    val text: String,
    val tone: AccountStatusTone,
    /** True when the card leads with Reconnect (Disconnect stays, secondary). */
    val offerReconnect: Boolean = false,
)

/**
 * What an account card says about [authState]. [expired] is the last sync's
 * probe verdict for this source: the credential is still stored (so the auth
 * state reads Connected) but the service rejected it. "Connected as X" with
 * only a red Disconnect was the wrong thing to show then: the listener's next
 * step is to sign in again, so the card says so and leads with Reconnect.
 *
 * The flag only counts while Connected. A stale flag on a source that has
 * since been disconnected (or is mid sign-in) must not override what the card
 * otherwise shows.
 */
internal fun accountStatus(authState: AuthState, expired: Boolean): AccountStatus = when (authState) {
    is AuthState.Connected -> {
        val name = authState.user.displayName
        if (expired) {
            AccountStatus(
                text = if (name.isBlank()) {
                    "Session expired. Reconnect to keep syncing."
                } else {
                    "Session expired for $name. Reconnect to keep syncing."
                },
                tone = AccountStatusTone.EXPIRED,
                offerReconnect = true,
            )
        } else {
            AccountStatus("Connected as $name", AccountStatusTone.CONNECTED)
        }
    }
    is AuthState.Connecting -> AccountStatus("Connecting...", AccountStatusTone.NEUTRAL)
    is AuthState.Error -> AccountStatus(authState.message, AccountStatusTone.ERROR)
    AuthState.NotConnected -> AccountStatus("Not connected", AccountStatusTone.NEUTRAL)
}
