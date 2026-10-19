package com.stash.data.download.lossless

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.model.DownloadMode
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The old Lossless switch decided downloads too (on = FLAC first, off = the lossy chain). Its
 * value becomes the stored download mode once, silently: on → Lossless, off → Opus. After that the
 * switch is streaming only and never moves the download mode.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadModeMigrationTest {

    private lateinit var context: Context
    private lateinit var prefs: LosslessSourcePreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.filesDir.resolve("datastore/lossless_source_preferences.preferences_pb").delete()
        prefs = LosslessSourcePreferences(context, mockk<DownloadQueueDao>(relaxed = true))
        // The DataStore instance outlives a test (one per process): start each from nothing.
        kotlinx.coroutines.runBlocking { prefs.writeRawForTest { it.clear() } }
    }

    @Test
    fun `a fresh install downloads lossless`() = runTest {
        assertEquals(DownloadMode.LOSSLESS, prefs.downloadModeNow())
        assertEquals(DownloadMode.LOSSLESS, prefs.migrateDownloadMode())
    }

    @Test
    fun `lossless was off before the setting existed, so downloads become Opus`() = runTest {
        // Written as the old app wrote it: setEnabled had nothing to pin then. A raw edit, since
        // today's setEnabled pins the mode first.
        writeLegacyLosslessSwitch(false)

        assertEquals(DownloadMode.OPUS, prefs.migrateDownloadMode())
        assertEquals(DownloadMode.OPUS, prefs.downloadModeNow())
    }

    @Test
    fun `lossless was on, so downloads stay lossless, and the switch no longer moves them`() = runTest {
        writeLegacyLosslessSwitch(true)
        prefs.migrateDownloadMode()

        prefs.setEnabled(false) // lossless streaming off

        assertEquals(DownloadMode.LOSSLESS, prefs.downloadModeNow())
        assertEquals(false, prefs.enabledNow())
    }

    @Test
    fun `turning lossless streaming off before the migration ran still keeps lossless downloads`() = runTest {
        // No stored mode yet: setEnabled pins the mode the switch meant before it changes it.
        prefs.setEnabled(false)

        assertEquals(DownloadMode.LOSSLESS, prefs.downloadModeNow())
    }

    @Test
    fun `the migration runs once and never overrides a chosen mode`() = runTest {
        prefs.setDownloadMode(DownloadMode.AAC)
        writeLegacyLosslessSwitch(false)

        assertEquals(DownloadMode.AAC, prefs.migrateDownloadMode())
        assertEquals(DownloadMode.AAC, prefs.downloadModeNow())
    }

    @Test
    fun `an unknown stored name reads as what the switch meant`() {
        assertEquals(DownloadMode.AAC, DownloadMode.fromName("AAC"))
        assertEquals(null, DownloadMode.fromName("MP3"))
        assertEquals(null, DownloadMode.fromName(null))
    }

    /** The `enabled` key as an older build stored it, without a download mode beside it. */
    private suspend fun writeLegacyLosslessSwitch(on: Boolean) {
        prefs.writeRawForTest { it[androidx.datastore.preferences.core.booleanPreferencesKey("enabled")] = on }
    }
}
