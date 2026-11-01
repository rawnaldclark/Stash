package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.stash.core.data.prefs.StreamingPreference
import com.stash.data.download.lossless.LosslessQualityTier
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.prefs.StreamingQualityPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamQualityPolicyTest {

    private val connectivity = mockk<ConnectivityMonitor>()
    private val prefs = mockk<StreamingQualityPreferences>()
    private val cache = StreamUrlCache()
    private val losslessPrefs = mockk<LosslessSourcePreferences>()
    private val streamingPreference = mockk<StreamingPreference> {
        coEvery { isSkipQobuz() } returns false
    }
    private val policy = StreamQualityPolicy(connectivity, prefs, cache, losslessPrefs, streamingPreference)

    /** [metered] defaults to [cellular]: mobile data is metered, home Wi-Fi isn't. */
    private fun setup(
        cellular: Boolean,
        wifi: LosslessQualityTier = LosslessQualityTier.MAX,
        cell: LosslessQualityTier = LosslessQualityTier.CD,
        saveData: Boolean = false,
        preferTidalAac: Boolean = false,
        metered: Boolean = cellular,
        lossless: Boolean = true,
    ) {
        every { connectivity.isCellular() } returns cellular
        every { connectivity.isMetered() } returns metered
        coEvery { prefs.wifiTierNow() } returns wifi
        coEvery { prefs.cellularTierNow() } returns cell
        coEvery { prefs.saveDataNow() } returns saveData
        coEvery { prefs.preferTidalAacNow() } returns preferTidalAac
        coEvery { prefs.preferCloudCopiesNow() } returns false
        coEvery { losslessPrefs.enabledNow() } returns lossless
    }

    @Test fun `wifi uses wifi tier`() = runTest {
        setup(cellular = false, wifi = LosslessQualityTier.MAX)
        assertThat(policy.streamingTier()).isEqualTo(LosslessQualityTier.MAX)
    }

    @Test fun `cellular uses cellular tier`() = runTest {
        setup(cellular = true, cell = LosslessQualityTier.CD)
        assertThat(policy.streamingTier()).isEqualTo(LosslessQualityTier.CD)
    }

    /**
     * The Data saver matrix: metered x Lossless x the switch. It applies only where the switch is
     * on AND the network is metered; there, Lossless on streams lossy at [LossyQuality.DATA_SAVER]
     * (no FLAC, so the lossless rungs step aside) and Lossless off the lowest lossy quality. Every
     * other cell is exactly what the switch being off gives.
     */
    @Test fun `data saver matrix - metered x lossless x switch`() = runTest {
        data class Cell(val metered: Boolean, val lossless: Boolean, val saver: Boolean, val applies: Boolean, val lossy: LossyQuality)
        val cells = listOf(
            Cell(metered = false, lossless = true, saver = false, applies = false, lossy = LossyQuality.FULL),
            Cell(metered = false, lossless = true, saver = true, applies = false, lossy = LossyQuality.FULL),
            Cell(metered = false, lossless = false, saver = false, applies = false, lossy = LossyQuality.FULL),
            Cell(metered = false, lossless = false, saver = true, applies = false, lossy = LossyQuality.FULL),
            Cell(metered = true, lossless = true, saver = false, applies = false, lossy = LossyQuality.FULL),
            Cell(metered = true, lossless = true, saver = true, applies = true, lossy = LossyQuality.DATA_SAVER),
            Cell(metered = true, lossless = false, saver = false, applies = false, lossy = LossyQuality.FULL),
            Cell(metered = true, lossless = false, saver = true, applies = true, lossy = LossyQuality.LOWEST),
        )
        for (c in cells) {
            setup(cellular = c.metered, metered = c.metered, lossless = c.lossless, saveData = c.saver)
            assertWithMessage("$c applies").that(policy.dataSaverApplies()).isEqualTo(c.applies)
            assertWithMessage("$c lossy").that(policy.lossyQuality()).isEqualTo(c.lossy)
        }
    }

    @Test fun `the lossy qualities ask JioSaavn and YouTube for the right files`() {
        assertThat(LossyQuality.FULL.jioSaavnKbps).isEqualTo(320)
        assertThat(LossyQuality.FULL.youTubeLowest).isFalse()
        // Lossless on, mobile data: YouTube's best and JioSaavn's same-size 160, not FLAC or Tidal AAC.
        assertThat(LossyQuality.DATA_SAVER.jioSaavnKbps).isEqualTo(160)
        assertThat(LossyQuality.DATA_SAVER.youTubeLowest).isFalse()
        assertThat(LossyQuality.LOWEST.jioSaavnKbps).isEqualTo(96)
        assertThat(LossyQuality.LOWEST.youTubeLowest).isTrue()
    }

    /** On Wi-Fi Data saver changes nothing: the Wi-Fi tier stands (it used to force CD here). */
    @Test fun `data saver keeps the wifi tier on unmetered wifi`() = runTest {
        setup(cellular = false, wifi = LosslessQualityTier.MAX, saveData = true)
        assertThat(policy.streamingTier()).isEqualTo(LosslessQualityTier.MAX)
        assertThat(policy.dataSaverApplies()).isFalse()
    }

    /** A Wi-Fi hotspot marked metered spends a data plan: Data saver applies on it. */
    @Test fun `data saver applies on a metered wifi hotspot`() = runTest {
        setup(cellular = false, metered = true, saveData = true)
        assertThat(policy.dataSaverApplies()).isTrue()
    }

    /** Where it applies no lossless rung runs; anything that still asks (a debug toggle) gets the floor. */
    @Test fun `data saver on mobile data floors the tier at CD`() = runTest {
        setup(cellular = true, cell = LosslessQualityTier.HI_RES, saveData = true)
        assertThat(policy.streamingTier()).isEqualTo(LosslessQualityTier.CD)
    }

    /** Walking out of the house with Data saver on: the FLAC URLs cached on Wi-Fi must not play on mobile data. */
    @Test fun `leaving wifi with data saver on drops the cached URLs`() = runTest {
        setup(cellular = false, wifi = LosslessQualityTier.CD, saveData = true)
        policy.streamingTier()
        cache.put(1L, StreamUrl(url = "https://cdn/1.flac?etsp=1", expiresAtMs = Long.MAX_VALUE))

        setup(cellular = true, cell = LosslessQualityTier.CD, saveData = true)
        assertThat(policy.dataSaverApplies()).isTrue()
        assertThat(cache.get(1L)).isNull()
    }

    /** Flipping the switch on Wi-Fi changes nothing a resolve asks for, so the cache stays. */
    @Test fun `flipping data saver on wifi keeps the cached URLs`() = runTest {
        setup(cellular = false, saveData = false)
        policy.streamingTier()
        cache.put(1L, StreamUrl(url = "https://cdn/1.flac?etsp=1", expiresAtMs = Long.MAX_VALUE))

        setup(cellular = false, saveData = true)
        policy.streamingTier()
        assertThat(cache.get(1L)).isNotNull()
    }

    /**
     * The Lossless switch governs streaming (2026-09-05). Off, the chain skips the lossless
     * sources — but a FLAC link cached in the last hour would still play. The lossy path
     * reads [StreamQualityPolicy.lossyQuality], so that read must notice the switch and clear.
     */
    @Test fun `flipping the lossless switch drops the cached URLs`() = runTest {
        setup(cellular = false, wifi = LosslessQualityTier.HI_RES)
        policy.streamingTier()
        cache.put(1L, StreamUrl(url = "https://cdn/1.flac?etsp=1", expiresAtMs = Long.MAX_VALUE))

        coEvery { losslessPrefs.enabledNow() } returns false
        policy.lossyQuality()                        // what the YouTube resolver asks
        assertThat(cache.get(1L)).isNull()
    }

    /** With a CD tier already chosen on mobile data, flipping Data saver still changes the chain: the cache must go. */
    @Test fun `flipping data saver on mobile data with a CD tier still drops the cached URLs`() = runTest {
        setup(cellular = true, cell = LosslessQualityTier.CD, saveData = false)
        policy.streamingTier()
        cache.put(1L, StreamUrl(url = "https://cdn/1?etsp=1", expiresAtMs = Long.MAX_VALUE))

        setup(cellular = true, cell = LosslessQualityTier.CD, saveData = true)
        assertThat(policy.streamingTier()).isEqualTo(LosslessQualityTier.CD)
        assertThat(cache.get(1L)).isNull()
    }

    /**
     * A cached stream URL is only right for the tier it was minted under. Flipping Data
     * saver, changing a tier, or walking from Wi-Fi onto cellular changes what the next
     * resolve asks for — and must also stop the hour-old URLs from the OLD tier being served.
     */
    @Test fun `a tier change between resolves drops every cached stream URL`() = runTest {
        setup(cellular = false, wifi = LosslessQualityTier.HI_RES)
        policy.streamingTier()
        cache.put(1L, StreamUrl(url = "https://cdn/1?etsp=1", expiresAtMs = Long.MAX_VALUE))

        policy.streamingTier()                       // same tier — the cache stays
        assertThat(cache.get(1L)).isNotNull()

        every { connectivity.isCellular() } returns true   // Wi-Fi → cellular: CD now
        every { connectivity.isMetered() } returns true
        assertThat(policy.streamingTier()).isEqualTo(LosslessQualityTier.CD)
        assertThat(cache.get(1L)).isNull()
    }


    @Test fun `prefer Tidal AAC is off by default and exposed when on`() = runTest {
        setup(cellular = false)
        assertThat(policy.preferTidalAac()).isFalse()
        setup(cellular = false, preferTidalAac = true)
        assertThat(policy.preferTidalAac()).isTrue()
    }

    /** A FLAC link cached while the switch was off must not keep playing once it's on (and back). */
    @Test fun `flipping prefer Tidal AAC drops the cached URLs`() = runTest {
        setup(cellular = false)
        policy.preferTidalAac()
        cache.put(1L, StreamUrl(url = "https://cdn/1.flac?etsp=1", expiresAtMs = Long.MAX_VALUE))

        coEvery { prefs.preferTidalAacNow() } returns true
        policy.preferTidalAac()                      // what the registry asks at each resolve
        assertThat(cache.get(1L)).isNull()
    }

    /** The debug "skip Qobuz" toggle changes which rung serves, so a flip must drop cached Qobuz URLs. */
    @Test fun `flipping the debug skip-Qobuz toggle drops the cached URLs`() = runTest {
        setup(cellular = false)
        policy.preferTidalAac()
        cache.put(1L, StreamUrl(url = "https://cdn/1.flac?etsp=1", expiresAtMs = Long.MAX_VALUE))

        coEvery { streamingPreference.isSkipQobuz() } returns true
        policy.preferTidalAac()                      // what the registry asks at each resolve
        assertThat(cache.get(1L)).isNull()
    }

    /** A Qobuz link cached before "Play my cloud copies first" was turned on must not keep winning (#679). */
    @Test fun `flipping prefer cloud copies drops the cached URLs`() = runTest {
        setup(cellular = false)
        assertThat(policy.preferCloudCopies()).isFalse()
        cache.put(1L, StreamUrl(url = "https://cdn/1.flac?etsp=1", expiresAtMs = Long.MAX_VALUE))

        coEvery { prefs.preferCloudCopiesNow() } returns true
        assertThat(policy.preferCloudCopies()).isTrue()   // what the registry asks at each resolve
        assertThat(cache.get(1L)).isNull()
    }

    private fun flacLink() = StreamUrl(
        url = "https://qobuz.example/7.flac", expiresAtMs = System.currentTimeMillis() + 3_600_000L,
        codec = "flac", bitsPerSample = 16, origin = "qbdlx",
    )

    /**
     * The review case: an album played on Wi-Fi left FLAC links in the cache. The moment the
     * network callback reports mobile data (switch on), the cache is cleared, before any resolve.
     */
    @Test fun `a FLAC link saved on Wi-Fi is dropped the moment the network turns metered`() = runTest {
        setup(cellular = false, saveData = true)
        val metered = MutableStateFlow(false)
        every { connectivity.meteredChanges() } returns metered
        every { prefs.saveData } returns flowOf(true)
        val seen = mutableListOf<Boolean>()
        val watch = launch(UnconfinedTestDispatcher(testScheduler)) { policy.dataSaverChanges().collect { seen += it } }
        runCurrent() // the decision at the start of the watch, on the old network
        cache.put(7L, flacLink())
        assertThat(cache.get(7L)).isNotNull()

        setup(cellular = true, saveData = true)
        metered.value = true
        runCurrent()

        assertThat(seen).containsExactly(false, true).inOrder()
        assertThat(cache.get(7L)).isNull()
        watch.cancel()
    }

    @Test fun `turning the switch on while on mobile data drops the cache at once`() = runTest {
        setup(cellular = true, saveData = false)
        val saveData = MutableStateFlow(false)
        every { connectivity.meteredChanges() } returns MutableStateFlow(true)
        every { prefs.saveData } returns saveData
        val seen = mutableListOf<Boolean>()
        val watch = launch(UnconfinedTestDispatcher(testScheduler)) { policy.dataSaverChanges().collect { seen += it } }
        runCurrent() // the decision at the start of the watch, on the old network
        cache.put(7L, flacLink())

        setup(cellular = true, saveData = true)
        saveData.value = true
        runCurrent()

        assertThat(seen).containsExactly(false, true).inOrder()
        assertThat(cache.get(7L)).isNull()
        watch.cancel()
    }

    /** Watching starts on the network the last resolve saw: nothing changed, so nothing is dropped. */
    @Test fun `starting the watch on an unchanged network keeps the cache`() = runTest {
        setup(cellular = false, saveData = true)
        assertThat(policy.dataSaverApplies()).isFalse() // a resolve on Wi-Fi
        cache.put(7L, flacLink())
        every { connectivity.meteredChanges() } returns MutableStateFlow(false)
        every { prefs.saveData } returns flowOf(true)

        val watch = launch(UnconfinedTestDispatcher(testScheduler)) { policy.dataSaverChanges().collect {} }
        runCurrent()

        assertThat(cache.get(7L)).isNotNull()
        watch.cancel()
    }
}
