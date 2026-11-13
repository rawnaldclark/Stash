package com.stash.core.media.service

import android.content.Context
import android.media.AudioManager
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.test.utils.ExoPlayerTestRunner
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.FakeMediaSource
import androidx.media3.test.utils.FakeRenderer
import androidx.media3.test.utils.FakeTimeline
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.RobolectricUtil
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Two real players through a fade that outlasts the song it fades out of. The ramp counts 50 ms
 * steps that each run a little long, so a long fade ends after the song does (and a stream can end
 * before its stated length): the outgoing reaches its end first, and pauseAtEndOfMediaItems holds
 * it there. The session's player was then a finished song that read as paused. A play from the
 * notification, a headset or a car, or the end of a short focus loss, started it again: it moved on
 * into the next song from 0, the song the incoming had been playing for seconds, and the hand-back
 * stopped the incoming. The next song started over. Now the incoming takes over as the outgoing
 * runs out, and finishes its rise as the master.
 *
 * The fade's coroutine runs on the test dispatcher and the players on the main looper, so the
 * outgoing is run to its end while the ramp stands still: it always runs out first here.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CrossfadeEngineRunOutTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Song lengths: "a" fades out, "b" fades in, "c" comes after, "x" is a Play next. */
    private val lengthsUs = mapOf("a" to 20_000_000L, "b" to 60_000_000L, "c" to 60_000_000L, "x" to 60_000_000L)

    private fun song(id: String) = MediaItem.Builder().setMediaId(id).setUri("stash-test://$id").build()

    private val sources = object : MediaSource.Factory {
        override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider) = this
        override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy) = this
        override fun getSupportedTypes(): IntArray = intArrayOf()
        override fun createMediaSource(mediaItem: MediaItem): MediaSource = FakeMediaSource(
            FakeTimeline(
                FakeTimeline.TimelineWindowDefinition.Builder()
                    .setDurationUs(lengthsUs.getValue(mediaItem.mediaId)).setMediaItem(mediaItem).build(),
            ),
            ExoPlayerTestRunner.AUDIO_FORMAT,
        )
    }

    /** One clock for both players, so the outgoing's end and the incoming's position share a time line. */
    private val clock = FakeClock(/* isAutoAdvancing= */ true)
    private val players = mutableListOf<ExoPlayer>()

    private fun player(): ExoPlayer =
        TestExoPlayerBuilder(context).setClock(clock).setRenderers(FakeRenderer(C.TRACK_TYPE_AUDIO))
            .setMediaSourceFactory(sources).build().also { players += it }

    @After fun release() = players.forEach { it.release() }

    private class Fade(val master: ExoPlayer, val spare: ExoPlayer, val engine: CrossfadeEngine) {
        /** What the engine handed the session at the swap, if it swapped. */
        var swapped: ExoPlayer? = null
    }

    /** "a" playing at 14 s of its 20, "b" primed on the spare, and a 5.5 s fade under way. */
    private fun TestScope.fadeOutOfA(): Fade {
        val master = player()
        val spare = player()
        val engine = CrossfadeEngine(context, ArrayDeque(listOf(master, spare))::removeFirst, backgroundScope)
            .also { it.initialize() }
        master.setMediaItems(listOf(song("a"), song("b"), song("c")))
        master.prepare()
        TestPlayerRunHelper.playUntilPosition(master, 0, 14_000)
        engine.prepareNext(master.getMediaItemAt(1))
        TestPlayerRunHelper.run(spare).untilState(Player.STATE_READY)
        val fade = Fade(master, spare, engine)
        engine.performTransition(fadeMs = 5_500L) { fade.swapped = it }
        runCurrent() // the fade starts the spare, silent
        return fade
    }

    /** Runs the rest of the ramp, then lets both players catch up. */
    private fun TestScope.finishTheRamp(fade: Fade) {
        advanceTimeBy(10_000)
        runCurrent()
        TestPlayerRunHelper.run(fade.master).untilPendingCommandsAreFullyHandled()
        TestPlayerRunHelper.run(fade.spare).untilPendingCommandsAreFullyHandled()
    }

    /** "b" plays on from where the fade had it, on the session's player, and the fade is over. */
    private fun assertBPlaysOnFrom(fade: Fade, positionMs: Long) {
        val session = fade.engine.masterPlayer
        assertThat(session.currentMediaItem?.mediaId).isEqualTo("b")
        assertThat(session.currentPosition).isAtLeast(positionMs) // not back to 0
        assertThat(session.playWhenReady).isTrue()
        assertThat(fade.swapped).isSameInstanceAs(fade.spare)
        assertThat(session).isSameInstanceAs(fade.spare)
        assertThat(session.mediaItemCount).isEqualTo(3) // the whole queue came across
        assertThat(fade.master.mediaItemCount).isEqualTo(0) // the old player let go
        assertThat(fade.engine.isTransitioning()).isFalse()
    }

    @Test fun `a play that reaches the session after the old song ended does not start the new one again`() = runTest {
        val fade = fadeOutOfA()
        TestPlayerRunHelper.run(fade.master).untilPlayWhenReadyIs(false) // "a" ran out
        val bAt = fade.spare.currentPosition
        assertThat(bAt).isGreaterThan(5_000L)

        // The notification's play button, a headset or a car: the session plays its player.
        fade.engine.masterPlayer.play()
        TestPlayerRunHelper.run(fade.engine.masterPlayer).untilPendingCommandsAreFullyHandled()
        finishTheRamp(fade)

        assertBPlaysOnFrom(fade, bAt)
    }

    @Test fun `a short focus loss right after the old song ended does not start the new one again`() = runTest {
        val fade = fadeOutOfA()
        TestPlayerRunHelper.run(fade.master).untilPlayWhenReadyIs(false) // "a" ran out
        val bAt = fade.spare.currentPosition

        // A notification sound, an assistant: a transient loss and its end.
        fade.engine.focusListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        fade.engine.focusListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        TestPlayerRunHelper.run(fade.engine.masterPlayer).untilPendingCommandsAreFullyHandled()
        finishTheRamp(fade)

        assertBPlaysOnFrom(fade, bAt)
    }

    @Test fun `the old song running out first hands over at once, and the new one keeps rising to full`() = runTest {
        val fade = fadeOutOfA()
        TestPlayerRunHelper.run(fade.master).untilPlayWhenReadyIs(false) // "a" ran out

        // Handed over at the run-out, not at the end of the ramp: no finished song in the session.
        assertThat(fade.swapped).isSameInstanceAs(fade.spare)
        assertThat(fade.engine.isTransitioning()).isFalse()
        val volumeAtHandover = fade.spare.volume
        assertThat(volumeAtHandover).isLessThan(1f) // not jumped to full

        advanceTimeBy(2_750) // half the ramp
        runCurrent()
        assertThat(fade.spare.volume).isGreaterThan(volumeAtHandover)
        assertThat(fade.spare.volume).isLessThan(1f)

        finishTheRamp(fade)
        assertThat(fade.spare.volume).isEqualTo(1f)
        assertThat(fade.engine.masterPlayer.currentMediaItem?.mediaId).isEqualTo("b")
    }

    @Test fun `a Play next that lands just before the old song ends plays next`() = runTest {
        val fade = fadeOutOfA()
        fade.master.addMediaItem(1, song("x")) // "b" is no longer the next song

        // "a" runs out (the hold), and the fade gives way to "x".
        RobolectricUtil.runMainLooperUntil { !fade.master.playWhenReady || fade.master.currentMediaItemIndex == 1 }
        advanceTimeBy(1_000)
        runCurrent()
        TestPlayerRunHelper.run(fade.master).untilPosition(1, 1_000)

        assertThat(fade.swapped).isNull()
        assertThat(fade.engine.masterPlayer).isSameInstanceAs(fade.master)
        assertThat(fade.master.currentMediaItem?.mediaId).isEqualTo("x")
        assertThat(fade.master.playWhenReady).isTrue() // on into "x", not stuck at the end of "a"
        assertThat(fade.spare.mediaItemCount).isEqualTo(0)
        assertThat(fade.engine.isTransitioning()).isFalse()
    }
}
