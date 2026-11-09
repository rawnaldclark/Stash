package com.stash.data.download.lossless.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.prefs.StreamingPreference
import com.stash.data.download.lossless.LosslessAvailability
import com.stash.data.download.lossless.LosslessQualityTier
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.RoutingRow
import com.stash.data.download.lossless.RoutingState
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import com.stash.data.download.lossless.relay.TidalRelay
import com.stash.data.download.prefs.StreamingQualityPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Test

/**
 * The "Lossless" section of the diagnostics bundle: every line a FLAC report
 * needs, no account email, and no network calls when the phone is offline.
 */
class LosslessDiagnosticsContributorTest {

    private val rows = listOf(
        RoutingRow("qobuz", "Your Qobuz account", "alice@example.com", RoutingState.CONNECTED),
        RoutingRow("tidal", "Tidal", "configured", RoutingState.CONFIGURED),
    )
    private val tidalRelay = MutableStateFlow<TidalRelay?>(TidalRelay("https://tidal.example.workers.dev", "tok"))
    private val availability: LosslessAvailability = mockk { every { routingRows } returns flowOf(rows) }
    private val config: LosslessConfigFetcher = mockk {
        every { tidalRelay } returns this@LosslessDiagnosticsContributorTest.tidalRelay
        every { updatedAt } returns MutableStateFlow(1_758_000_000L)
        every { enabled } returns false
    }
    private val losslessPrefs: LosslessSourcePreferences = mockk {
        coEvery { enabledNow() } returns true
        coEvery { qualityTierNow() } returns LosslessQualityTier.HI_RES
        coEvery { downloadModeNow() } returns com.stash.core.model.DownloadMode.LOSSLESS
        coEvery { youtubeFallbackEnabledNow() } returns false
    }
    private val streamingQuality: StreamingQualityPreferences = mockk { coEvery { saveDataNow() } returns false }
    private val streamingPreference: StreamingPreference = mockk {
        coEvery { current() } returns false // Download mode
        every { streamOnCellular } returns flowOf(true)
    }
    private val connectivity: ConnectivityManager = mockk { every { activeNetwork } returns null } // offline
    private val context: Context = mockk {
        every { getSystemService(ConnectivityManager::class.java) } returns connectivity
    }

    private fun contributor() = LosslessDiagnosticsContributor(
        context, availability, config, OkHttpClient(), losslessPrefs, streamingQuality, streamingPreference,
    ).also { it.nowMs = { 1_758_000_000_000L } }

    @Test fun `reports the mode and the quality settings in plain words`() = runTest {
        val s = contributor().section()
        assertThat(s).contains("Mode:               Offline (sync writes files; only downloads play)")
        assertThat(s).contains("Lossless streaming: on")
        assertThat(s).contains("Download quality:   Lossless · Hi-Res (24-bit/96 kHz) · lossy fallback off")
        assertThat(s).contains("Data saver:         off (metered networks only)")
        assertThat(s).contains("Stream on cellular: on")
    }

    @Test fun `routing rows read as Settings shows them, minus the account email`() = runTest {
        val s = contributor().section()
        assertThat(s).contains("Your Qobuz account: connected")
        assertThat(s).doesNotContain("alice@example.com")
        assertThat(s).contains("Tidal: configured")
    }

    /** Settings' order is the order a song asks them; the bundle must not tell a different story. */
    @Test fun `routing keeps Settings' order`() = runTest {
        every { availability.routingRows } returns flowOf(
            listOf(
                RoutingRow("qobuz", "Your Qobuz account", "not connected", RoutingState.NOT_CONFIGURED),
                RoutingRow("tidal", "Custom Tidal server", "first above CD", RoutingState.CONFIGURED),
            ),
        )
        val s = contributor().section()
        assertThat(s).contains("Custom Tidal server: first above CD")
        assertThat(s.indexOf("Your Qobuz account")).isLessThan(s.indexOf("Custom Tidal server"))
    }

    @Test fun `the config block names the timestamp and the Tidal relay by host - no Qobuz relay blocks`() = runTest {
        val s = contributor().section()
        assertThat(s).contains("Lossless config:    updated_at=2025-09-16T05:20:00Z")
        assertThat(s).contains("Tidal relay:        configured (tidal.example.workers.dev)")
        assertThat(s).doesNotContain("https://tidal.example.workers.dev") // host only
        assertThat(s).doesNotContain("Relay config")
        assertThat(s).doesNotContain("Custom endpoint")
        assertThat(s).doesNotContain("Recent relay answers")
    }

    @Test fun `no Tidal relay in the config says so`() = runTest {
        tidalRelay.value = null
        assertThat(contributor().section()).contains("Tidal relay:        not in the config")
    }

    @Test fun `online, the probes are the Tidal relay's health and the Qobuz CDN`() = runTest {
        val network: Network = mockk()
        val caps: NetworkCapabilities = mockk {
            every { hasTransport(any()) } answers { firstArg<Int>() == NetworkCapabilities.TRANSPORT_WIFI }
            every { hasCapability(any()) } returns true
        }
        every { connectivity.activeNetwork } returns network
        every { connectivity.getNetworkCapabilities(network) } returns caps
        val probed = mutableListOf<String>()
        val c = contributor().also { it.probe = { url -> synchronized(probed) { probed += url }; "HTTP 200 in 1 ms" } }
        val s = c.section()
        assertThat(probed).containsExactly("https://tidal.example.workers.dev/health", "https://streaming-qobuz-std.akamaized.net/")
        assertThat(s).contains("Tidal relay tidal.example.workers.dev: HTTP 200 in 1 ms")
        assertThat(s).doesNotContain("/v1/status")
    }

    @Test fun `offline means no probes and says so`() = runTest {
        var probed = 0
        val c = contributor().also { it.probe = { probed++; "HTTP 200 in 1 ms" } }
        val s = c.section()
        assertThat(s).contains("Network:            none (offline)")
        assertThat(s).contains("Reachability:       offline — probes skipped")
        assertThat(probed).isEqualTo(0)
    }

    @Test fun `a failing block costs one line, not the section`() = runTest {
        coEvery { losslessPrefs.enabledNow() } throws IllegalStateException("datastore closed")
        val s = contributor().section()
        assertThat(s).contains("[settings unavailable: datastore closed]")
        assertThat(s).contains("Tidal: configured")
    }
}
