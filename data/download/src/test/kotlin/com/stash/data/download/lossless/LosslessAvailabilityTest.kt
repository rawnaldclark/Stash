package com.stash.data.download.lossless

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.lossless.qbdlx.QbdlxCredentialStore
import com.stash.data.download.lossless.quota.LosslessQuota
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import com.stash.data.download.lossless.relay.LosslessRelayClient
import com.stash.data.download.lossless.relay.RelayEntry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LosslessAvailabilityTest {
    private val login = MutableStateFlow(false)
    private val configRelays = MutableStateFlow<List<RelayEntry>>(emptyList())
    private val custom = MutableStateFlow<String?>(null)
    private val loginEmail = MutableStateFlow<String?>(null)
    private val store: QbdlxCredentialStore = mockk {
        every { hasLogin } returns login
        every { connectedEmailFlow } returns loginEmail
    }
    private val config: LosslessConfigFetcher = mockk { every { relays } returns configRelays }
    private val prefs: LosslessSourcePreferences = mockk { every { customLosslessEndpoint } returns custom }
    private val relayClient: LosslessRelayClient = mockk()
    private var now = 1_000_000_000_000L
    private val quota = LosslessQuota.inMemory { now }
    private val a = LosslessAvailability(store, config, prefs, relayClient, quota)

    private fun stubNow(loginLive: Boolean = false, customNow: String? = null, cooled: Set<String> = emptySet()) {
        coEvery { store.loginLive() } returns loginLive
        coEvery { prefs.customLosslessEndpointNow() } returns customNow
        every { relayClient.isCooled(any()) } answers { firstArg<String>() in cooled }
    }

    @Test fun `a spent day's allowance leaves the config relays out of fileUrlAvailableNow, not a live login`() = runTest {
        configRelays.value = listOf(RelayEntry("https://r1"))
        stubNow()
        assertThat(a.fileUrlAvailableNow()).isTrue()
        quota.onHeaders("500/500", "${now / 1000 + 3600}")
        assertThat(a.fileUrlAvailableNow()).isFalse()
        stubNow(loginLive = true)
        assertThat(a.fileUrlAvailableNow()).isTrue()
        now += 3_600_000 // the reset
        stubNow()
        assertThat(a.fileUrlAvailableNow()).isTrue()
    }

    @Test fun `nothing configured - every predicate false`() = runTest {
        stubNow()
        assertThat(a.qbdlxEnabledNow()).isFalse()
        assertThat(a.fileUrlAvailableNow()).isFalse()
        assertThat(a.anyUserOwnedNow()).isFalse()
    }

    @Test fun `relay configured - enabled, but not user-owned`() = runTest {
        configRelays.value = listOf(RelayEntry("https://r.example", 1)); stubNow()
        assertThat(a.qbdlxEnabledNow()).isTrue()
        assertThat(a.fileUrlAvailableNow()).isTrue()
        assertThat(a.anyUserOwnedNow()).isFalse()
    }

    @Test fun `ownAccountLiveNow is the live login and nothing else`() = runTest {
        // A configured relay is a shared, capped budget — it must never count as "own".
        configRelays.value = listOf(RelayEntry("https://r.example", 1)); stubNow(loginLive = false)
        assertThat(a.ownAccountLiveNow()).isFalse()
        stubNow(loginLive = true)
        assertThat(a.ownAccountLiveNow()).isTrue()
    }

    @Test fun `a cooled relay is still configured but not available now`() = runTest {
        configRelays.value = listOf(RelayEntry("https://r.example", 1)); stubNow(cooled = setOf("https://r.example"))
        assertThat(a.qbdlxEnabledNow()).isTrue()
        assertThat(a.fileUrlAvailableNow()).isFalse()
    }

    @Test fun `BYO login is user-owned and available`() = runTest {
        login.value = true; stubNow(loginLive = true)
        assertThat(a.fileUrlAvailableNow()).isTrue()
        assertThat(a.anyUserOwnedNow()).isTrue()
    }

    @Test fun `a dead-cooled login is still enabled but not available now`() = runTest {
        login.value = true; stubNow(loginLive = false)
        assertThat(a.qbdlxEnabledNow()).isTrue()
        assertThat(a.fileUrlAvailableNow()).isFalse()
    }

    @Test fun `custom endpoint is user-owned - cooled custom is not available`() = runTest {
        custom.value = "https://mine.example"; stubNow(customNow = "https://mine.example", cooled = setOf("https://mine.example"))
        assertThat(a.anyUserOwnedNow()).isTrue()
        assertThat(a.qbdlxEnabledNow()).isTrue()
        assertThat(a.fileUrlAvailableNow()).isFalse()
    }

    // ── routingRows: what Settings › Audio renders ──────────────────────────

    private suspend fun rows() = a.routingRows.first()

    private suspend fun row(id: String) = rows().single { it.id == id }

    @Test fun `nothing configured - qobuz and relay rows are not configured`() = runTest {
        assertThat(row("qobuz").state).isEqualTo(RoutingState.NOT_CONFIGURED)
        assertThat(row("qobuz").detail).isEqualTo("not connected")
        assertThat(row("relay").state).isEqualTo(RoutingState.NOT_CONFIGURED)
        assertThat(row("relay").detail).isEqualTo("not configured")
        assertThat(rows().map { it.id }).containsExactly("qobuz", "relay").inOrder()
    }

    @Test fun `a connected account shows its email`() = runTest {
        login.value = true; loginEmail.value = "me@example.com"
        assertThat(row("qobuz").state).isEqualTo(RoutingState.CONNECTED)
        assertThat(row("qobuz").detail).isEqualTo("me@example.com")
    }

    @Test fun `a migrated token has no email but is still connected`() = runTest {
        login.value = true; loginEmail.value = null
        assertThat(row("qobuz").state).isEqualTo(RoutingState.CONNECTED)
        assertThat(row("qobuz").detail).isEqualTo("connected (token)")
    }

    @Test fun `a config relay makes the relay row configured`() = runTest {
        configRelays.value = listOf(RelayEntry("https://r.example", 1))
        assertThat(row("relay").state).isEqualTo(RoutingState.CONFIGURED)
        assertThat(row("relay").detail).isEqualTo("configured")
    }

    @Test fun `a custom endpoint adds its own row - absent otherwise`() = runTest {
        assertThat(rows().map { it.id }).doesNotContain("custom")
        custom.value = "https://mine.example"
        assertThat(row("custom").state).isEqualTo(RoutingState.CONFIGURED)
        assertThat(rows().map { it.id }).containsExactly("qobuz", "relay", "custom").inOrder()
    }
}
