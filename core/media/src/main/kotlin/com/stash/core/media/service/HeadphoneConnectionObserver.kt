package com.stash.core.media.service

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.stash.core.data.prefs.HeadphonePreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Monitors audio output routing to notify when headphones reconnect.
 *
 * Specifically:
 * - Ignores devices that were already connected when the observer started.
 * - Filters for genuine media headphones (Bluetooth A2DP, BLE headset, wired, USB headset or DAC).
 *   Wired headsets arrive here too, so no ACTION_HEADSET_PLUG receiver is needed.
 * - Debounces rapid multi-profile events into a single callback.
 * - Calls back only while the "Resume when headphones reconnect" setting is on.
 *
 * @param scope a main-thread scope (the service's): [onHeadphonesConnected] runs in it, so the
 *   callback reads the player in the same main-thread moment as the setting.
 */
class HeadphoneConnectionObserver(
    context: Context,
    private val scope: CoroutineScope,
    private val headphonePreference: HeadphonePreference,
    private val onHeadphonesConnected: () -> Unit,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val knownDeviceIds = mutableSetOf<Int>()
    private var debounceJob: Job? = null
    private var isStarted = false

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            val newHeadphones = addedDevices?.filter {
                isHeadphone(it) && !knownDeviceIds.contains(it.id)
            }
            addedDevices?.forEach { knownDeviceIds.add(it.id) }
            if (!newHeadphones.isNullOrEmpty()) {
                notifyHeadphonesConnected()
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            removedDevices?.forEach { knownDeviceIds.remove(it.id) }
        }
    }

    fun start() {
        if (isStarted) return
        isStarted = true

        // Snapshot existing devices so we only react to newly connected devices. Registering
        // reports every connected device as "added" straight away; this set makes that a no-op.
        audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.forEach {
            knownDeviceIds.add(it.id)
        }

        audioManager?.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
    }

    fun release() {
        if (!isStarted) return
        isStarted = false

        debounceJob?.cancel()
        debounceJob = null
        knownDeviceIds.clear()

        try {
            audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
        } catch (_: Exception) {}
    }

    private fun notifyHeadphonesConnected() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            // Debounce multiple events (e.g. A2DP connection + route registration) into a single resume
            delay(DEBOUNCE_MS)
            if (headphonePreference.resumeOnReconnect.first()) {
                onHeadphonesConnected()
            }
        }
    }

    companion object {
        /** A Bluetooth headset's profiles (A2DP, SCO, LE) land as separate adds within this window. */
        internal const val DEBOUNCE_MS = 400L

        fun isHeadphone(device: AudioDeviceInfo): Boolean {
            if (!device.isSink) return false
            return when (device.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                // A USB DAC dongle: unplugging one pauses (becoming noisy), so plugging it back resumes.
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLE_HEADSET -> true
                else -> false
            }
        }
    }
}
