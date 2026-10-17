package com.stash.data.download.lossless.qbdlx

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.quota.LosslessQuota
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import com.stash.data.download.lossless.relay.LosslessRelayClient
import com.stash.data.download.lossless.relay.RelayEntry
import com.stash.data.download.lossless.relay.RelayMint
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class QbdlxFileUrlRouterTest {
    private val api: QbdlxApiClient = mockk()
    private val store: QbdlxCredentialStore = mockk(relaxUnitFun = true)
    private val relay: LosslessRelayClient = mockk()
    private val configRelays = MutableStateFlow<List<RelayEntry>>(emptyList())
    private val config: LosslessConfigFetcher = mockk { every { relays } returns configRelays }
    private val prefs: LosslessSourcePreferences = mockk()
    private var now = 1_000_000_000_000L
    private val quota = LosslessQuota.inMemory { now }
    private val router = QbdlxFileUrlRouter(api, store, relay, config, prefs, quota)
    private val login = QbdlxLoginCredential("byo", "798273057", "sec")

    private fun noLogin() { coEvery { store.loginCredential() } returns null; coEvery { store.loginLive() } returns false }

    @Test fun `with the day's lossless allowance spent the config relays are not asked`() = runTest {
        noLogin()
        coEvery { prefs.customLosslessEndpointNow() } returns null
        configRelays.value = listOf(RelayEntry("https://r1"))
        quota.onHeaders("500/500", "${now / 1000 + 3600}")
        assertThat(router.getFileUrl(42, 27)).isNull()
        coVerify(exactly = 0) { relay.mint(any(), any(), any()) }
    }

    @Test fun `spent still asks the user's own endpoint`() = runTest {
        noLogin()
        coEvery { prefs.customLosslessEndpointNow() } returns "https://mine.example"
        configRelays.value = listOf(RelayEntry("https://r1"))
        quota.onHeaders("500/500", "${now / 1000 + 3600}")
        every { relay.isCooled(any()) } returns false
        coEvery { relay.mint("https://mine.example", 42, 27) } returns RelayMint.Ok("https://cdn/f?etsp=1", 27, 24, 96_000)
        assertThat(router.getFileUrl(42, 27)).isInstanceOf(QbdlxResolveResult.Ok::class.java)
        coVerify(exactly = 0) { relay.mint("https://r1", any(), any()) }
    }

    @Test fun `a Spent answer stops the walk since every config relay shares the count`() = runTest {
        noLogin()
        coEvery { prefs.customLosslessEndpointNow() } returns null
        configRelays.value = listOf(RelayEntry("https://r1"), RelayEntry("https://r2", priority = 2))
        every { relay.isCooled(any()) } returns false
        coEvery { relay.mint("https://r1", 42, 27) } returns RelayMint.Spent
        assertThat(router.getFileUrl(42, 27)).isNull()
        coVerify(exactly = 0) { relay.mint("https://r2", any(), any()) }
    }

    @Test fun `BYO signs locally and never touches a relay`() = runTest {
        coEvery { store.loginCredential() } returns login; coEvery { store.loginLive() } returns true
        coEvery { prefs.customLosslessEndpointNow() } returns "https://mine.example"
        coEvery { api.getFileUrl(42, 27, "byo") } returns QbdlxResolveResult.Ok("https://cdn/f?etsp=1", "flac", 16, 44_100)
        val r = router.getFileUrl(42, 27)
        assertThat(r).isInstanceOf(QbdlxResolveResult.Ok::class.java)
        coVerify(exactly = 0) { relay.mint(any(), any(), any()) }
        coVerify { store.recordAlive("byo") }
    }

    @Test fun `BYO TokenDead rejects the login and does NOT fall through to the relay`() = runTest {
        coEvery { store.loginCredential() } returns login; coEvery { store.loginLive() } returns true
        coEvery { api.getFileUrl(42, 27, "byo") } returns QbdlxResolveResult.TokenDead
        assertThat(router.getFileUrl(42, 27)).isEqualTo(QbdlxResolveResult.TokenDead)
        coVerify { store.rejectLogin("byo") }
        coVerify(exactly = 0) { relay.mint(any(), any(), any()) }
    }

    @Test fun `BYO 401 exception is treated as TokenDead`() = runTest {
        coEvery { store.loginCredential() } returns login; coEvery { store.loginLive() } returns true
        coEvery { api.getFileUrl(42, 27, "byo") } throws QbdlxAuthException(401)
        assertThat(router.getFileUrl(42, 27)).isEqualTo(QbdlxResolveResult.TokenDead)
        coVerify { store.rejectLogin("byo") }
    }

    // What rejectLogin then DOES with the token — terminal for a migrated paste,
    // a cooldown for a real account — is the store's decision, covered against a
    // real DataStore in QbdlxCredentialStoreTest. The router's job is only to call it.

    @Test fun `BYO network failure falls through to the relays without marking dead`() = runTest {
        coEvery { store.loginCredential() } returns login; coEvery { store.loginLive() } returns true
        coEvery { api.getFileUrl(42, 27, "byo") } throws java.io.IOException("reset")
        coEvery { prefs.customLosslessEndpointNow() } returns null
        configRelays.value = listOf(RelayEntry("https://a.example", 1))
        every { relay.isCooled(any()) } returns false
        coEvery { relay.mint("https://a.example", 42, 27) } returns RelayMint.Ok("https://cdn/f", 27, 24, 96_000)
        assertThat(router.getFileUrl(42, 27)).isInstanceOf(QbdlxResolveResult.Ok::class.java)
        coVerify(exactly = 0) { store.rejectLogin(any()) }
        coVerify(exactly = 0) { store.recordAlive(any()) }
    }

    @Test fun `BYO 5xx falls through and yields null when no relay is configured`() = runTest {
        coEvery { store.loginCredential() } returns login; coEvery { store.loginLive() } returns true
        coEvery { api.getFileUrl(42, 27, "byo") } throws QbdlxApiException(503)
        coEvery { prefs.customLosslessEndpointNow() } returns null
        assertThat(router.getFileUrl(42, 27)).isNull()
        coVerify(exactly = 0) { store.rejectLogin(any()) }
    }

    @Test fun `a dead-cooled login skips BYO and falls to the relays`() = runTest {
        coEvery { store.loginCredential() } returns login
        coEvery { store.loginLive() } returns false
        coEvery { prefs.customLosslessEndpointNow() } returns null
        configRelays.value = listOf(RelayEntry("https://a.example", 1))
        every { relay.isCooled(any()) } returns false
        coEvery { relay.mint("https://a.example", 42, 27) } returns RelayMint.Ok("https://cdn/f", 27, 24, 96_000)
        assertThat(router.getFileUrl(42, 27)).isInstanceOf(QbdlxResolveResult.Ok::class.java)
        coVerify(exactly = 0) { api.getFileUrl(any(), any(), any()) }
    }

    @Test fun `a relay that serves a lossy format_id is RegionLocked`() = runTest {
        noLogin(); coEvery { prefs.customLosslessEndpointNow() } returns null
        configRelays.value = listOf(RelayEntry("https://a.example", 1))
        every { relay.isCooled(any()) } returns false
        coEvery { relay.mint("https://a.example", 42, 27) } returns RelayMint.Ok("https://cdn/f.mp3", 5, 0, 0)
        assertThat(router.getFileUrl(42, 27)).isEqualTo(QbdlxResolveResult.RegionLocked)
    }

    /** 6 is CD FLAC — the BOUNDARY of the `< 6` downgrade rule, so it must pass. */
    @Test fun `a relay format_id of 6 (CD) is accepted`() = runTest {
        noLogin(); coEvery { prefs.customLosslessEndpointNow() } returns null
        configRelays.value = listOf(RelayEntry("https://a.example", 1))
        every { relay.isCooled(any()) } returns false
        coEvery { relay.mint("https://a.example", 42, 27) } returns RelayMint.Ok("https://cdn/f", 6, 16, 44_100)
        val r = router.getFileUrl(42, 27)
        assertThat(r).isInstanceOf(QbdlxResolveResult.Ok::class.java)
        assertThat((r as QbdlxResolveResult.Ok).bitDepth).isEqualTo(16)
    }

    @Test fun `custom endpoint outranks config relays`() = runTest {
        noLogin()
        coEvery { prefs.customLosslessEndpointNow() } returns "https://mine.example"
        configRelays.value = listOf(RelayEntry("https://public.example", 1))
        every { relay.isCooled(any()) } returns false
        coEvery { relay.mint("https://mine.example", 42, 27) } returns RelayMint.Ok("https://cdn/f?etsp=1", 27, 24, 96_000)
        val r = router.getFileUrl(42, 27) as QbdlxResolveResult.Ok
        assertThat(r.sampleRateHz).isEqualTo(96_000)   // Hz passed through, not multiplied
        coVerify(exactly = 0) { relay.mint("https://public.example", any(), any()) }
    }

    @Test fun `unavailable base falls through to the next - NoMatch is RegionLocked - nothing left is null`() = runTest {
        noLogin(); coEvery { prefs.customLosslessEndpointNow() } returns null
        configRelays.value = listOf(RelayEntry("https://a.example", 1), RelayEntry("https://b.example", 2))
        every { relay.isCooled(any()) } returns false
        coEvery { relay.mint("https://a.example", 42, 27) } returns RelayMint.Unavailable
        coEvery { relay.mint("https://b.example", 42, 27) } returns RelayMint.NoMatch
        assertThat(router.getFileUrl(42, 27)).isEqualTo(QbdlxResolveResult.RegionLocked)

        every { relay.isCooled(any()) } returns true
        assertThat(router.getFileUrl(43, 27)).isNull()
        coVerify(exactly = 0) { relay.mint(any(), 43, any()) }
    }
}
