package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.prefs.StreamingPreference
import com.stash.data.download.lossless.LosslessQualityTier
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.prefs.StreamingQualityPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class StreamQualityPolicyTest {

    private val connectivity = mockk<ConnectivityMonitor>()
    private val prefs = mockk<StreamingQualityPreferences>()
    private val cache = StreamUrlCache()
    private val losslessPrefs = mockk<LosslessSourcePreferences>()
    private val streamingPreference = mockk<StreamingPreference> {
        coEvery { isSkipQobuz() } returns false
    }
    private val policy = StreamQualityPolicy(connectivity, prefs, cache, losslessPrefs, streamingPreference)

    private fun setup(
        cellular: Boolean,
        wifi: LosslessQualityTier = LosslessQualityTier.MAX,
        cell: LosslessQualityTier = LosslessQualityTier.CD,
        saveData: Boolean = false,
        preferTidalAac: Boolean = false,
    ) {
        every { connectivity.isCellular() } returns cellular
        coEvery { prefs.wifiTierNow() } returns wifi
        coEvery { prefs.cellularTierNow() } returns cell
        coEvery { prefs.saveDataNow() } returns saveData
        coEvery { prefs.preferTidalAacNow() } returns preferTidalAac
        coEvery { prefs.preferCloudCopiesNow() } returns false
        coEvery { losslessPrefs.enabledNow() } returns true
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
     * Save Data never leaves lossless: it is the LOWEST lossless tier (CD, ~28 MB / 4 min
     * against 70 for Hi-Res and 140 for Max) on every network. A user who wants less than
     * FLAC turns lossless off; on that lossy path Save Data asks YouTube for its lowest
     * audio quality instead ([saveData] is what the YouTube resolver reads).
     */
    @Test fun `save data streams CD on wifi`() = runTest {
        setup(cellular = false, wifi = LosslessQualityTier.MAX, saveData = true)
        assertThat(policy.streamingTier()).isEqualTo(LosslessQualityTier.CD)
    }

    @Test fun `save data streams CD on cellular even when the cellular tier is higher`() = runTest {
        setup(cellular = true, cell = LosslessQualityTier.HI_RES, saveData = true)
        assertThat(policy.streamingTier()).isEqualTo(LosslessQualityTier.CD)
    }

    @Test fun `save data is exposed for the lossy path`() = runTest {
        setup(cellular = false, saveData = true)
        assertThat(policy.saveData()).isTrue()
        setup(cellular = false, saveData = false)
        assertThat(policy.saveData()).isFalse()
    }

    /**
     * The Lossless switch governs streaming (2026-09-05). Off, the chain skips the lossless
     * sources — but a FLAC link cached in the last hour would still play. The lossy path
     * reads [StreamQualityPolicy.saveData], so that read must notice the switch and clear.
     */
    @Test fun `flipping the lossless switch drops the cached URLs`() = runTest {
        setup(cellular = false, wifi = LosslessQualityTier.HI_RES)
        policy.streamingTier()
        cache.put(1L, StreamUrl(url = "https://cdn/1.flac?etsp=1", expiresAtMs = Long.MAX_VALUE))

        coEvery { losslessPrefs.enabledNow() } returns false
        policy.saveData()                            // what the YouTube resolver asks
        assertThat(cache.get(1L)).isNull()
    }

    /** With a CD tier already chosen, flipping Save Data changes only the lossy path — the cache must still go. */
    @Test fun `flipping save data with a CD tier still drops the cached URLs`() = runTest {
        setup(cellular = false, wifi = LosslessQualityTier.CD, saveData = false)
        policy.streamingTier()
        cache.put(1L, StreamUrl(url = "https://cdn/1?etsp=1", expiresAtMs = Long.MAX_VALUE))

        setup(cellular = false, wifi = LosslessQualityTier.CD, saveData = true)
        assertThat(policy.streamingTier()).isEqualTo(LosslessQualityTier.CD)
        assertThat(cache.get(1L)).isNull()
    }

    /**
     * A cached stream URL is only right for the tier it was minted under. Flipping Save
     * Data, changing a tier, or walking from Wi-Fi onto cellular changes what the next
     * resolve asks for — and must also stop the hour-old URLs from the OLD tier being served.
     */
    @Test fun `a tier change between resolves drops every cached stream URL`() = runTest {
        setup(cellular = false, wifi = LosslessQualityTier.HI_RES)
        policy.streamingTier()
        cache.put(1L, StreamUrl(url = "https://cdn/1?etsp=1", expiresAtMs = Long.MAX_VALUE))

        policy.streamingTier()                       // same tier — the cache stays
        assertThat(cache.get(1L)).isNotNull()

        every { connectivity.isCellular() } returns true   // Wi-Fi → cellular: CD now
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
}
