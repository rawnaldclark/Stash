package com.stash.core.data.sync.workers

import androidx.work.NetworkType
import com.stash.core.model.DownloadNetworkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Background art and metadata work waits for Wi-Fi (UNMETERED) only while Data saver is on; with it
 * off every worker asks for exactly what it did before. The user-triggered paths (a mix made or
 * refreshed by hand, the local re-link, downloads) never change.
 */
class DataSaverConstraintsTest {

    @Test
    fun `backgroundNetworkType is UNMETERED with the switch on, the old type with it off`() {
        assertEquals(NetworkType.UNMETERED, backgroundNetworkType(dataSaverOn = true))
        assertEquals(NetworkType.CONNECTED, backgroundNetworkType(dataSaverOn = false))
        assertEquals(NetworkType.NOT_REQUIRED, backgroundNetworkType(dataSaverOn = false, otherwise = NetworkType.NOT_REQUIRED))
        assertEquals(NetworkType.UNMETERED, backgroundNetworkType(dataSaverOn = true, otherwise = NetworkType.NOT_REQUIRED))
    }

    @Test
    fun `art backfill - any connection, Wi-Fi under Data saver`() {
        assertEquals(NetworkType.CONNECTED, ArtBackfillWorker.constraints(dataSaverOn = false).requiredNetworkType)
        assertEquals(NetworkType.UNMETERED, ArtBackfillWorker.constraints(dataSaverOn = true).requiredNetworkType)
    }

    @Test
    fun `mix refresh periodic - no network constraint as before, Wi-Fi under Data saver, battery always`() {
        val off = StashMixRefreshWorker.periodicConstraints(dataSaverOn = false)
        assertEquals(NetworkType.NOT_REQUIRED, off.requiredNetworkType)
        assertTrue(off.requiresBatteryNotLow())
        val on = StashMixRefreshWorker.periodicConstraints(dataSaverOn = true)
        assertEquals(NetworkType.UNMETERED, on.requiredNetworkType)
        assertTrue(on.requiresBatteryNotLow())
    }

    @Test
    fun `mix refresh one-shot - the automatic full refresh waits for Wi-Fi, the local re-link never does`() {
        assertEquals(
            NetworkType.CONNECTED,
            StashMixRefreshWorker.oneShotConstraints(materializeOnly = false, dataSaverOn = false).requiredNetworkType,
        )
        assertEquals(
            NetworkType.UNMETERED,
            StashMixRefreshWorker.oneShotConstraints(materializeOnly = false, dataSaverOn = true).requiredNetworkType,
        )
        for (on in listOf(false, true)) {
            assertEquals(
                "dataSaver=$on",
                NetworkType.CONNECTED,
                StashMixRefreshWorker.oneShotConstraints(materializeOnly = true, dataSaverOn = on).requiredNetworkType,
            )
        }
    }

    @Test
    fun `discovery periodic - Any network waits for Wi-Fi under Data saver, the Wi-Fi modes are unchanged`() {
        for (mode in DownloadNetworkMode.values()) {
            assertEquals(
                "mode=$mode with Data saver off is the mode's own constraint",
                constraintsFor(mode),
                StashDiscoveryWorker.periodicConstraints(mode, dataSaverOn = false),
            )
        }
        assertEquals(
            NetworkType.UNMETERED,
            StashDiscoveryWorker.periodicConstraints(DownloadNetworkMode.ANY_NETWORK, dataSaverOn = true).requiredNetworkType,
        )
        assertEquals(
            constraintsFor(DownloadNetworkMode.WIFI_AND_CHARGING),
            StashDiscoveryWorker.periodicConstraints(DownloadNetworkMode.WIFI_AND_CHARGING, dataSaverOn = true),
        )
    }

    @Test
    fun `track info - periodic and first run wait for Wi-Fi under Data saver only`() {
        assertEquals(NetworkType.CONNECTED, TrackInfoEnrichmentWorker.periodicConstraints(dataSaverOn = false).requiredNetworkType)
        assertEquals(NetworkType.UNMETERED, TrackInfoEnrichmentWorker.periodicConstraints(dataSaverOn = true).requiredNetworkType)
        assertTrue(TrackInfoEnrichmentWorker.periodicConstraints(dataSaverOn = true).requiresBatteryNotLow())
        assertEquals(NetworkType.NOT_REQUIRED, TrackInfoEnrichmentWorker.firstRunConstraints(dataSaverOn = false).requiredNetworkType)
        assertEquals(NetworkType.UNMETERED, TrackInfoEnrichmentWorker.firstRunConstraints(dataSaverOn = true).requiredNetworkType)
    }

    @Test
    fun `tagging periodic - Any network waits for Wi-Fi under Data saver, like the discovery drain`() {
        for (mode in DownloadNetworkMode.values()) {
            assertEquals("mode=$mode", constraintsFor(mode), TagEnrichmentWorker.periodicConstraints(mode, dataSaverOn = false))
            assertEquals(
                "mode=$mode",
                StashDiscoveryWorker.periodicConstraints(mode, dataSaverOn = true),
                TagEnrichmentWorker.periodicConstraints(mode, dataSaverOn = true),
            )
        }
        assertEquals(
            NetworkType.UNMETERED,
            TagEnrichmentWorker.periodicConstraints(DownloadNetworkMode.ANY_NETWORK, dataSaverOn = true).requiredNetworkType,
        )
    }
}
