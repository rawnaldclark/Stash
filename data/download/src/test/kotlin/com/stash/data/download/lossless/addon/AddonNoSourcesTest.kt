package com.stash.data.download.lossless.addon

import com.google.common.truth.Truth.assertThat
import com.stash.addons.contract.AddonKind
import com.stash.addons.contract.Delivery
import com.stash.addons.contract.Manifest
import com.stash.addons.contract.StreamCapabilities
import com.stash.addons.contract.StreamInfo
import com.stash.addons.contract.Tier
import com.stash.addons.contract.Track
import com.stash.core.addons.AddonRegistry
import com.stash.core.addons.AddonResult
import com.stash.core.addons.HttpAddonClient
import com.stash.core.addons.InstalledAddon
import com.stash.core.data.prefs.StreamingPreference
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.LosslessQualityTier
import com.stash.data.download.lossless.LosslessSourceHealthGate
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.LosslessSourceRegistry
import com.stash.data.download.lossless.PlayOrder
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.tidal.FakeAddonRefDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

/**
 * review-882 B1, the search preview's half: a lookup marked [NoAddonSources] reaches no add-on.
 * The preview plays its match on a plain cached HTTP source, which has none of the add-on base
 * rules, so `LosslessUrlPrefetcher` marks its lookup (`LosslessUrlPrefetcherTest` in `:core:media`
 * holds it to that) and every add-on source answers null without a request.
 *
 * Run through the real [LosslessSourceRegistry] walk: the marker is a coroutine context element,
 * and what is proven here is that it reaches each [AddonLosslessSource] through that walk.
 */
class AddonNoSourcesTest {

    private class Registry(list: List<InstalledAddon>) : AddonRegistry {
        override val addons: StateFlow<List<InstalledAddon>> = MutableStateFlow(list)
    }

    private val prefs: LosslessSourcePreferences = mockk {
        coEvery { qualityTierNow() } returns LosslessQualityTier.HI_RES
        coEvery { minQualityNow() } returns LosslessSourcePreferences.MinQuality.ANY
    }
    private val healthGate: LosslessSourceHealthGate = mockk { coEvery { isDegraded(any()) } returns false }
    private val streamingPreference: StreamingPreference = mockk {
        coEvery { isForceQbdlxOnly() } returns false
        coEvery { isSkipQobuz() } returns false
    }

    private fun addon(id: String, userAdded: Boolean) = InstalledAddon(
        base = "https://$id.example/",
        manifest = Manifest(
            stash = 1, id = id, name = id, version = "1", kind = AddonKind.SERVER, resources = listOf("resolve", "download"),
            stream = StreamCapabilities(cors = false, manifests = listOf("none"), tiers = listOf("lossless")),
        ),
        userAdded = userAdded,
    )

    private val flac = StreamInfo(url = "https://cdn.example/a.flac", manifest = Delivery.NONE, tier = Tier.LOSSLESS, cors = false)

    private val client: HttpAddonClient = mockk {
        coEvery { resolve(any(), any(), any()) } returns AddonResult.Ok(Track(id = "7", title = "B", artist = "A", isrc = "GBAAA0000001"))
        coEvery { download(any(), any(), any(), any()) } returns AddonResult.Ok(flac)
    }

    /** No Hilt-bound source: the chain is the add-ons alone, one the user added and one default. */
    private fun registry(): LosslessSourceRegistry {
        val addons = Registry(listOf(addon("mine", userAdded = true), addon("official", userAdded = false)))
        return LosslessSourceRegistry(
            sources = emptySet(),
            prefs = prefs,
            healthGate = healthGate,
            streamingPreference = streamingPreference,
            availability = mockk(),
            addons = AddonDownloadSources(addons, AddonRefs(client, FakeAddonRefDao()), AggregatorRateLimiter(), prefs),
            // The chain is asked in the play order (add-ons phase 4 §2.8); nothing saved is the automatic
            // one: the user's add-ons, then the defaults.
            playOrder = PlayOrder(flowOf(null), {}, addons),
        )
    }

    private val query = TrackQuery(artist = "A", title = "B", isrc = "GBAAA0000001")

    @Test fun `a lookup marked NoAddonSources asks no add-on, the user's or a default`() = runTest {
        val match = withContext(NoAddonSources) { registry().resolve(query, bypassRateLimit = true) }
        assertThat(match).isNull()
        coVerify(exactly = 0) { client.resolve(any(), any(), any()) }
        coVerify(exactly = 0) { client.download(any(), any(), any(), any()) }
        coVerify(exactly = 0) { client.search(any(), any(), any(), any()) }
    }

    @Test fun `the same lookup without the marker does reach the add-on`() = runTest {
        val match = registry().resolve(query, bypassRateLimit = true)
        assertThat(match!!.sourceId).isEqualTo("addon:mine")
        coVerify(exactly = 1) { client.resolve(any(), any(), any()) }
    }
}
