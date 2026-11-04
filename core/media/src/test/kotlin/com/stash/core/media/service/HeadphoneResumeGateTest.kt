package com.stash.core.media.service

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HeadphoneResumeGateTest {

    private var nowMs = 1_000_000L
    private lateinit var gate: HeadphoneResumeGate

    @Before
    fun setUp() {
        gate = HeadphoneResumeGate(resumeWindowMs = WINDOW_MS, clock = { nowMs })
    }

    private fun play() =
        gate.onPlayWhenReadyChanged(playWhenReady = true, reason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)

    private fun pause(reason: Int) = gate.onPlayWhenReadyChanged(playWhenReady = false, reason = reason)

    private fun unplug() = pause(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)

    /** A reconnect with nothing else in the way: no call, no room, no cast, a queue, paused. */
    private fun shouldResumeNow(
        hasActiveCall: Boolean = false,
        isInListenTogether: Boolean = false,
        isCasting: Boolean = false,
        hasMediaItems: Boolean = true,
        playWhenReady: Boolean = false,
    ) = gate.shouldResume(hasActiveCall, isInListenTogether, isCasting, hasMediaItems, playWhenReady)

    // ---- arming ----

    @Test
    fun initially_notArmed() {
        assertNull(gate.armedAtMs)
        assertFalse(shouldResumeNow())
    }

    @Test
    fun playing_thenUnplug_armsAtThatMoment() {
        play()
        nowMs += 7_000
        unplug()
        assertEquals(nowMs, gate.armedAtMs)
    }

    @Test
    fun playing_thenUnplug_thenReplug_resumes() {
        play()
        unplug()
        nowMs += 30_000
        assertTrue(shouldResumeNow())
    }

    @Test
    fun neverPlayedRestoredQueue_thenNoisy_notArmed() {
        // The service starts with a restored queue, paused; Android 8–9 send the noisy broadcast
        // on every disconnect, so Media3 reports AUDIO_BECOMING_NOISY on this paused player.
        unplug()
        assertNull(gate.armedAtMs)
        assertFalse(shouldResumeNow())
    }

    @Test
    fun userPause_thenUnplug_thenReplug_staysSilent() {
        play()
        pause(Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        // The noisy event still arrives on the already-paused player (another app playing, a call).
        unplug()
        assertNull(gate.armedAtMs)
        assertFalse(shouldResumeNow())
    }

    @Test
    fun secondNoisyWhilePaused_keepsTheFirstUnplugsTime() {
        play()
        unplug()
        val armedAt = gate.armedAtMs
        nowMs += 60_000
        unplug()
        assertEquals(armedAt, gate.armedAtMs)
    }

    @Test
    fun pauseFromAudioFocusLoss_notArmed() {
        play()
        pause(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
        assertNull(gate.armedAtMs)
    }

    @Test
    fun userPauseAfterUnplug_disarms() {
        play()
        unplug()
        pause(Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        assertNull(gate.armedAtMs)
        assertFalse(shouldResumeNow())
    }

    @Test
    fun playingAgainAfterUnplug_disarms() {
        play()
        unplug()
        play()
        assertNull(gate.armedAtMs)
    }

    // ---- the time limit ----

    @Test
    fun replugAtTheEndOfTheWindow_resumes() {
        play()
        unplug()
        nowMs += WINDOW_MS
        assertTrue(shouldResumeNow())
    }

    @Test
    fun replugPastTheWindow_doesNotResume() {
        play()
        unplug()
        nowMs += WINDOW_MS + 1
        assertFalse(shouldResumeNow())
    }

    // ---- crossfade swap ----

    @Test
    fun afterASwapToAPlayingMaster_unplugArms() {
        play()
        // The fade's pauseAtEndOfMediaItems pauses the OUTGOING master while the listener is on it.
        pause(Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
        // The listener moves to the playing new master; no event fires for that.
        gate.onPlayerSwapped(playWhenReady = true)
        unplug()
        assertEquals(nowMs, gate.armedAtMs)
        assertTrue(shouldResumeNow())
    }

    @Test
    fun withoutTheSwapCall_theStaleStateWouldMissTheUnplug() {
        // Guards the reason onPlayerSwapped exists: the gate believes the player is paused.
        play()
        pause(Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
        unplug()
        assertNull(gate.armedAtMs)
    }

    @Test
    fun swapToAPlayingMaster_disarms() {
        play()
        unplug()
        gate.onPlayerSwapped(playWhenReady = true)
        assertNull(gate.armedAtMs)
    }

    @Test
    fun swapToAPausedMaster_keepsTheState() {
        play()
        unplug()
        val armedAt = gate.armedAtMs
        gate.onPlayerSwapped(playWhenReady = false)
        assertEquals(armedAt, gate.armedAtMs)
    }

    // ---- what blocks a resume ----

    @Test
    fun duringActiveCall_doesNotResume() {
        play()
        unplug()
        assertFalse(shouldResumeNow(hasActiveCall = true))
    }

    @Test
    fun inListenTogether_doesNotResume() {
        play()
        unplug()
        assertFalse(shouldResumeNow(isInListenTogether = true))
    }

    @Test
    fun whileCasting_doesNotResume() {
        play()
        unplug()
        assertFalse(shouldResumeNow(isCasting = true))
    }

    @Test
    fun emptyPlayer_doesNotResume() {
        play()
        unplug()
        assertFalse(shouldResumeNow(hasMediaItems = false))
    }

    @Test
    fun alreadySetToPlay_doesNotResume() {
        play()
        unplug()
        assertFalse(shouldResumeNow(playWhenReady = true))
    }

    @Test
    fun onResumeTriggered_disarms() {
        play()
        unplug()
        gate.onResumeTriggered()
        assertNull(gate.armedAtMs)
        assertFalse(shouldResumeNow())
    }

    private companion object {
        const val WINDOW_MS = 5 * 60_000L
    }
}
