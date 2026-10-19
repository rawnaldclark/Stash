package com.stash.data.download.lossless.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.prefs.StreamingPreference
import com.stash.data.download.lossless.LosslessAvailability
import com.stash.data.download.lossless.LosslessQualityTier
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.RoutingRow
import com.stash.data.download.lossless.RoutingState

import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import com.stash.data.download.lossless.relay.LosslessRelayClient
import com.stash.data.download.lossless.relay.RelayEntry
import com.stash.data.download.prefs.StreamingQualityPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The "Lossless" section of the diagnostics bundle: every line a FLAC report
 * needs, no account email, and no network calls when the phone is offline.
 */
class LosslessDiagnosticsContributorTest {

    private val rows = listOf(
        RoutingRow("qobuz", "Your Qobuz account", "alice@example.com", RoutingState.CONNECTED),
        RoutingRow("relay", "Stash lossless", "configured", RoutingState.CONFIGURED),
    )
    private val availability: LosslessAvailability = mockk { every { routingRows } returns flowOf(rows) }
    private val config: LosslessConfigFetcher = mockk {
        every { relays } returns MutableStateFlow(listOf(RelayEntry("https://relay.example.workers.dev")))
        every { updatedAt } returns MutableStateFlow(1_758_000_000L)
        every { enabled } returns false
    }
    private val relayClient: LosslessRelayClient = mockk {
        every { isCooled(any()) } returns false
        every { recentOutcomes() } returns listOf("17:54:01Z relay.example.workers.dev 503 busy 212ms")
    }
    private val losslessPrefs: LosslessSourcePreferences = mockk {
        coEvery { enabledNow() } returns true
        coEvery { qualityTierNow() } returns LosslessQualityTier.HI_RES
        coEvery { downloadModeNow() } returns com.stash.core.model.DownloadMode.LOSSLESS
        coEvery { youtubeFallbackEnabledNow() } returns false
        coEvery { customLosslessEndpointNow() } returns null
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
        context, availability, config, relayClient, losslessPrefs, streamingQuality, streamingPreference,
    ).also { it.nowMs = { 1_758_000_000_000L } }

    @Test fun `reports the mode and the quality settings in plain words`() = runTest {
        val s = contributor().section()
        assertThat(s).contains("Mode:               Download (sync writes files)")
        assertThat(s).contains("Lossless streaming: on")
        assertThat(s).contains("Download quality:   Lossless · Hi-Res (24-bit/96 kHz) · lossy fallback off")
        assertThat(s).contains("Save Data:          off")
        assertThat(s).contains("Stream on cellular: on")
    }

    @Test fun `routing rows read as Settings shows them, minus the account email`() = runTest {
        val s = contributor().section()
        assertThat(s).contains("Your Qobuz account: connected")
        assertThat(s).doesNotContain("alice@example.com")
        assertThat(s).contains("Stash lossless: configured")
    }

    @Test fun `relays are listed by host with their cooldown and the config timestamp`() = runTest {
        every { relayClient.isCooled("https://relay.example.workers.dev") } returns true
        val s = contributor().section()
        assertThat(s).contains("Relay config:       1 relay(s), updated_at=2025-09-16T05:20:00Z")
        assertThat(s).contains("  relay.example.workers.dev: cooled (recent failure)")
        assertThat(s).doesNotContain("https://relay.example.workers.dev") // host only
        assertThat(s).contains("Custom endpoint:    not set")
        assertThat(s).contains("  17:54:01Z relay.example.workers.dev 503 busy 212ms")
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
        assertThat(s).contains("Stash lossless: configured")
    }
}
