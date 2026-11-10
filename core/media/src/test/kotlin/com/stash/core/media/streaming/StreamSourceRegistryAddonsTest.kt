package com.stash.core.media.streaming

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
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.media.diagnostics.PlaybackDiagnosticsLog
import com.stash.data.download.lossless.LosslessQualityTier
import com.stash.data.download.lossless.LosslessSourcePreferences
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.Collections

/**
 * The add-on stream rungs (Stash Add-ons v1, phase 1). With an empty registry the chain must be
 * exactly the one the app had: same rungs, same calls, in the same order, and nothing asked of the
 * add-on client. With an add-on on, its rung sits at the lossless position.
 */
class StreamSourceRegistryAddonsTest {

    private val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val qbdlx: QbdlxStreamResolver = mockk { coEvery { resolve(any()) } coAnswers { calls += "qbdlx"; null } }
    private val tidal: TidalStreamResolver = mockk {
        coEvery { resolve(any()) } coAnswers { calls += "tidal"; null }
        coEvery { needsRelay(any()) } coAnswers { calls += "tidal?"; false }
    }
    private val jiosaavn: JioSaavnStreamResolver = mockk { coEvery { resolve(any()) } coAnswers { calls += "jiosaavn"; null } }
    private val youtube: YouTubeStreamResolver = mockk {
        coEvery { resolve(any(), any()) } coAnswers { calls += "youtube:${secondArg<Boolean>()}"; null }
    }
    private val streamingPreference: StreamingPreference = mockk {
        coEvery { isForceQbdlxOnly() } returns false
        coEvery { isSkipQobuz() } returns false
        coEvery { isForceYouTubeFallback() } returns false
    }
    private val losslessPrefs: LosslessSourcePreferences = mockk { coEvery { enabledNow() } returns true }

    /** Strict: only what the chain reads with the add-ons off. `streamingTier()` is NOT stubbed. */
    private val policy: StreamQualityPolicy = mockk {
        coEvery { dataSaverApplies() } returns false
        coEvery { preferTidalAac() } returns false
        coEvery { preferCloudCopies() } returns false
    }

    /** Strict: any call to the add-on client fails the test. */
    private val strictClient: HttpAddonClient = mockk()

    private class Registry(list: List<InstalledAddon>) : AddonRegistry {
        override val addons: StateFlow<List<InstalledAddon>> = MutableStateFlow(list)
    }

    private fun registry(addons: AddonStreamRungs?) = StreamSourceRegistry(
        qbdlx, jiosaavn, youtube, streamingPreference, LosslessSourceHealth(), losslessPrefs, Dispatchers.IO,
        PlaybackDiagnosticsLog(), tidal, null, policy, null, null, null, addons,
    )

    private fun track() = TrackEntity(id = 1L, title = "Title", artist = "Artist", album = "Album", durationMs = 200_000L, youtubeId = "abc")

    private fun addon(id: String = "test", userAdded: Boolean = false, metered: Boolean = false) = InstalledAddon(
        base = "https://addon.example/",
        manifest = Manifest(
            stash = 1, id = id, name = "Test", version = "1", kind = AddonKind.SERVER,
            resources = listOf("resolve", "stream") + if (metered) listOf("quota") else emptyList(),
            stream = StreamCapabilities(cors = false, manifests = listOf("none", "dash"), tiers = listOf("lossless", "hires")),
        ),
        userAdded = userAdded,
    )

    private val flac = StreamInfo(
        url = "https://cdn.example/a.flac", manifest = Delivery.NONE, tier = Tier.LOSSLESS, cors = false,
        bitDepth = 16, sampleRate = 44_100,
    )

    /** A client whose add-on has [hit] or misses (resolve answers no item). */
    private fun addonClient(hit: Boolean): HttpAddonClient = mockk {
        coEvery { resolve(any(), any(), any()) } coAnswers {
            calls += "addon:${firstArg<InstalledAddon>().id}"
            AddonResult.Ok(if (hit) Track(id = "x1", title = "Title", artist = "Artist") else null)
        }
        coEvery { stream(any(), any(), any(), any()) } returns AddonResult.Ok(flac)
    }

    private suspend fun walk(addons: AddonStreamRungs?, allowYouTube: Boolean, allowYtDlp: Boolean, speculative: Boolean): Pair<StreamUrl?, List<String>> {
        calls.clear()
        val r = registry(addons).resolve(track(), allowYouTube = allowYouTube, allowYtDlp = allowYtDlp, speculative = speculative)
        // A walk that may use yt-dlp starts the lossy lanes alongside the lossless rungs (look-aheads
        // too, when nothing defers): compare those calls as a set. The rest keep their order.
        val hedged = allowYouTube && allowYtDlp
        return r to if (hedged) calls.toList().sorted() else calls.toList()
    }

    /** With an add-on on, the chain reads the streaming tier for its request. */
    private fun withTier() {
        coEvery { policy.streamingTier() } returns LosslessQualityTier.HI_RES
    }

    // ------------------------------------------------------------------------------------------ inert

    @Test fun `an empty registry leaves every chain and its calls as they were`() = runTest {
        val none = AddonStreamRungs(Registry(emptyList()), strictClient)
        for (allowYouTube in listOf(true, false)) for (allowYtDlp in listOf(true, false)) for (speculative in listOf(false, true)) {
            val before = walk(null, allowYouTube, allowYtDlp, speculative)
            val after = walk(none, allowYouTube, allowYtDlp, speculative)
            assertThat(after).isEqualTo(before)
        }
        // Nothing was asked of the add-on client, and the streaming tier was never read for it.
        io.mockk.confirmVerified(strictClient)
    }

    @Test fun `an empty registry gives no rungs and reads nothing else`() {
        assertThat(AddonStreamRungs(Registry(emptyList()), strictClient).rungs()).isEmpty()
    }

    // ------------------------------------------------------------------------------------------ with an add-on on

    @Test fun `a default add-on is asked after Tidal and before the lossy rungs`() = runTest {
        withTier()
        val rungs = AddonStreamRungs(Registry(listOf(addon())), addonClient(hit = true))
        val r = registry(rungs).resolve(track(), allowYouTube = false, allowYtDlp = true)
        assertThat(r!!.origin).isEqualTo("addon:test")
        assertThat(r.codec).isEqualTo("flac")
        assertThat(r.bitsPerSample).isEqualTo(16)
        assertThat(calls).containsExactly("qbdlx", "tidal", "addon:test").inOrder()
    }

    @Test fun `a user-added add-on goes ahead of Qobuz`() = runTest {
        withTier()
        val rungs = AddonStreamRungs(Registry(listOf(addon(id = "mine", userAdded = true))), addonClient(hit = false))
        registry(rungs).resolve(track(), allowYouTube = false, allowYtDlp = true)
        assertThat(calls).containsExactly("addon:mine", "qbdlx", "tidal").inOrder()
    }

    @Test fun `a look-ahead stops before a metered add-on`() = runTest {
        withTier()
        val rungs = AddonStreamRungs(Registry(listOf(addon(metered = true))), addonClient(hit = true))
        val r = registry(rungs).resolve(track(), allowYouTube = true, allowYtDlp = true, speculative = true)
        assertThat(r).isNull()
        // tidal? = the look-ahead's cache check, made while the chain is built.
        assertThat(calls).containsExactly("tidal?", "qbdlx", "tidal").inOrder()
    }

    @Test fun `background fill never asks an add-on`() = runTest {
        val rungs = AddonStreamRungs(Registry(listOf(addon())), addonClient(hit = true))
        registry(rungs).resolve(track(), allowYouTube = false, allowYtDlp = false)
        assertThat(calls).isEmpty()
    }

    @Test fun `with the Lossless switch off no add-on is asked`() = runTest {
        coEvery { losslessPrefs.enabledNow() } returns false
        val rungs = AddonStreamRungs(Registry(listOf(addon())), addonClient(hit = true))
        registry(rungs).resolve(track(), allowYouTube = true, allowYtDlp = false)
        assertThat(calls.none { it.startsWith("addon:") }).isTrue()
    }

    @Test fun `an answer that needs request headers is not played`() = runTest {
        val client: HttpAddonClient = mockk {
            coEvery { resolve(any(), any(), any()) } returns AddonResult.Ok(Track(id = "x1", title = "Title", artist = "Artist"))
            coEvery { stream(any(), any(), any(), any()) } returns AddonResult.Ok(flac.copy(headers = mapOf("Referer" to "https://x.example/")))
        }
        val rung = AddonStreamRungs(Registry(listOf(addon())), client).rungs().single()
        assertThat(rung.resolve(track(), null)).isNull()
    }

    @Test fun `expiry is the add-on's, a minute early, and never past an hour`() {
        val now = 1_000_000_000_000L
        val soon = AddonStreamRung.toStreamUrl(flac.copy(expiresAt = now / 1000 + 600), null, "addon:x", now)
        assertThat(soon.expiresAtMs).isEqualTo(now + 540_000)
        val none = AddonStreamRung.toStreamUrl(flac, null, "addon:x", now)
        assertThat(none.expiresAtMs).isEqualTo(now + 30 * 60_000)
        val far = AddonStreamRung.toStreamUrl(flac.copy(expiresAt = now / 1000 + 86_400), null, "addon:x", now)
        assertThat(far.expiresAtMs).isEqualTo(now + 60 * 60_000)
    }
}
