package com.stash.data.download.lossless

import android.content.Context
import androidx.datastore.preferences.core.longPreferencesKey
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

    /**
     * The custom relay endpoint left with the shared Qobuz relay, and the squid.wtf captcha
     * cookie with squid.wtf: the one-shot purge deletes what an older build stored.
     */
    @Test fun `the retired-source purge deletes a stored custom relay endpoint and the squid captcha cookie`() = runTest {
        val endpoint = stringPreferencesKey("custom_lossless_endpoint")
        val cookie = stringPreferencesKey("squid_wtf_captcha_verified_at")
        val cookieSetAt = longPreferencesKey("squid_wtf_captcha_set_at_ms")
        prefs.writeRawForTest {
            it[endpoint] = "https://relay.example.org"
            it[cookie] = "1777687404951"
            it[cookieSetAt] = 1_777_687_404_951L
        }
        prefs.purgeRetiredSourceKeys()
        val left = mutableListOf<Any?>()
        prefs.writeRawForTest { left += listOf(it[endpoint], it[cookie], it[cookieSetAt]) }
        assertThat(left).containsExactly(null, null, null)
    }
}
