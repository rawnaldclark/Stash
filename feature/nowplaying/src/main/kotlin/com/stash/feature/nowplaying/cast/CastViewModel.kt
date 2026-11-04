package com.stash.feature.nowplaying.cast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.media.cast.BrowserOutput
import com.stash.core.media.cast.BrowserOutputs
import com.stash.core.media.cast.BrowserSession
import com.stash.core.media.cast.CastConnection
import com.stash.core.media.cast.CastDevice
import com.stash.core.media.cast.CastDevices
import com.stash.core.media.cast.CastRemote
import com.stash.core.media.cast.WebCastRemote
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * Where the music plays, for the "Play on" button, its sheet, Now Playing's line and the mini player (Remote v2, spec
 * 2026-11-03 §3, §6): this phone, a speaker ([connection]) or a linked browser ([browser]).
 */
data class PlayOnState(
    val connection: CastConnection = CastConnection.Unavailable,
    val browser: BrowserSession? = null,
    /** The space has a linked browser: "Play on" shows even on a phone without Cast. */
    val hasBrowser: Boolean = false,
) {
    /** The button (and the ⋮ row) shows: the phone can Cast, or there is a browser to play on. */
    val available: Boolean get() = connection != CastConnection.Unavailable || hasBrowser || browser != null

    /** The phone can Cast: the sheet lists speakers. */
    val castSupported: Boolean get() = connection != CastConnection.Unavailable

    /** A browser plays the queue (not just picked: also while it starts or reconnects). */
    val onBrowser: Boolean get() = browser != null

    /** The device the music plays on, once it does ("Chrome on Windows", "Living Room"); null on this phone. */
    val playingOn: String?
        get() = browser?.takeIf { it.link != WebCastRemote.Link.STARTING }?.name ?: (connection as? CastConnection.Connected)?.deviceName

    /** The device the music is on its way to ("Starting on…", "Connecting to…"); null otherwise. */
    val startingOn: String?
        get() = browser?.takeIf { it.link == WebCastRemote.Link.STARTING }?.name ?: (connection as? CastConnection.Connecting)?.deviceName

    /** The music is somewhere other than this phone, or on its way there. */
    val elsewhere: Boolean get() = playingOn != null || startingOn != null
}

/** Now Playing's "Play on" button and sheet (spec 2026-10-06 §5, Remote v2 §3), and the mini player's device line. */
@HiltViewModel
class CastViewModel @Inject constructor(
    private val devices: CastDevices,
    private val browsers: BrowserOutputs,
) : ViewModel() {
    val connection: StateFlow<CastConnection> = devices.connection

    val speakers: StateFlow<List<CastDevice>> = devices.devices

    /** What happened to a browser session, once each ([PlayOnNotices] shows them). */
    val notices: kotlinx.coroutines.flow.SharedFlow<com.stash.core.media.cast.BrowserNotice> = browsers.notices

    /** Every linked browser with what picking it would do, kept fresh while the sheet shows. */
    val browserList: StateFlow<List<BrowserOutput>> = browsers.browsers

    /** Where the music plays. */
    val playOn: StateFlow<PlayOnState> = combine(devices.connection, browsers.session, browsers.hasBrowser, ::PlayOnState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayOnState(devices.connection.value))

    /** The speaker's volume (0..1) while connected, else null. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val volume: StateFlow<Float?> = devices.remote
        .flatMapLatest(::volumeOf)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The browser's volume (0..1) while one plays the queue and can set it, else null (Safari on iOS can't). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val browserVolume: StateFlow<Float?> = browsers.remote
        .flatMapLatest(::volumeOf)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun volumeOf(remote: CastRemote?): Flow<Float?> {
        if (remote == null) return flowOf(null)
        return callbackFlow {
            val read = { remote.status.takeIf { it.canSetVolume }?.volume }
            val listener = { trySend(read()); Unit }
            remote.addListener(listener)
            trySend(read())
            awaitClose { remote.removeListener(listener) }
        }
    }

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

    /** Plays the queue on [browser]: false when it can't be picked after all (its tab closed meanwhile). */
    fun connectBrowser(browser: BrowserOutput): Boolean = browsers.connect(browser.device)

    fun setBrowserVolume(level: Float) = browsers.remote.value?.setVolume(level)

    /** "This phone": whatever plays elsewhere comes back here; a browser's music carries on (owner's decision D2). */
    fun playHere() {
        if (browsers.session.value != null) browsers.disconnect()
        if (devices.connection.value is CastConnection.Connected || devices.connection.value is CastConnection.Connecting) {
            devices.disconnect()
        }
    }

    /**
     * "Play on this phone" on a song only this phone has (Remote v2 §5): the music comes back here (D2), then [then] runs once the
     * playback service holds the queue again, so the song plays here instead of being stepped over on the browser.
     */
    fun playHereThen(then: () -> Unit) {
        playHere()
        viewModelScope.launch {
            withTimeoutOrNull(BACK_ON_PHONE_MS) { browsers.served.first { !it } }
            then()
        }
    }

    override fun onCleared() {
        if (scanning) devices.stopScan()
    }

    private companion object {
        /** Coming back is a few calls on the main thread; this only keeps a tap from waiting forever on a stopped service. */
        const val BACK_ON_PHONE_MS = 3_000L
    }
}

/**
 * A queue song only this phone has, while a browser plays the queue (Remote v2 §5, the one rule in [WebSongs]): the browser
 * can't find it, so it is stepped over there and its row is dimmed.
 */
internal val PHONE_ONLY: (com.stash.core.model.Track) -> Boolean = { !com.stash.core.data.weblink.WebSongs.playsOnWeb(it) }
