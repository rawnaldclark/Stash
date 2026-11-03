package com.stash.core.media.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
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
 * - Filters for genuine media headphones (A2DP, BLE headset, wired, USB).
 * - Disregards initial sticky broadcasts from ACTION_HEADSET_PLUG.
 * - Debounces rapid multi-profile events into a single callback.
 */
class HeadphoneConnectionObserver(
    private val context: Context,
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

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (isInitialStickyBroadcast) return
            if (intent?.action == Intent.ACTION_HEADSET_PLUG) {
                val state = intent.getIntExtra("state", -1)
                if (state == 1) {
                    notifyHeadphonesConnected()
                }
            }
        }
    }

    fun start() {
        if (isStarted) return
        isStarted = true

        // Snapshot existing devices so we only react to newly connected devices
        audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.forEach {
            knownDeviceIds.add(it.id)
        }

        audioManager?.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)

        val filter = IntentFilter(Intent.ACTION_HEADSET_PLUG)
        ContextCompat.registerReceiver(
            context,
            broadcastReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
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

        try {
            context.unregisterReceiver(broadcastReceiver)
        } catch (_: Exception) {}
    }

    private fun notifyHeadphonesConnected() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            // Debounce multiple events (e.g. A2DP connection + route registration) into a single resume
            delay(400)
            if (headphonePreference.resumeOnReconnect.first()) {
                mainHandler.post { onHeadphonesConnected() }
            }
        }
    }

    companion object {
        fun isHeadphone(device: AudioDeviceInfo): Boolean {
            if (!device.isSink) return false
            return when (device.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLE_HEADSET -> true
                else -> false
            }
        }
    }
}
