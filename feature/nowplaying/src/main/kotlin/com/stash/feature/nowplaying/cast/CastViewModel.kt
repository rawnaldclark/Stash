package com.stash.feature.nowplaying.cast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.media.cast.CastConnection
import com.stash.core.media.cast.CastDevice
import com.stash.core.media.cast.CastDevices
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/** Now Playing's cast button and speaker sheet (spec 2026-10-06 §5). */
@HiltViewModel
class CastViewModel @Inject constructor(
    private val devices: CastDevices,
) : ViewModel() {
    val connection: StateFlow<CastConnection> = devices.connection

    val speakers: StateFlow<List<CastDevice>> = devices.devices

    /** The speaker's volume (0..1) while connected, else null. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val volume: StateFlow<Float?> = devices.remote
        .flatMapLatest { remote ->
            if (remote == null) {
                flowOf(null)
            } else {
                callbackFlow<Float?> {
                    val listener = { trySend(remote.status.volume); Unit }
                    remote.addListener(listener)
                    trySend(remote.status.volume)
                    awaitClose { remote.removeListener(listener) }
                }
            }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private var scanning = false

    fun startScan() {
        scanning = true
        devices.startScan()
    }

    fun stopScan() {
        scanning = false
        devices.stopScan()
    }

    fun connect(device: CastDevice) = devices.connect(device.id)
    fun disconnect() = devices.disconnect()
    fun setVolume(level: Float) = devices.remote.value?.setVolume(level)

    override fun onCleared() {
        if (scanning) devices.stopScan()
    }
}
