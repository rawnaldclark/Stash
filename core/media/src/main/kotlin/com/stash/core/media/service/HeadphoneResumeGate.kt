package com.stash.core.media.service

import androidx.media3.common.Player

/**
 * Evaluates whether reconnecting headphones should resume playback.
 *
 * Rules:
 * - Resumes only when a headphone disconnect previously paused playback (AUDIO_BECOMING_NOISY).
 * - Does nothing if the user paused manually or if pause was caused by focus loss.
 * - Skips resume while a phone call is active, in a Listen Together room, or while casting.
 * - Never loads a saved queue into an empty player.
 * - Does nothing if the player is already playing.
 */
class HeadphoneResumeGate {
    /** True if the last pause was triggered by an audio becoming noisy (headphone disconnect) event. */
    var pausedByHeadphoneDisconnect: Boolean = false
        internal set

    fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!playWhenReady) {
            pausedByHeadphoneDisconnect = (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)
        } else {
            pausedByHeadphoneDisconnect = false
        }
    }

    fun shouldResume(
        resumeOnReconnectEnabled: Boolean,
        hasActiveCall: Boolean,
        isInListenTogether: Boolean,
        isCasting: Boolean,
        hasMediaItems: Boolean,
        isPlaying: Boolean,
    ): Boolean {
        if (!resumeOnReconnectEnabled) return false
        if (!pausedByHeadphoneDisconnect) return false
        if (hasActiveCall) return false
        if (isInListenTogether) return false
        if (isCasting) return false
        if (!hasMediaItems) return false
        if (isPlaying) return false
        return true
    }

    fun onResumeTriggered() {
        pausedByHeadphoneDisconnect = false
    }
}
