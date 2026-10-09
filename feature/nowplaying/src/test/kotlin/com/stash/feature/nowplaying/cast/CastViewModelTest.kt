package com.stash.feature.nowplaying.cast

import com.stash.core.media.cast.CastConnection
import com.stash.core.media.cast.CastDevice
import com.stash.core.media.cast.CastDevices
import com.stash.core.media.cast.CastMedia
import com.stash.core.media.cast.CastRemote
import com.stash.core.media.cast.CastStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CastViewModelTest {

    private class FakeRemote : CastRemote {
        override var status = CastStatus.EMPTY.copy(volume = 0.4f)
        val volumes = mutableListOf<Float>()
        val listeners = mutableListOf<() -> Unit>()
        override fun load(media: CastMedia, startPositionMs: Long, autoplay: Boolean) = Unit
        override fun setNext(media: CastMedia?) = Unit
        override fun playNext() = false
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun stop() = Unit
        override fun setVolume(level: Float) { volumes += level }
        override fun setMuted(muted: Boolean) = Unit
        override fun addListener(listener: () -> Unit) { listeners += listener }
        override fun removeListener(listener: () -> Unit) { listeners -= listener }

        fun volumeChangedTo(level: Float) {
            status = status.copy(volume = level)
            listeners.toList().forEach { it() }
        }
    }

    private class FakeDevices : CastDevices {
        override val connection = MutableStateFlow<CastConnection>(CastConnection.Disconnected)
        override val devices = MutableStateFlow(listOf(CastDevice("id-1", "Living room")))
        override val remote = MutableStateFlow<CastRemote?>(null)
        val calls = mutableListOf<String>()
        override fun startScan() { calls += "startScan" }
        override fun stopScan() { calls += "stopScan" }
        override fun connect(deviceId: String) { calls += "connect:$deviceId" }
        override fun disconnect() { calls += "disconnect" }
    }

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `volume follows the connected speaker and is null without one`() = runTest(dispatcher) {
        val devices = FakeDevices()
        val viewModel = CastViewModel(devices)
        backgroundScope.launch { viewModel.volume.collect {} }
        assertNull(viewModel.volume.value)

        val remote = FakeRemote()
        devices.remote.value = remote
        assertEquals(0.4f, viewModel.volume.value)

        remote.volumeChangedTo(0.7f)
        assertEquals(0.7f, viewModel.volume.value)

        devices.remote.value = null
        assertNull(viewModel.volume.value)
        assertEquals(0, remote.listeners.size) // stopped listening to the old speaker
    }

    @Test fun `the slider sets the connected speaker's volume`() {
        val devices = FakeDevices()
        val remote = FakeRemote()
        devices.remote.value = remote

        CastViewModel(devices).setVolume(0.25f)

        assertEquals(listOf(0.25f), remote.volumes)
    }

    @Test fun `picking a speaker connects to it`() {
        val devices = FakeDevices()

        CastViewModel(devices).connect(devices.devices.value.single())

        assertEquals(listOf("connect:id-1"), devices.calls)
    }
}
