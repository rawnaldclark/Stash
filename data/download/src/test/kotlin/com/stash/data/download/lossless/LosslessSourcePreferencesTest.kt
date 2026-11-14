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
        prefs.clearBitchordServer()
        prefs.setLosslessOfflineDismissed(false)
        prefs.setPlayOrder(null)
        prefs.writeRawForTest { it.remove(stringPreferencesKey("play_order_off")) }
    }

    /** The play order (add-ons phase 4 §2.8): nothing saved is automatic; a drag saves the whole order. */
    @Test fun `the play order is null until it is saved, and saving null goes back to automatic`() = runTest {
        assertThat(prefs.playOrderNow()).isNull()
        prefs.setPlayOrder(listOf("addon:mine", "qobuz", "tidal", "qobuz"))
        assertThat(prefs.playOrderNow()).containsExactly("addon:mine", "qobuz", "tidal").inOrder()
        prefs.setPlayOrder(null)
        assertThat(prefs.playOrderNow()).isNull()
        prefs.setPlayOrder(emptyList())
        assertThat(prefs.playOrder.first()).isNull()
    }

    @Test fun `the rows switched off are kept as a set, and the last one switched on leaves no key`() = runTest {
        assertThat(prefs.playOrderOff.first()).isEmpty()
        prefs.setPlayOrderOff("tidal", off = true)
        prefs.setPlayOrderOff("addon:builtin", off = true)
        prefs.setPlayOrderOff("tidal", off = true)
        assertThat(prefs.playOrderOff.first()).containsExactly("tidal", "addon:builtin")
        prefs.setPlayOrderOff("tidal", off = false)
        prefs.setPlayOrderOff("addon:builtin", off = false)
        assertThat(prefs.playOrderOff.first()).isEmpty()
    }

    /** The priority list no screen ever wrote is gone with the play order: the startup purge deletes a stored one. */
    @Test fun `the retired priority list is purged`() = runTest {
        var stored: String? = "x"
        prefs.writeRawForTest { it[stringPreferencesKey("priority_order")] = "tidal_bitchord,qbdlx_qobuz" }
        prefs.purgeRetiredSourceKeys()
        prefs.writeRawForTest { stored = it[stringPreferencesKey("priority_order")] }
        assertThat(stored).isNull()
    }

    /**
     * The old Custom Tidal server keys (add-ons phase 4 §2.10): nothing writes them any more; they
     * are read as an older build stored them, a blank one is none, and both go in one edit.
     */
    @Test fun `the old own Tidal server keys read as stored and are cleared together`() = runTest {
        assertThat(prefs.bitchordEndpoint.first()).isNull()
        prefs.writeRawForTest {
            it[stringPreferencesKey("bitchord_endpoint")] = "https://tidal.example.org"
            it[stringPreferencesKey("bitchord_access_token")] = "   "
        }
        assertThat(prefs.bitchordEndpointNow()).isEqualTo("https://tidal.example.org")
        assertThat(prefs.bitchordAccessTokenNow()).isNull()
        prefs.writeRawForTest { it[stringPreferencesKey("bitchord_access_token")] = "tok" }
        assertThat(prefs.bitchordAccessTokenNow()).isEqualTo("tok")
        prefs.clearBitchordServer()
        assertThat(prefs.bitchordEndpointNow()).isNull()
        assertThat(prefs.bitchordAccessTokenNow()).isNull()
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
        prefs.writeRawForTest { left.addAll(listOf(it[endpoint], it[cookie], it[cookieSetAt])) }
        assertThat(left).containsExactly(null, null, null)
    }
}
