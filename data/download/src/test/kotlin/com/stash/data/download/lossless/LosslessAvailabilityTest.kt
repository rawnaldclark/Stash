package com.stash.data.download.lossless

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.lossless.qbdlx.QbdlxCredentialStore
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import com.stash.data.download.lossless.relay.TidalRelay
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LosslessAvailabilityTest {
    private val login = MutableStateFlow(false)
    private val stashTidal = MutableStateFlow<TidalRelay?>(null)
    private val tidalServer = MutableStateFlow<String?>(null)
    private val loginEmail = MutableStateFlow<String?>(null)
    private val store: QbdlxCredentialStore = mockk {
        every { hasLogin } returns login
        every { connectedEmailFlow } returns loginEmail
    }
    private val config: LosslessConfigFetcher = mockk { every { tidalRelay } returns stashTidal }
    private val prefs: LosslessSourcePreferences = mockk {
        every { bitchordEndpoint } returns tidalServer
    }
    private val a = LosslessAvailability(store, config, com.stash.data.download.lossless.tidal.OwnTidalServer(com.stash.core.addons.NoUserAddons, prefs))

    private fun stubNow(loginLive: Boolean = false) {
        coEvery { store.loginLive() } returns loginLive
    }

    @Test fun `nothing configured - every predicate false`() = runTest {
        stubNow()
        assertThat(a.qbdlxEnabledNow()).isFalse()
        assertThat(a.fileUrlAvailableNow()).isFalse()
        assertThat(a.anyUserOwnedNow()).isFalse()
        assertThat(a.anySourceConfigured.first()).isFalse()
    }

    /** The shared Qobuz pool is gone: Stash's Tidal relay alone never turns the Qobuz rung on. */
    @Test fun `Stash's Tidal relay - a source is configured, but the Qobuz rung stays off`() = runTest {
        stashTidal.value = TidalRelay("https://tidal.stash.example", null); stubNow()
        assertThat(a.anySourceConfigured.first()).isTrue()
        assertThat(a.qbdlxEnabledNow()).isFalse()
        assertThat(a.fileUrlAvailableNow()).isFalse()
        assertThat(a.anyUserOwnedNow()).isFalse()
    }

    @Test fun `the user's own Tidal server counts as a configured source`() = runTest {
        tidalServer.value = "https://tidal.mine.example"
        assertThat(a.anySourceConfigured.first()).isTrue()
        assertThat(a.qbdlxEnabledNow()).isFalse()
    }

    /** Their own server never touches Stash's relay: no allowance, nothing for a supporter code to lift. */
    @Test fun `Stash's Tidal relay is in use only without a Tidal server of their own`() = runTest {
        assertThat(a.stashTidalRelayInUse.first()).isFalse() // no relay in the config
        stashTidal.value = TidalRelay("https://tidal.stash.example", null)
        assertThat(a.stashTidalRelayInUse.first()).isTrue()
        login.value = true // their own Qobuz account still falls back to Stash's Tidal
        assertThat(a.stashTidalRelayInUse.first()).isTrue()
        tidalServer.value = "https://tidal.example.org"
        assertThat(a.stashTidalRelayInUse.first()).isFalse()
    }

    @Test fun `ownAccountLiveNow is the live login and nothing else`() = runTest {
        stashTidal.value = TidalRelay("https://tidal.stash.example", null); stubNow(loginLive = false)
        assertThat(a.ownAccountLiveNow()).isFalse()
        stubNow(loginLive = true)
        assertThat(a.ownAccountLiveNow()).isTrue()
    }

    @Test fun `BYO login is user-owned, configured and available`() = runTest {
        login.value = true; stubNow(loginLive = true)
        assertThat(a.qbdlxEnabledNow()).isTrue()
        assertThat(a.fileUrlAvailableNow()).isTrue()
        assertThat(a.anyUserOwnedNow()).isTrue()
        assertThat(a.anySourceConfigured.first()).isTrue()
    }

    @Test fun `a dead-cooled login is still enabled but not available now`() = runTest {
        login.value = true; stubNow(loginLive = false)
        assertThat(a.qbdlxEnabledNow()).isTrue()
        assertThat(a.fileUrlAvailableNow()).isFalse()
    }

    // ── routingRows: what Settings › Lossless sources renders ───────────────

    private suspend fun rows() = a.routingRows.first()

    private suspend fun row(id: String) = rows().single { it.id == id }

    @Test fun `nothing configured - qobuz and tidal rows are not configured`() = runTest {
        assertThat(row("qobuz").state).isEqualTo(RoutingState.NOT_CONFIGURED)
        assertThat(row("qobuz").detail).isEqualTo("not connected")
        assertThat(row("tidal").state).isEqualTo(RoutingState.NOT_CONFIGURED)
        assertThat(row("tidal").detail).isEqualTo("not configured")
        assertThat(rows().map { it.id }).containsExactly("qobuz", "tidal").inOrder()
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

    @Test fun `Stash's Tidal relay makes the Tidal row configured`() = runTest {
        stashTidal.value = TidalRelay("https://tidal.stash.example", null)
        assertThat(row("tidal").label).isEqualTo("Tidal")
        assertThat(row("tidal").state).isEqualTo(RoutingState.CONFIGURED)
        assertThat(row("tidal").detail).isEqualTo("configured")
        assertThat(rows().map { it.id }).containsExactly("qobuz", "tidal").inOrder()
    }

    @Test fun `no retired rows - neither the custom endpoint nor the Qobuz relay`() = runTest {
        login.value = true
        stashTidal.value = TidalRelay("https://tidal.stash.example", null)
        assertThat(rows().map { it.id }).containsNoneOf("custom", "relay")
    }

    // ── The user's own Tidal server ─────────────────────────────────────────

    @Test fun `no Tidal server of their own - Qobuz leads`() = runTest {
        assertThat(a.ownTidalFirstNow(LosslessQualityTier.HI_RES)).isFalse()
    }

    @Test fun `own Tidal server and no own Qobuz - it leads above CD`() = runTest {
        tidalServer.value = "https://tidal.mine.example"
        stashTidal.value = TidalRelay("https://tidal.stash.example", null)
        assertThat(a.ownTidalFirstNow(LosslessQualityTier.HI_RES)).isTrue()
        assertThat(a.ownTidalFirstNow(LosslessQualityTier.MAX)).isTrue()
        // One Tidal row: the user's server replaces Stash's relay.
        assertThat(rows().map { it.id }).containsExactly("qobuz", "tidal").inOrder()
        assertThat(row("tidal").label).isEqualTo("Custom Tidal server")
        assertThat(row("tidal").state).isEqualTo(RoutingState.CONFIGURED)
        // The list can't follow the tier (it differs by network, and between streams and
        // downloads), so the row says where it leads.
        assertThat(row("tidal").detail).isEqualTo("first above CD")
        // Nothing of their own on the Qobuz side: that row still says so.
        assertThat(row("qobuz").state).isEqualTo(RoutingState.NOT_CONFIGURED)
    }

    /** Tidal has no quality setting and serves its best: at CD, Qobuz asks for the tier the user picked. */
    @Test fun `own Tidal server at CD quality - Qobuz keeps the lead`() = runTest {
        tidalServer.value = "https://tidal.mine.example"
        assertThat(a.ownTidalFirstNow(LosslessQualityTier.CD)).isFalse()
    }

    @Test fun `own Tidal server and own Qobuz login - Qobuz keeps the lead`() = runTest {
        tidalServer.value = "https://tidal.mine.example"
        login.value = true
        assertThat(a.ownTidalFirstNow(LosslessQualityTier.HI_RES)).isFalse()
        assertThat(rows().map { it.id }).containsExactly("qobuz", "tidal").inOrder()
        assertThat(row("tidal").detail).isEqualTo("configured")
    }

    /** BitChord's own form carries the server's ACCESS_TOKEN as the path; Settings and diagnostics print rows. */
    @Test fun `the Tidal row never shows the server address`() = runTest {
        tidalServer.value = "https://tidal.mine.example/SECRET-ACCESS-TOKEN"
        val shown = rows().toString()
        assertThat(shown).doesNotContain("SECRET-ACCESS-TOKEN")
        assertThat(shown).doesNotContain("tidal.mine.example")
    }
}
