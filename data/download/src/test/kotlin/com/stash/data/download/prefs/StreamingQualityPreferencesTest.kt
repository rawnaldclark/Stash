package com.stash.data.download.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import androidx.datastore.preferences.core.edit
import com.stash.data.download.lossless.LosslessQualityTier
import com.stash.data.download.lossless.LosslessSourcePreferences
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class StreamingQualityPreferencesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // The extension-property DataStore is a single shared instance for the
    // whole test process, and DataStore caches its Preferences in memory —
    // deleting the on-disk file alone doesn't evict that cache, so writes
    // from one case bleed into the next. Clear the live store through the
    // (internal) extension property AND delete the backing file before each
    // test so every case starts from a true empty-keys baseline.
    @Before fun clean() {
        runBlocking { context.streamingQualityDataStore.edit { it.clear() } }
        File(context.filesDir, "datastore/streaming_quality_preferences.preferences_pb").delete()
    }

    private fun newPrefs(downloadTier: LosslessQualityTier): StreamingQualityPreferences {
        val lossless = mockk<LosslessSourcePreferences>()
        coEvery { lossless.qualityTierNow() } returns downloadTier
        return StreamingQualityPreferences(context, lossless)
    }

    @Test
    fun `defaults before migration`() = runTest {
        val prefs = newPrefs(LosslessQualityTier.MAX)
        assertThat(prefs.wifiTier.first()).isEqualTo(LosslessQualityTier.HI_RES)
        assertThat(prefs.cellularTier.first()).isEqualTo(LosslessQualityTier.CD)
        assertThat(prefs.saveData.first()).isFalse()
        assertThat(prefs.preferTidalAacNow()).isFalse() // opt-in
    }

    @Test
    fun `migrateIfNeeded seeds wifi from download tier and cellular to CD`() = runTest {
        val prefs = newPrefs(LosslessQualityTier.MAX)
        prefs.migrateIfNeeded()
        assertThat(prefs.wifiTier.first()).isEqualTo(LosslessQualityTier.MAX)
        assertThat(prefs.cellularTier.first()).isEqualTo(LosslessQualityTier.CD)
    }

    @Test
    fun `migrateIfNeeded is a no-op once a tier is explicitly set`() = runTest {
        val prefs = newPrefs(LosslessQualityTier.MAX)
        prefs.setWifiTier(LosslessQualityTier.CD)
        prefs.migrateIfNeeded() // download tier is MAX, but user already chose CD
        assertThat(prefs.wifiTier.first()).isEqualTo(LosslessQualityTier.CD)
    }

    @Test
    fun `setters round-trip`() = runTest {
        val prefs = newPrefs(LosslessQualityTier.HI_RES)
        prefs.setCellularTier(LosslessQualityTier.HI_RES)
        prefs.setSaveData(true)
        assertThat(prefs.cellularTier.first()).isEqualTo(LosslessQualityTier.HI_RES)
        assertThat(prefs.saveData.first()).isTrue()
    }

    @Test
    fun `prefer Tidal AAC round-trips`() = runTest {
        val prefs = newPrefs(LosslessQualityTier.HI_RES)
        prefs.setPreferTidalAac(true)
        assertThat(prefs.preferTidalAacNow()).isTrue()
        prefs.setPreferTidalAac(false)
        assertThat(prefs.preferTidalAac.first()).isFalse()
    }
}
