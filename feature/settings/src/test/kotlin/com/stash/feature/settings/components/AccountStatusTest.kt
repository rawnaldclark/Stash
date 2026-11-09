package com.stash.feature.settings.components

import android.content.res.Resources
import com.google.common.truth.Truth.assertThat
import com.stash.core.auth.model.AuthState
import com.stash.core.auth.model.UserInfo
import com.stash.feature.settings.R
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

class AccountStatusTest {

    private val res = mockk<Resources> {
        every { getString(R.string.settings_connected_as, any()) } answers { "Connected as ${(args[1] as Array<*>)[0]}" }
        every { getString(R.string.settings_account_session_expired) } returns "Session expired. Reconnect to keep syncing."
        every { getString(R.string.settings_account_session_expired_for, any()) } answers { "Session expired for ${(args[1] as Array<*>)[0]}. Reconnect to keep syncing." }
        every { getString(R.string.settings_account_connecting) } returns "Connecting..."
        every { getString(R.string.settings_account_not_connected) } returns "Not connected"
    }

    private val connected = AuthState.Connected(UserInfo(id = "u1", displayName = "Ronald"))

    @Test fun `a healthy connection reads Connected as`() {
        val status = accountStatus(connected, expired = false, res)

        assertThat(status.text).isEqualTo("Connected as Ronald")
        assertThat(status.tone).isEqualTo(AccountStatusTone.CONNECTED)
        assertThat(status.offerReconnect).isFalse()
    }

    @Test fun `an expired session says so and leads with Reconnect`() {
        val status = accountStatus(connected, expired = true, res)

        assertThat(status.text).isEqualTo("Session expired for Ronald. Reconnect to keep syncing.")
        assertThat(status.tone).isEqualTo(AccountStatusTone.EXPIRED)
        assertThat(status.offerReconnect).isTrue()
    }

    @Test fun `an expired session without a display name drops the name`() {
        val status = accountStatus(AuthState.Connected(UserInfo(id = "u1", displayName = " ")), expired = true, res)

        assertThat(status.text).isEqualTo("Session expired. Reconnect to keep syncing.")
    }

    @Test fun `a stale expired flag does not override a disconnected account`() {
        val status = accountStatus(AuthState.NotConnected, expired = true, res)

        assertThat(status.text).isEqualTo("Not connected")
        assertThat(status.offerReconnect).isFalse()
    }

    @Test fun `a stale expired flag does not override a sign-in in progress or an error`() {
        assertThat(accountStatus(AuthState.Connecting, expired = true, res).offerReconnect).isFalse()

        val error = accountStatus(AuthState.Error("Couldn't reach Spotify"), expired = true, res)
        assertThat(error.text).isEqualTo("Couldn't reach Spotify")
        assertThat(error.tone).isEqualTo(AccountStatusTone.ERROR)
        assertThat(error.offerReconnect).isFalse()
    }
}
