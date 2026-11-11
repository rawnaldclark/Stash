package com.stash.data.download.lossless.addon

import com.google.common.truth.Truth.assertThat
import com.stash.addons.contract.AddonKind
import com.stash.addons.contract.Delivery
import com.stash.addons.contract.Manifest
import com.stash.addons.contract.StreamCapabilities
import com.stash.addons.contract.StreamInfo
import com.stash.addons.contract.StreamRequest
import com.stash.addons.contract.Tier
import com.stash.addons.contract.Track
import com.stash.core.addons.AddonDialect
import com.stash.core.addons.AddonRegistry
import com.stash.core.addons.EclipseTraits
import com.stash.core.addons.AddonResult
import com.stash.core.addons.HttpAddonClient
import com.stash.core.addons.InstalledAddon
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.LosslessQualityTier
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import com.stash.data.download.lossless.relay.RelaySigner
import com.stash.data.download.lossless.relay.TidalRelay
import com.stash.data.download.lossless.tidal.FakeAddonRefDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.junit.Test

/** The download side of Stash Add-ons v1 (phase 1): the source seam, and the client's host ports. */
@OptIn(ExperimentalCoroutinesApi::class)
class AddonDownloadSeamTest {

    private class Registry(list: List<InstalledAddon>) : AddonRegistry {
        override val addons: StateFlow<List<InstalledAddon>> = MutableStateFlow(list)
    }

    private val prefs: LosslessSourcePreferences = mockk { coEvery { qualityTierNow() } returns LosslessQualityTier.HI_RES }

    private fun addon(resources: List<String> = listOf("resolve", "download"), tiers: List<String>? = listOf("lossless", "hires")) = InstalledAddon(
        base = "https://addon.example/",
        manifest = Manifest(
            stash = 1, id = "dl", name = "Downloads", version = "1", kind = AddonKind.SERVER, resources = resources,
            stream = StreamCapabilities(cors = false, manifests = listOf("none", "dash"), tiers = tiers),
        ),
    )

    private val flac = StreamInfo(
        url = "https://cdn.example/a.flac", manifest = Delivery.DASH, tier = Tier.HIRES, cors = false,
        bitDepth = 24, sampleRate = 96_000, bitrate = 4_608_000,
    )

    // ------------------------------------------------------------------------------------------ inert

    @Test fun `an empty registry gives no download sources and asks nothing`() {
        // Strict mocks: any call to the client or the limiter fails the test.
        val sources = AddonDownloadSources(Registry(emptyList()), mockk(), mockk(), mockk())
        assertThat(sources.sources()).isEmpty()
    }

    @Test fun `only add-ons that find songs, allow downloads and give lossless files become sources`() {
        val limiter = AggregatorRateLimiter()
        val ok = addon()
        val lossyOnly = addon(tiers = listOf("high")).let { it.copy(manifest = it.manifest.copy(id = "lossy")) }
        val noFinding = addon(resources = listOf("download")).let { it.copy(manifest = it.manifest.copy(id = "nofinding")) }
        // Phase 4 §2.4: search without resolve is matched on the phone, so it is a source.
        val searchOnly = addon(resources = listOf("search", "stream")).let { it.copy(manifest = it.manifest.copy(id = "search")) }
        // BitChord's allowDownloads: 0.
        val noDownloads = addon(resources = listOf("search", "stream")).let {
            it.copy(manifest = it.manifest.copy(id = "nodl"), dialect = AddonDialect.ECLIPSE, eclipse = EclipseTraits(allowDownloads = false))
        }
        val sources = AddonDownloadSources(Registry(listOf(ok, lossyOnly, noFinding, searchOnly, noDownloads)), mockk(), limiter, prefs).sources()
        assertThat(sources.map { it.id }).containsExactly("addon:dl", "addon:search")
    }

    // ------------------------------------------------------------------------------------------ the source

    @Test fun `a source resolves, then downloads at the download quality`() = runTest {
        val request = slot<StreamRequest>()
        val client: HttpAddonClient = mockk {
            coEvery { resolve(any(), any(), any()) } returns AddonResult.Ok(Track(id = "td:7", title = "B", artist = "A", isrc = "GBAAA0000001"))
            coEvery { download(any(), "td:7", capture(request), any()) } returns AddonResult.Ok(flac)
        }
        val source = AddonDownloadSources(Registry(listOf(addon())), refs(client), AggregatorRateLimiter(), prefs).sources().single()
        val r = source.resolve(TrackQuery(artist = "A", title = "B", isrc = "GBAAA0000001"))!!
        assertThat(r.sourceId).isEqualTo("addon:dl")
        assertThat(r.downloadUrl).isEqualTo(flac.url)
        assertThat(r.isDash).isTrue()
        assertThat(r.format.codec).isEqualTo("flac")
        assertThat(r.format.bitsPerSample).isEqualTo(24)
        assertThat(r.format.bitrateKbps).isEqualTo(4608)
        assertThat(r.confidence).isEqualTo(1f)
        assertThat(request.captured).isEqualTo(StreamRequest(Tier.HIRES, Tier.LOSSLESS, listOf(Delivery.NONE, Delivery.DASH)))
        // A download is background work: paced.
        coVerify { client.resolve(any(), any(), true) }
    }

    @Test fun `CD quality asks for lossless only`() {
        assertThat(AddonLosslessSource.lossless(LosslessQualityTier.CD).tier).isEqualTo(Tier.LOSSLESS)
        assertThat(AddonLosslessSource.lossless(LosslessQualityTier.MAX).tier).isEqualTo(Tier.HIRES)
    }

    @Test fun `no match, a failure, or an answer needing headers is no source result`() = runTest {
        // Each case on its own refs table: a miss stamp would otherwise answer the next case.
        for (answer in listOf<AddonResult<Track?>>(
            AddonResult.Ok(null),
            AddonResult.Failed(com.stash.core.addons.AddonFailure.Timeout),
            AddonResult.Ok(Track(id = "1", title = "B", artist = "A")),
        )) {
            val client: HttpAddonClient = mockk {
                coEvery { resolve(any(), any(), any()) } returns answer
                coEvery { download(any(), any(), any(), any()) } returns AddonResult.Ok(flac.copy(headers = mapOf("Referer" to "https://x.example/")))
            }
            val source = AddonDownloadSources(Registry(listOf(addon())), refs(client), AggregatorRateLimiter(), prefs).sources().single()
            assertThat(source.resolve(TrackQuery(artist = "A", title = "B"))).isNull()
            coVerify(exactly = 1) { client.resolve(any(), any(), any()) }
        }
    }

    @Test fun `a search-only add-on is matched on the phone and downloads its match`() = runTest {
        val client: HttpAddonClient = mockk {
            coEvery { search(any(), any(), any(), any(), any()) } returns AddonResult.Ok(
                com.stash.addons.contract.SearchResponse(tracks = listOf(Track(id = "9", title = "Digital Love", artist = "Daft Punk", durationMs = 301_000L))),
            )
            coEvery { stream(any(), "9", any(), true, any()) } returns AddonResult.Ok(flac)
        }
        val a = addon(resources = listOf("search", "stream"))
        val source = AddonDownloadSources(Registry(listOf(a)), refs(client), AggregatorRateLimiter(), prefs).sources().single()
        val r = source.resolve(TrackQuery(artist = "Daft Punk", title = "Digital Love", durationMs = 301_000L))!!
        assertThat(r.sourceTrackId).isEqualTo("9")
        assertThat(r.confidence).isAtLeast(0.7f)
    }

    // ------------------------------------------------------------------------------------------ host ports

    @Test fun `a Retry-After pauses for exactly that long, and null keeps the configured backoff`() = runTest {
        val limiter = AggregatorRateLimiter().apply { clock = virtualClock() }
        limiter.reportRateLimited("addon_x", 30_000L)
        assertThat(limiter.stateOf("addon_x").msUntilUnblock).isEqualTo(30_000L)
        advanceTimeBy(30_001)
        assertThat(limiter.stateOf("addon_x").isCircuitBroken).isFalse()

        limiter.reportRateLimited("addon_y", null)
        assertThat(limiter.stateOf("addon_y").msUntilUnblock).isEqualTo(AggregatorRateLimiter.Config().backoff429Ms)
    }

    @Test fun `a breaker the pause trips never shortens it, nor the pause a longer breaker`() = runTest {
        // Two bad answers, then a 429 asking for an hour: the 429 is the third failure and trips the
        // 30-minute breaker, which used to overwrite the hour.
        val limiter = AggregatorRateLimiter().apply { clock = virtualClock() }
        repeat(2) { limiter.reportFailure("addon_x") }
        limiter.reportRateLimited("addon_x", 3_600_000L)
        assertThat(limiter.stateOf("addon_x").msUntilUnblock).isEqualTo(3_600_000L)

        // A pause shorter than the breaker it trips leaves the breaker's block.
        repeat(2) { limiter.reportFailure("addon_y") }
        limiter.reportRateLimited("addon_y", 60_000L)
        assertThat(limiter.stateOf("addon_y").msUntilUnblock).isEqualTo(AggregatorRateLimiter.Config().circuitBreakDurationMs)
    }

    @Test fun `quota pauses only the metered key`() = runTest {
        val limiter = AggregatorRateLimiter().apply { clock = virtualClock() }
        val gate = RateLimiterAddonGate(limiter)
        gate.onAnswer(HttpAddonClient.rateKey("x"))
        gate.onRateLimited(HttpAddonClient.meteredKey("x"), 600_000L)
        assertThat(gate.admit(HttpAddonClient.rateKey("x"), paced = false)).isTrue()
        assertThat(gate.admit(HttpAddonClient.meteredKey("x"), paced = false)).isFalse()
    }

    @Test fun `the gate paces background calls and only checks the circuit in the foreground`() = runTest {
        val limiter = AggregatorRateLimiter().apply { clock = virtualClock() }
        val gate = RateLimiterAddonGate(limiter)
        assertThat(gate.admit("addon_x", paced = false)).isTrue()
        assertThat(limiter.stateOf("addon_x").tokensAvailable).isEqualTo(3.0) // nothing spent
        assertThat(gate.admit("addon_x", paced = true)).isTrue()
        assertThat(limiter.stateOf("addon_x").tokensAvailable).isLessThan(3.0)
        gate.onRateLimited("addon_x", 60_000L)
        assertThat(gate.admit("addon_x", paced = false)).isFalse()
    }

    @Test fun `first-party is the signed config's relay, by origin and path`() {
        val relay = MutableStateFlow<TidalRelay?>(TidalRelay("https://relay.stash.example/tok", null))
        val trust = SignedConfigAddonTrust(mockk<LosslessConfigFetcher> { every { tidalRelay } returns relay }, com.stash.core.addons.NoDefaultAddons)
        assertThat(trust.isFirstParty("https://relay.stash.example/tok/")).isTrue()
        assertThat(trust.isFirstParty("https://relay.stash.example/tok/addon/")).isTrue()
        assertThat(trust.isFirstParty("https://relay.stash.example/tokens/")).isFalse()
        assertThat(trust.isFirstParty("https://relay.stash.example.evil.example/tok/")).isFalse()
        assertThat(trust.isFirstParty("http://relay.stash.example/tok/")).isFalse()
        assertThat(trust.isFirstParty("https://relay.stash.example:8443/tok/")).isFalse()
        relay.value = null
        assertThat(trust.isFirstParty("https://relay.stash.example/tok/")).isFalse()
    }

    @Test fun `the signer adds the build, the signature and the supporter token`() = runTest {
        val relaySigner: RelaySigner = mockk {
            every { appBuild(any()) } just runs
            coEvery { sign(any(), "td:7", any()) } returns true
            coEvery { entitle(any()) } just runs
        }
        val signer = RelayAddonSigner(relaySigner)
        assertThat(signer.sign(Request.Builder().url("https://relay.stash.example/stream/td:7"), "td:7")).isTrue()
        coVerifyOrder {
            relaySigner.appBuild(any())
            relaySigner.sign(any(), "td:7", any())
            relaySigner.entitle(any())
        }
    }

    private fun refs(client: HttpAddonClient) = AddonRefs(client, FakeAddonRefDao())

    private fun TestScope.virtualClock(): AggregatorRateLimiter.Clock = object : AggregatorRateLimiter.Clock {
        override fun nowMs(): Long = testScheduler.currentTime
    }
}
