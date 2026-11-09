package com.stash.data.download.lossless

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.DownloadQueueDao
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LosslessSourcePreferencesTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = LosslessSourcePreferences(ctx, mockk<DownloadQueueDao>(relaxed = true))

    /** The preferencesDataStore delegate is process-wide; start each test clean (codebase convention). */
    @Before fun clear() = runBlocking {
        prefs.setBitchordEndpoint(null)
        prefs.setLosslessOfflineDismissed(false)
    }

    @Test fun `own Tidal server is null by default, normalised on set, cleared on blank`() = runTest {
        assertThat(prefs.bitchordEndpoint.first()).isNull()
        prefs.setBitchordEndpoint("  https://tidal.example.org/  ")
        assertThat(prefs.bitchordEndpointNow()).isEqualTo("https://tidal.example.org")
        prefs.setBitchordEndpoint("http://insecure.example") // not https -> rejected
        assertThat(prefs.bitchordEndpointNow()).isNull()
        prefs.setBitchordEndpoint("https://tidal.example.org")
        prefs.setBitchordEndpoint("   ")
        assertThat(prefs.bitchordEndpointNow()).isNull()
    }

    /** The custom relay endpoint left with the shared Qobuz relay: the one-shot purge deletes what an older build stored. */
    @Test fun `the retired-source purge deletes a stored custom relay endpoint`() = runTest {
        val key = stringPreferencesKey("custom_lossless_endpoint")
        prefs.writeRawForTest { it[key] = "https://relay.example.org" }
        prefs.purgeRetiredSourceKeys()
        var left: String? = "unread"
        prefs.writeRawForTest { left = it[key] }
        assertThat(left).isNull()
    }
}
