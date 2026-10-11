package com.stash.core.ui.ambient

import com.stash.core.model.PlayerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The owner's rule: the wordmark and the selected tab wear the album colour only while a song is actually playing. */
@OptIn(ExperimentalCoroutinesApi::class)
class AlbumTintTest {

    @Test fun `playing tints, paused and idle do not`() {
        assertTrue(nextTinted(wasTinted = false, isPlaying = true, isBuffering = false))
        assertFalse(nextTinted(wasTinted = true, isPlaying = false, isBuffering = false))
        assertFalse(nextTinted(wasTinted = false, isPlaying = false, isBuffering = false))
    }

    @Test fun `buffering keeps whatever the chrome already was`() {
        // a seek or the gap between songs while playing is not a pause...
        assertTrue(nextTinted(wasTinted = true, isPlaying = false, isBuffering = true))
        // ...and buffering a paused song (a seek while paused) does not light the chrome up.
        assertFalse(nextTinted(wasTinted = false, isPlaying = false, isBuffering = true))
    }

    private fun state(playing: Boolean, buffering: Boolean = false) =
        PlayerState(isPlaying = playing, isBuffering = buffering)

    @Test fun `a pause turns neutral only after the grace`() = runTest {
        val src = MutableSharedFlow<PlayerState>()
        val out = mutableListOf<Boolean>()
        backgroundScope.launch { src.tintedWhilePlaying(graceMs = 600).collect { out += it } }
        runCurrent()
        src.emit(state(playing = true))
        runCurrent()
        assertEquals(listOf(true), out)
        src.emit(state(playing = false))
        advanceTimeBy(599)
        assertEquals(listOf(true), out)
        advanceTimeBy(2)
        assertEquals(listOf(true, false), out)
    }

    @Test fun `a momentary not-playing between songs never flashes neutral`() = runTest {
        val src = MutableSharedFlow<PlayerState>()
        val out = mutableListOf<Boolean>()
        backgroundScope.launch { src.tintedWhilePlaying(graceMs = 600).collect { out += it } }
        runCurrent()
        src.emit(state(playing = true))
        runCurrent()
        src.emit(state(playing = false)) // skip: the old item stops...
        advanceTimeBy(200)
        src.emit(state(playing = true)) // ...and the next one starts
        advanceTimeBy(2_000)
        assertEquals(listOf(true), out)
    }

    @Test fun `a long buffer while playing stays tinted`() = runTest {
        val src = MutableSharedFlow<PlayerState>()
        val out = mutableListOf<Boolean>()
        backgroundScope.launch { src.tintedWhilePlaying(graceMs = 600).collect { out += it } }
        runCurrent()
        src.emit(state(playing = true))
        runCurrent()
        src.emit(state(playing = false, buffering = true)) // a slow stream resolve
        advanceTimeBy(10_000)
        assertEquals(listOf(true), out)
        src.emit(state(playing = false)) // gave up / paused
        advanceTimeBy(700)
        assertEquals(listOf(true, false), out)
    }

    @Test fun `idle at start is neutral`() = runTest {
        val src = MutableSharedFlow<PlayerState>()
        val out = mutableListOf<Boolean>()
        backgroundScope.launch { src.tintedWhilePlaying(graceMs = 600).collect { out += it } }
        runCurrent()
        src.emit(state(playing = false))
        advanceTimeBy(700)
        assertEquals(listOf(false), out)
    }
}
