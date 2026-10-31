package com.stash.data.download.lossless

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.lossless.qbdlx.QbdlxCredentialStore
import com.stash.data.download.lossless.quota.LosslessQuota
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import com.stash.data.download.lossless.relay.RelayEntry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Tests that need a source guaranteed to be consulted (not parked) use a
 * synthetic id (`"lucida"`) — the registry only special-cases the known parked
 * ids, so an unrecognised id always survives the filter.
 */
class LosslessSourceRegistryTest {

    private val prefs: LosslessSourcePreferences = mockk()
    private val healthGate: LosslessSourceHealthGate = mockk()
    private val streamingPreference: com.stash.core.data.prefs.StreamingPreference =
        mockk {
            // Normal use: all force-only toggles off so the full chain is consulted.
            coEvery { isForceQbdlxOnly() } returns false
            coEvery { isSkipQobuz() } returns false
        }

    // What the user has of their own; nothing by default.
    private val ownQobuzLogin = MutableStateFlow(false)
    private val customQobuzEndpoint = MutableStateFlow<String?>(null)
    private val ownTidalServer = MutableStateFlow<String?>(null)

    /** The download quality setting; Hi-Res unless a test picks another. */
    private var downloadTier = LosslessQualityTier.HI_RES

    init {
        every { prefs.customLosslessEndpoint } returns customQobuzEndpoint
        every { prefs.bitchordEndpoint } returns ownTidalServer
        coEvery { prefs.qualityTierNow() } answers { downloadTier }
    }

    /** The real rule ([LosslessAvailability.ownTidalFirst]), read from the flows above. */
    private val availability = LosslessAvailability(
        credentialStore = mockk<QbdlxCredentialStore> {
            every { hasLogin } returns ownQobuzLogin
            every { connectedEmailFlow } returns MutableStateFlow(null)
        },
        config = mockk<LosslessConfigFetcher> {
            every { relays } returns MutableStateFlow(listOf(RelayEntry("https://relay.example")))
        },
        prefs = prefs,
        relayClient = mockk(),
        quota = LosslessQuota.inMemory { 0L },
    )

    private val query = TrackQuery(artist = "A", title = "B")

    private fun registry(sources: Set<LosslessSource>) =
        LosslessSourceRegistry(sources, prefs, healthGate, streamingPreference, availability)

    private fun flacResult(srcId: String) = SourceResult(
        sourceId = srcId,
        downloadUrl = "https://cdn/$srcId.flac",
        downloadHeaders = emptyMap(),
        format = AudioFormat(codec = "flac", bitrateKbps = 0, sampleRateHz = 44_100, bitsPerSample = 16),
        confidence = 0.9f,
        sourceTrackId = "1",
        coverArtUrl = null,
    )

    private fun fakeSource(srcId: String, result: SourceResult?): LosslessSource =
        mockk {
            every { id } returns srcId
            coEvery { isEnabled() } returns true
            coEvery { resolve(any()) } returns result
            coEvery { rateLimitState() } returns RateLimitState(3.0, 0L, false, 0L, 0)
        }

    private fun acceptAnyQuality() {
        coEvery { prefs.priorityOrderNow() } returns emptyList()
        coEvery { prefs.minQualityNow() } returns LosslessSourcePreferences.MinQuality.ANY
    }

    @Test
    fun `degraded source is skipped without resolving and the next source wins`() = runTest {
        acceptAnyQuality()
        // Use active (non-parked) sources so the normal chain reaches them.
        val lucidaResult = flacResult("lucida")
        val qbdlx = fakeSource("qbdlx_qobuz", flacResult("qbdlx_qobuz"))
        val lucida = fakeSource("lucida", lucidaResult)
        coEvery { healthGate.isDegraded("qbdlx_qobuz") } returns true
        coEvery { healthGate.isDegraded("lucida") } returns false

        // priorityOrder empty → registration order; ensure qbdlx is tried first.
        val registry = registry(linkedSetOf(qbdlx, lucida))
        val result = registry.resolve(query)

        assertThat(result).isEqualTo(lucidaResult)
        coVerify(exactly = 0) { qbdlx.resolve(any()) } // skipped before resolving
        coVerify(exactly = 1) { lucida.resolve(any()) }
    }

    @Test
    fun `qbdlx is tried ahead of lower-priority sources in the normal chain`() = runTest {
        coEvery { prefs.priorityOrderNow() } returns
            LosslessSourcePreferences.DEFAULT_PRIORITY
        coEvery { prefs.minQualityNow() } returns LosslessSourcePreferences.MinQuality.ANY
        coEvery { healthGate.isDegraded(any()) } returns false

        val qbdlxResult = flacResult("qbdlx_qobuz")
        // Both would match; qbdlx must win because it's ranked first (fast,
        // no proxy) — the unranked source (appended last) is never consulted.
        val qbdlx = fakeSource("qbdlx_qobuz", qbdlxResult)
        val lucida = fakeSource("lucida", flacResult("lucida"))

        val registry = registry(linkedSetOf(lucida, qbdlx)) // scrambled on purpose
        val result = registry.resolve(query)

        assertThat(result).isEqualTo(qbdlxResult)
        coVerify(exactly = 1) { qbdlx.resolve(any()) }
        coVerify(exactly = 0) { lucida.resolve(any()) }
    }

    @Test
    fun `parked qobuz proxies are skipped and an active source serves the normal chain`() = runTest {
        coEvery { prefs.priorityOrderNow() } returns
            LosslessSourcePreferences.DEFAULT_PRIORITY
        coEvery { prefs.minQualityNow() } returns LosslessSourcePreferences.MinQuality.ANY
        coEvery { healthGate.isDegraded(any()) } returns false

        val lucidaResult = flacResult("lucida")
        // squid + kennyy are parked (PARKED_SOURCE_IDS): the normal resolve
        // chain must skip them entirely, never even calling resolve().
        val squid = fakeSource("squid_qobuz", flacResult("squid_qobuz"))
        val kennyy = fakeSource("kennyy_qobuz", flacResult("kennyy_qobuz"))
        val lucida = fakeSource("lucida", lucidaResult)

        val registry = registry(linkedSetOf(squid, kennyy, lucida))
        val result = registry.resolve(query)

        assertThat(result).isEqualTo(lucidaResult)
        coVerify(exactly = 0) { squid.resolve(any()) } // parked
        coVerify(exactly = 0) { kennyy.resolve(any()) } // parked
        coVerify(exactly = 1) { lucida.resolve(any()) }
    }

    @Test
    fun `no source resolves when all are degraded`() = runTest {
        acceptAnyQuality()
        val kennyy = fakeSource("kennyy_qobuz", flacResult("kennyy_qobuz"))
        val squid = fakeSource("squid_qobuz", flacResult("squid_qobuz"))
        coEvery { healthGate.isDegraded(any()) } returns true

        val registry = registry(linkedSetOf(kennyy, squid))

        assertThat(registry.resolve(query)).isNull()
        coVerify(exactly = 0) { kennyy.resolve(any()) }
        coVerify(exactly = 0) { squid.resolve(any()) }
    }

    @Test
    fun `the priority list, not registration order, drives ranking`() = runTest {
        coEvery { prefs.priorityOrderNow() } returns LosslessSourcePreferences.DEFAULT_PRIORITY

        // Register sources out of order; an id missing from the list goes last.
        val lucida = fakeSource("lucida", flacResult("lucida"))
        val squid = fakeSource("squid_qobuz", flacResult("squid_qobuz"))
        val kennyy = fakeSource("kennyy_qobuz", flacResult("kennyy_qobuz"))

        val registry = registry(linkedSetOf(lucida, kennyy, squid))

        assertThat(registry.orderedSources().map { it.id })
            .containsExactly("squid_qobuz", "kennyy_qobuz", "lucida").inOrder()
    }

    // -- canSearchNow: can an empty answer be trusted as "no lossless version"? --

    private fun sourceInState(
        srcId: String,
        enabled: Boolean = true,
        circuitBroken: Boolean = false,
        recentFailures: Int = 0,
    ): LosslessSource = mockk {
        every { id } returns srcId
        coEvery { isEnabled() } returns enabled
        coEvery { rateLimitState() } returns RateLimitState(3.0, 0L, circuitBroken, 0L, recentFailures)
    }

    @Test
    fun `a healthy source in the chain means a lookup could really search`() = runTest {
        acceptAnyQuality()
        coEvery { healthGate.isDegraded(any()) } returns false

        assertThat(registry(linkedSetOf(sourceInState("lucida"))).canSearchNow()).isTrue()
    }

    @Test
    fun `no lookup could search while every source is off, degraded, broken, failing or parked`() = runTest {
        acceptAnyQuality()
        coEvery { healthGate.isDegraded(any()) } returns false
        coEvery { healthGate.isDegraded("degraded") } returns true
        val sources = linkedSetOf(
            sourceInState("off", enabled = false),
            sourceInState("degraded"),
            sourceInState("broken", circuitBroken = true),
            // Its last call failed (a network error answers null like a miss does).
            sourceInState("failing", recentFailures = 1),
            sourceInState("squid_qobuz"), // parked: resolve never asks it
        )

        assertThat(registry(sources).canSearchNow()).isFalse()
    }

    @Test
    fun `a cancelled caller is rethrown, not treated as a failing source`() = runTest {
        acceptAnyQuality()
        coEvery { healthGate.isDegraded(any()) } returns false
        val cancelled = mockk<LosslessSource> {
            every { id } returns "lucida"
            coEvery { isEnabled() } returns true
            coEvery { resolve(any(), any()) } throws kotlinx.coroutines.CancellationException("worker stopped")
        }
        val next = fakeSource("other", flacResult("other"))

        val thrown = runCatching { registry(linkedSetOf(cancelled, next)).resolve(query) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
        coVerify(exactly = 0) { next.resolve(any(), any()) } // the chain stops; no lossy fallthrough
    }

    @Test
    fun `debug skip-Qobuz sends a download to Tidal and never asks Qobuz`() = runTest {
        coEvery { prefs.priorityOrderNow() } returns LosslessSourcePreferences.DEFAULT_PRIORITY
        coEvery { prefs.minQualityNow() } returns LosslessSourcePreferences.MinQuality.ANY
        coEvery { healthGate.isDegraded(any()) } returns false
        coEvery { streamingPreference.isSkipQobuz() } returns true

        val tidalResult = flacResult("tidal_bitchord")
        val qbdlx = fakeSource("qbdlx_qobuz", flacResult("qbdlx_qobuz"))
        val tidal = fakeSource("tidal_bitchord", tidalResult)

        val result = registry(linkedSetOf(qbdlx, tidal)).resolve(query)

        assertThat(result).isEqualTo(tidalResult)
        coVerify(exactly = 0) { qbdlx.resolve(any()) }
        coVerify(exactly = 1) { tidal.resolve(any()) }
    }

    @Test
    fun `skip-Qobuz off keeps Qobuz ahead of Tidal for downloads`() = runTest {
        coEvery { prefs.priorityOrderNow() } returns LosslessSourcePreferences.DEFAULT_PRIORITY
        coEvery { prefs.minQualityNow() } returns LosslessSourcePreferences.MinQuality.ANY
        coEvery { healthGate.isDegraded(any()) } returns false

        val qbdlxResult = flacResult("qbdlx_qobuz")
        val qbdlx = fakeSource("qbdlx_qobuz", qbdlxResult)
        val tidal = fakeSource("tidal_bitchord", flacResult("tidal_bitchord"))

        val result = registry(linkedSetOf(tidal, qbdlx)).resolve(query)

        assertThat(result).isEqualTo(qbdlxResult)
        coVerify(exactly = 0) { tidal.resolve(any()) }
    }

    // -- The user's own Tidal server ([LosslessAvailability.ownTidalFirst]) --

    private fun defaultOrderAnyQuality() {
        coEvery { prefs.priorityOrderNow() } returns LosslessSourcePreferences.DEFAULT_PRIORITY
        coEvery { prefs.minQualityNow() } returns LosslessSourcePreferences.MinQuality.ANY
        coEvery { healthGate.isDegraded(any()) } returns false
    }

    @Test
    fun `own Tidal server and no own Qobuz - Tidal is asked before Qobuz`() = runTest {
        defaultOrderAnyQuality()
        ownTidalServer.value = "https://tidal.mine.example"
        val tidalResult = flacResult("tidal_bitchord")
        val qbdlx = fakeSource("qbdlx_qobuz", flacResult("qbdlx_qobuz"))
        val tidal = fakeSource("tidal_bitchord", tidalResult)

        val result = registry(linkedSetOf(qbdlx, tidal)).resolve(query)

        assertThat(result).isEqualTo(tidalResult)
        coVerify(exactly = 0) { qbdlx.resolve(any()) }
    }

    @Test
    fun `own Tidal server first - a Tidal miss still falls back to Qobuz`() = runTest {
        defaultOrderAnyQuality()
        ownTidalServer.value = "https://tidal.mine.example"
        val qbdlxResult = flacResult("qbdlx_qobuz")
        val qbdlx = fakeSource("qbdlx_qobuz", qbdlxResult)
        val tidal = fakeSource("tidal_bitchord", null)

        val result = registry(linkedSetOf(qbdlx, tidal)).resolve(query)

        assertThat(result).isEqualTo(qbdlxResult)
        coVerifyOrder {
            tidal.resolve(any())
            qbdlx.resolve(any())
        }
    }

    @Test
    fun `own Tidal server and own Qobuz login - Qobuz keeps the lead`() = runTest {
        defaultOrderAnyQuality()
        ownTidalServer.value = "https://tidal.mine.example"
        ownQobuzLogin.value = true
        val qbdlxResult = flacResult("qbdlx_qobuz")
        val qbdlx = fakeSource("qbdlx_qobuz", qbdlxResult)
        val tidal = fakeSource("tidal_bitchord", flacResult("tidal_bitchord"))

        val result = registry(linkedSetOf(tidal, qbdlx)).resolve(query)

        assertThat(result).isEqualTo(qbdlxResult)
        coVerify(exactly = 0) { tidal.resolve(any()) }
    }

    @Test
    fun `own Tidal server and a custom Qobuz endpoint - Qobuz keeps the lead`() = runTest {
        defaultOrderAnyQuality()
        ownTidalServer.value = "https://tidal.mine.example"
        customQobuzEndpoint.value = "https://qobuz.mine.example"
        val qbdlxResult = flacResult("qbdlx_qobuz")
        val qbdlx = fakeSource("qbdlx_qobuz", qbdlxResult)
        val tidal = fakeSource("tidal_bitchord", flacResult("tidal_bitchord"))

        assertThat(registry(linkedSetOf(tidal, qbdlx)).resolve(query)).isEqualTo(qbdlxResult)
        coVerify(exactly = 0) { tidal.resolve(any()) }
    }

    /** Tidal has no quality setting: it would save its best, usually 24-bit, where CD was asked for. */
    @Test
    fun `CD downloads - Qobuz keeps the lead, the user's own Tidal server right after`() = runTest {
        defaultOrderAnyQuality()
        downloadTier = LosslessQualityTier.CD
        ownTidalServer.value = "https://tidal.mine.example"
        val tidalResult = flacResult("tidal_bitchord")
        val qbdlx = fakeSource("qbdlx_qobuz", null)
        val tidal = fakeSource("tidal_bitchord", tidalResult)

        assertThat(registry(linkedSetOf(qbdlx, tidal)).resolve(query)).isEqualTo(tidalResult)
        coVerifyOrder {
            qbdlx.resolve(any())
            tidal.resolve(any())
        }
    }

    @Test
    fun `Max downloads - the user's own Tidal server leads`() = runTest {
        defaultOrderAnyQuality()
        downloadTier = LosslessQualityTier.MAX
        ownTidalServer.value = "https://tidal.mine.example"
        val tidalResult = flacResult("tidal_bitchord")
        val qbdlx = fakeSource("qbdlx_qobuz", flacResult("qbdlx_qobuz"))
        val tidal = fakeSource("tidal_bitchord", tidalResult)

        assertThat(registry(linkedSetOf(qbdlx, tidal)).resolve(query)).isEqualTo(tidalResult)
        coVerify(exactly = 0) { qbdlx.resolve(any()) }
    }

    @Test
    fun `own Tidal server first moves only Tidal - every other source keeps its place`() = runTest {
        defaultOrderAnyQuality()
        ownTidalServer.value = "https://tidal.mine.example"
        val misses = linkedSetOf(
            fakeSource("lucida", null), // unranked: appended after the default order
            fakeSource("tidal_bitchord", null),
            fakeSource("qbdlx_qobuz", null),
        )

        assertThat(registry(misses).resolve(query)).isNull()
        val (lucida, tidal, qbdlx) = misses.toList()
        coVerifyOrder {
            tidal.resolve(any())
            qbdlx.resolve(any())
            lucida.resolve(any())
        }
    }
}
