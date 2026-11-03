package com.stash.core.media

import android.media.AudioDeviceInfo
import com.stash.core.media.service.HeadphoneConnectionObserver
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HeadphoneConnectionObserverTest {

    private fun mockDevice(type: Int, isSink: Boolean = true): AudioDeviceInfo {
        return mockk<AudioDeviceInfo> {
            every { this@mockk.type } returns type
            every { this@mockk.isSink } returns isSink
        }
    }

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
