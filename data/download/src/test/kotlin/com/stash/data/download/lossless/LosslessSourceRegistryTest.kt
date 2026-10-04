package com.stash.data.download.lossless

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
        }

    private val query = TrackQuery(artist = "A", title = "B")

    private fun registry(sources: Set<LosslessSource>) =
        LosslessSourceRegistry(sources, prefs, healthGate, streamingPreference)

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
}
