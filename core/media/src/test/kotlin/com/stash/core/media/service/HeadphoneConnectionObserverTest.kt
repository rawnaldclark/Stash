package com.stash.core.media.service

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.prefs.HeadphonePreference
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAudioManager

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HeadphoneConnectionObserverTest {

    private class FakeHeadphonePreference(on: Boolean) : HeadphonePreference {
        override val resumeOnReconnect = MutableStateFlow(on)
        override suspend fun setResumeOnReconnect(enabled: Boolean) {
            resumeOnReconnect.value = enabled
        }
    }

    private val scope = TestScope()
    private val preference = FakeHeadphonePreference(on = true)
    private var callbacks = 0
    private lateinit var audio: ShadowAudioManager
    private lateinit var observer: HeadphoneConnectionObserver

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        audio = shadowOf(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
        observer = HeadphoneConnectionObserver(context, scope, preference) { callbacks++ }
    }

    @After
    fun tearDown() {
        observer.release()
    }

    private fun mockDevice(type: Int, isSink: Boolean = true, id: Int = 0): AudioDeviceInfo {
        return mockk<AudioDeviceInfo> {
            every { this@mockk.type } returns type
            every { this@mockk.isSink } returns isSink
            every { this@mockk.id } returns id
        }
    }

    // ---- connection events ----

    @Test
    fun devicesAlreadyConnectedAtRegistration_areIgnored() {
        // Registering reports every connected device as added; the snapshot must swallow that.
        audio.addOutputDevice(mockDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, id = 1), false)
        audio.addOutputDevice(mockDevice(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, id = 2), false)
        observer.start()
        scope.advanceUntilIdle()
        assertEquals(0, callbacks)
    }

    @Test
    fun newHeadphones_callBackOnce_afterTheDebounce() {
        observer.start()
        // A Bluetooth headset lands as several profiles in quick succession.
        audio.addOutputDevice(mockDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, id = 10), true)
        audio.addOutputDevice(mockDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, id = 11), true)
        audio.addOutputDevice(mockDevice(AudioDeviceInfo.TYPE_BLE_HEADSET, id = 12), true)
        scope.advanceTimeBy(HeadphoneConnectionObserver.DEBOUNCE_MS - 1)
        scope.runCurrent()
        assertEquals(0, callbacks)
        scope.advanceUntilIdle()
        assertEquals(1, callbacks)
    }

    @Test
    fun headphonesConnectedAtStart_unpluggedAndPluggedBack_callBack() {
        val headphones = mockDevice(AudioDeviceInfo.TYPE_WIRED_HEADSET, id = 20)
        audio.addOutputDevice(headphones, false)
        observer.start()
        audio.removeOutputDevice(headphones, true)
        audio.addOutputDevice(headphones, true)
        scope.advanceUntilIdle()
        assertEquals(1, callbacks)
    }

    @Test
    fun usbDacPluggedIn_callsBack() {
        observer.start()
        audio.addOutputDevice(mockDevice(AudioDeviceInfo.TYPE_USB_DEVICE, id = 30), true)
        scope.advanceUntilIdle()
        assertEquals(1, callbacks)
    }

    @Test
    fun speakerOrCallOnlyDevice_isIgnored() {
        observer.start()
        audio.addOutputDevice(mockDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, id = 40), true)
        audio.addOutputDevice(mockDevice(AudioDeviceInfo.TYPE_BLE_SPEAKER, id = 41), true)
        scope.advanceUntilIdle()
        assertEquals(0, callbacks)
    }

    @Test
    fun settingOff_noCallback() {
        preference.resumeOnReconnect.value = false
        observer.start()
        audio.addOutputDevice(mockDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, id = 50), true)
        scope.advanceUntilIdle()
        assertEquals(0, callbacks)
    }

    @Test
    fun release_dropsAPendingCallback_andStopsListening() {
        observer.start()
        audio.addOutputDevice(mockDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, id = 60), true)
        observer.release() // inside the debounce
        audio.addOutputDevice(mockDevice(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, id = 61), true)
        scope.advanceUntilIdle()
        assertEquals(0, callbacks)
    }

    // ---- the headphone filter ----

    @Test
    fun isHeadphone_wiredHeadset_returnsTrue() {
        assertTrue(HeadphoneConnectionObserver.isHeadphone(mockDevice(AudioDeviceInfo.TYPE_WIRED_HEADSET)))
    }

    @Test
    fun isHeadphone_wiredHeadphones_returnsTrue() {
        assertTrue(HeadphoneConnectionObserver.isHeadphone(mockDevice(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)))
    }

    @Test
    fun isHeadphone_usbHeadset_returnsTrue() {
        assertTrue(HeadphoneConnectionObserver.isHeadphone(mockDevice(AudioDeviceInfo.TYPE_USB_HEADSET)))
    }

    @Test
    fun isHeadphone_usbDacDongle_returnsTrue() {
        assertTrue(HeadphoneConnectionObserver.isHeadphone(mockDevice(AudioDeviceInfo.TYPE_USB_DEVICE)))
    }

    @Test
    fun isHeadphone_bluetoothA2dp_returnsTrue() {
        assertTrue(HeadphoneConnectionObserver.isHeadphone(mockDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)))
    }

    @Test
    fun isHeadphone_bleHeadset_returnsTrue() {
        assertTrue(HeadphoneConnectionObserver.isHeadphone(mockDevice(AudioDeviceInfo.TYPE_BLE_HEADSET)))
    }

    @Test
    fun isHeadphone_bluetoothSco_returnsFalse() {
        // Calls-only Bluetooth profile (e.g. car kit or mono phone earpiece) should not start music
        assertFalse(HeadphoneConnectionObserver.isHeadphone(mockDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)))
    }

    @Test
    fun isHeadphone_bleSpeaker_returnsFalse() {
        assertFalse(HeadphoneConnectionObserver.isHeadphone(mockDevice(AudioDeviceInfo.TYPE_BLE_SPEAKER)))
    }

    @Test
    fun isHeadphone_builtinSpeaker_returnsFalse() {
        assertFalse(HeadphoneConnectionObserver.isHeadphone(mockDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)))
    }

    @Test
    fun isHeadphone_builtinEarpiece_returnsFalse() {
        assertFalse(HeadphoneConnectionObserver.isHeadphone(mockDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)))
    }

    @Test
    fun isHeadphone_inputDevice_returnsFalse() {
        assertFalse(HeadphoneConnectionObserver.isHeadphone(mockDevice(AudioDeviceInfo.TYPE_WIRED_HEADSET, isSink = false)))
    }
}
