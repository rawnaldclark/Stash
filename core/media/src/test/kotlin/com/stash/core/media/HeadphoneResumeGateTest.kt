package com.stash.core.media

import androidx.media3.common.Player
import com.stash.core.media.service.HeadphoneResumeGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HeadphoneResumeGateTest {

    private lateinit var gate: HeadphoneResumeGate

    @Before
    fun setUp() {
        gate = HeadphoneResumeGate()
    }

    @Test
    fun initially_pausedByHeadphoneDisconnectIsFalse() {
        assertFalse(gate.pausedByHeadphoneDisconnect)
    }

    @Test
    fun pauseFromBecomingNoisy_setsFlagTrue() {
        gate.onPlayWhenReadyChanged(
            playWhenReady = false,
            reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
        )
        assertTrue(gate.pausedByHeadphoneDisconnect)
    }

    @Test
    fun pauseFromUserRequest_leavesFlagFalse() {
        gate.onPlayWhenReadyChanged(
            playWhenReady = false,
            reason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
        )
        assertFalse(gate.pausedByHeadphoneDisconnect)
    }

    @Test
    fun pauseFromAudioFocusLoss_leavesFlagFalse() {
        gate.onPlayWhenReadyChanged(
            playWhenReady = false,
            reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
        )
        assertFalse(gate.pausedByHeadphoneDisconnect)
    }

    @Test
    fun userPauseAfterBecomingNoisy_clearsFlag() {
        gate.onPlayWhenReadyChanged(
            playWhenReady = false,
            reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
        )
        assertTrue(gate.pausedByHeadphoneDisconnect)

        gate.onPlayWhenReadyChanged(
            playWhenReady = false,
            reason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
        )
        assertFalse(gate.pausedByHeadphoneDisconnect)
    }

    @Test
    fun resumingPlayback_clearsFlag() {
        gate.onPlayWhenReadyChanged(
            playWhenReady = false,
            reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
        )
        assertTrue(gate.pausedByHeadphoneDisconnect)

        gate.onPlayWhenReadyChanged(
            playWhenReady = true,
            reason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
        )
        assertFalse(gate.pausedByHeadphoneDisconnect)
    }

    @Test
    fun shouldResume_allConditionsMet_returnsTrue() {
        gate.onPlayWhenReadyChanged(
            playWhenReady = false,
            reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
        )
        val result = gate.shouldResume(
            resumeOnReconnectEnabled = true,
            hasActiveCall = false,
            isInListenTogether = false,
            isCasting = false,
            hasMediaItems = true,
            isPlaying = false,
        )
        assertTrue(result)
    }

    @Test
    fun shouldResume_whenPreferenceDisabled_returnsFalse() {
        gate.onPlayWhenReadyChanged(
            playWhenReady = false,
            reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
        )
        val result = gate.shouldResume(
            resumeOnReconnectEnabled = false,
            hasActiveCall = false,
            isInListenTogether = false,
            isCasting = false,
            hasMediaItems = true,
            isPlaying = false,
        )
        assertFalse(result)
    }

    @Test
    fun shouldResume_whenUserPaused_returnsFalse() {
        gate.onPlayWhenReadyChanged(
            playWhenReady = false,
            reason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
        )
        val result = gate.shouldResume(
            resumeOnReconnectEnabled = true,
            hasActiveCall = false,
            isInListenTogether = false,
            isCasting = false,
            hasMediaItems = true,
            isPlaying = false,
        )
        assertFalse(result)
    }

    @Test
    fun shouldResume_duringActiveCall_returnsFalse() {
        gate.onPlayWhenReadyChanged(
            playWhenReady = false,
            reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
        )
        val result = gate.shouldResume(
            resumeOnReconnectEnabled = true,
            hasActiveCall = true,
            isInListenTogether = false,
            isCasting = false,
            hasMediaItems = true,
            isPlaying = false,
        )
        assertFalse(result)
    }

    @Test
    fun shouldResume_inListenTogether_returnsFalse() {
        gate.onPlayWhenReadyChanged(
            playWhenReady = false,
            reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
        )
        val result = gate.shouldResume(
            resumeOnReconnectEnabled = true,
            hasActiveCall = false,
            isInListenTogether = true,
            isCasting = false,
            hasMediaItems = true,
            isPlaying = false,
        )
        assertFalse(result)
    }

    @Test
    fun shouldResume_whileCasting_returnsFalse() {
        gate.onPlayWhenReadyChanged(
            playWhenReady = false,
            reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
        )
        val result = gate.shouldResume(
            resumeOnReconnectEnabled = true,
            hasActiveCall = false,
            isInListenTogether = false,
            isCasting = true,
            hasMediaItems = true,
            isPlaying = false,
        )
        assertFalse(result)
    }

    @Test
    fun shouldResume_emptyPlayer_returnsFalse() {
        gate.onPlayWhenReadyChanged(
            playWhenReady = false,
            reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
        )
        val result = gate.shouldResume(
            resumeOnReconnectEnabled = true,
            hasActiveCall = false,
            isInListenTogether = false,
            isCasting = false,
            hasMediaItems = false,
            isPlaying = false,
        )
        assertFalse(result)
    }

    @Test
    fun shouldResume_alreadyPlaying_returnsFalse() {
        gate.onPlayWhenReadyChanged(
            playWhenReady = false,
            reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
        )
        val result = gate.shouldResume(
            resumeOnReconnectEnabled = true,
            hasActiveCall = false,
            isInListenTogether = false,
            isCasting = false,
            hasMediaItems = true,
            isPlaying = true,
        )
        assertFalse(result)
    }

    @Test
    fun onResumeTriggered_resetsFlag() {
        gate.onPlayWhenReadyChanged(
            playWhenReady = false,
            reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
        )
        assertTrue(gate.pausedByHeadphoneDisconnect)

        gate.onResumeTriggered()
        assertFalse(gate.pausedByHeadphoneDisconnect)
    }
}
