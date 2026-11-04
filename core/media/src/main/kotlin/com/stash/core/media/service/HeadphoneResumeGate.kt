package com.stash.core.media.service

import android.os.SystemClock
import androidx.media3.common.Player

/**
 * Evaluates whether reconnecting headphones should resume playback.
 *
 * Rules:
 * - Resumes only when a headphone disconnect (AUDIO_BECOMING_NOISY) stopped music that was playing.
 *   Media3 reports AUDIO_BECOMING_NOISY on a player that is already paused too: Android 8–9 send the
 *   broadcast on every disconnect, and Android 10+ send it whenever any app is playing (another app,
 *   a call). So the gate remembers the last playWhenReady and only arms on a playing → paused change;
 *   a noisy event on a paused player keeps whatever state was there.
 * - Does nothing if the user paused manually or if pause was caused by focus loss.
 * - Resumes only within [resumeWindowMs] of the unplug: a bound controller (Android Auto) can keep
 *   the service alive past the idle stop, and music starting long after it stopped would surprise.
 * - Skips resume while a phone call is active, in a Listen Together room, or while casting.
 * - Never loads a saved queue into an empty player.
 * - Does nothing if the player is already set to play.
 *
 * Main thread only, like the player listener that feeds it.
 *
 * @param resumeWindowMs how long after the unplug a reconnect still resumes.
 * @param clock monotonic milliseconds; [SystemClock.elapsedRealtime] in the app, a fake in tests.
 */
class HeadphoneResumeGate(
    private val resumeWindowMs: Long,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    /**
     * The master's playWhenReady as last reported. False at the start: the service's player is
     * built paused, and a restored queue comes back paused.
     */
    private var lastPlayWhenReady = false

    /**
     * When ([clock] time) an unplug stopped music that was playing, or null when nothing is
     * waiting to resume.
     */
    var armedAtMs: Long? = null
        private set

    /** Feeds the master player's [Player.Listener.onPlayWhenReadyChanged]. */
    fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        val wasPlaying = lastPlayWhenReady
        lastPlayWhenReady = playWhenReady
        armedAtMs = when {
            // Playing again (by any route) means nothing is left to resume.
            playWhenReady -> null
            // A user pause, focus loss, end of queue: the music stopped for a reason the
            // headphones coming back says nothing about.
            reason != Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> null
            // The unplug is what stopped the music.
            wasPlaying -> clock()
            // A noisy event on a player that was already paused (see the class doc): keep the
            // state as it was, so a user pause stays a pause and an earlier unplug stays armed.
            else -> armedAtMs
        }
    }

    /**
     * The crossfade engine promoted a new master and the listener moved to it. No event fires for
     * that, so the new master's playWhenReady is read here; without it a playing new master looks
     * paused and its next unplug would not arm.
     */
    fun onPlayerSwapped(playWhenReady: Boolean) {
        lastPlayWhenReady = playWhenReady
        if (playWhenReady) armedAtMs = null
    }

    /**
     * Whether headphones reconnecting now should start the music again. Every argument is read by
     * the caller in the same main-thread moment.
     *
     * @param playWhenReady the session player's playWhenReady (true: already set to play).
     */
    fun shouldResume(
        hasActiveCall: Boolean,
        isInListenTogether: Boolean,
        isCasting: Boolean,
        hasMediaItems: Boolean,
        playWhenReady: Boolean,
    ): Boolean {
        val armedAt = armedAtMs ?: return false
        if (clock() - armedAt > resumeWindowMs) return false
        if (hasActiveCall) return false
        if (isInListenTogether) return false
        if (isCasting) return false
        if (!hasMediaItems) return false
        if (playWhenReady) return false
        return true
    }

    /** The resume is happening; a later reconnect must not resume again on the same unplug. */
    fun onResumeTriggered() {
        armedAtMs = null
    }
}
