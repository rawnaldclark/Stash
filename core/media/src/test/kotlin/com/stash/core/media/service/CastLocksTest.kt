package com.stash.core.media.service

import android.net.wifi.WifiManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowPowerManager

/**
 * The cast wake and Wi-Fi locks follow the speaker's idleness: held while it
 * plays, let go while it is paused, ended or failed. Which speaker states
 * count as idle is covered by [isPlayerIdle] and CastSessionPlayerTest; this
 * covers the locks themselves, on a service that is never created.
 */
@RunWith(RobolectricTestRunner::class)
class CastLocksTest {

    private val service = Robolectric.buildService(StashPlaybackService::class.java).get()

    private val wakeLockHeld: Boolean
        get() = ShadowPowerManager.getLatestWakeLock()?.isHeld == true

    private val wifiLockHeld: Boolean
        get() {
            val wifi = ApplicationProvider.getApplicationContext<android.content.Context>()
                .getSystemService(WifiManager::class.java)
            return shadowOf(wifi).activeLockCount > 0
        }

    @Test fun `a playing speaker holds both locks`() {
        service.updateCastLocks(idle = false)

        assertThat(wakeLockHeld).isTrue()
        assertThat(wifiLockHeld).isTrue()
    }

    @Test fun `a paused speaker lets both go`() {
        service.updateCastLocks(idle = false)
        service.updateCastLocks(idle = true)

        assertThat(wakeLockHeld).isFalse()
        assertThat(wifiLockHeld).isFalse()
    }

    @Test fun `play after a pause takes them again`() {
        service.updateCastLocks(idle = false)
        service.updateCastLocks(idle = true)
        service.updateCastLocks(idle = false)

        assertThat(wakeLockHeld).isTrue()
        assertThat(wifiLockHeld).isTrue()
    }

    @Test fun `repeated play events take each lock only once`() {
        service.updateCastLocks(idle = false)
        val first = ShadowPowerManager.getLatestWakeLock()
        service.updateCastLocks(idle = false)

        assertThat(ShadowPowerManager.getLatestWakeLock()).isSameInstanceAs(first)
        service.updateCastLocks(idle = true) // one release frees it: nothing was counted twice
        assertThat(wakeLockHeld).isFalse()
        assertThat(wifiLockHeld).isFalse()
    }

    @Test fun `starting paused takes no locks`() {
        service.updateCastLocks(idle = true)

        assertThat(ShadowPowerManager.getLatestWakeLock()).isNull()
        assertThat(wifiLockHeld).isFalse()
    }
}
