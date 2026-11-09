package com.stash.core.media.service

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.test.utils.ExoPlayerTestRunner
import androidx.media3.test.utils.FakeMediaSource
import androidx.media3.test.utils.FakeRenderer
import androidx.media3.test.utils.FakeTimeline
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A tuning toggle rebuilds the idle players' outputs at once (a re-seek), so the paused master
 * doesn't rebuild on resume and the primed spare doesn't at the start of its fade.
 *
 * What rebuilds an output is the seek resetting the audio renderer (MediaCodecAudioRenderer then
 * flushes its sink, which releases the output and builds a new one, for the new tuning, as it
 * pre-fills). The real-player tests count those resets on a real ExoPlayer.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CrossfadeEngineTuningTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val nextItem = MediaItem.Builder().setMediaId("next").setUri("stash-test://next").build()
    private val song = MediaItem.Builder().setMediaId("song").setUri("stash-test://song").build()

    // --- with real players ---

    /** A minute of audio for any item. */
    private val sources = object : MediaSource.Factory {
        override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider) = this
        override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy) = this
        override fun getSupportedTypes(): IntArray = intArrayOf()
        override fun createMediaSource(mediaItem: MediaItem): MediaSource = FakeMediaSource(
            FakeTimeline(FakeTimeline.TimelineWindowDefinition.Builder().setDurationUs(60_000_000).setMediaItem(mediaItem).build()),
            ExoPlayerTestRunner.AUDIO_FORMAT,
        )
    }
    private val masterAudio = FakeRenderer(C.TRACK_TYPE_AUDIO)
    private val spareAudio = FakeRenderer(C.TRACK_TYPE_AUDIO)
    private val realPlayers = mutableListOf<ExoPlayer>()

    private fun realPlayer(audio: FakeRenderer): ExoPlayer =
        TestExoPlayerBuilder(context).setRenderers(audio).setMediaSourceFactory(sources).build().also { realPlayers += it }

    @After fun releasePlayers() = realPlayers.forEach { it.release() }

    /** A master played 10 s into [song] and paused there, as on the phone. */
    private fun pausedMaster(): ExoPlayer = realPlayer(masterAudio).apply {
        setMediaItem(song)
        prepare()
        play()
        TestPlayerRunHelper.playUntilPosition(this, 0, 10_000)
        pause()
        TestPlayerRunHelper.run(this).untilPendingCommandsAreFullyHandled()
    }

    @Test fun `ExoPlayer drops a seek to where a paused player stands - nothing is rebuilt`() {
        // Why the re-seek moves 1 ms: seekTo(currentPosition) on a paused player resets nothing.
        val master = pausedMaster()
        val resets = masterAudio.positionResetCount
        master.seekTo(master.currentPosition)
        TestPlayerRunHelper.run(master).untilPendingCommandsAreFullyHandled()
        assertThat(masterAudio.positionResetCount).isEqualTo(resets)
    }

    @Test fun `playing, paused, toggled - the re-seek restarts the paused master's audio while it is paused`() = runTest {
        val master = pausedMaster()
        val spare = realPlayer(spareAudio)
        val engine = CrossfadeEngine(context, ArrayDeque(listOf(master, spare))::removeFirst, backgroundScope)
            .also { it.initialize() }
        val at = master.currentPosition
        val resets = masterAudio.positionResetCount

        engine.rebuildIdleOutputs() // what the service does on the toggle
        TestPlayerRunHelper.run(master).untilPendingCommandsAreFullyHandled()

        assertThat(masterAudio.positionResetCount).isEqualTo(resets + 1) // a new output, built now
        assertThat(master.isPlaying).isFalse()
        assertThat(master.playWhenReady).isFalse()
        assertThat(master.currentPosition).isEqualTo(at - 1)
        assertThat(spareAudio.positionResetCount).isEqualTo(0) // nothing primed, nothing to rebuild

        // Resumed: no further reset, it plays on from there.
        master.play()
        TestPlayerRunHelper.playUntilPosition(master, 0, at + 2_000)
        assertThat(masterAudio.positionResetCount).isEqualTo(resets + 1)
    }

    @Test fun `the primed spare's audio restarts too, ready for its fade`() = runTest {
        val master = pausedMaster()
        val spare = realPlayer(spareAudio)
        val engine = CrossfadeEngine(context, ArrayDeque(listOf(master, spare))::removeFirst, backgroundScope)
            .also { it.initialize() }
        engine.prepareNext(nextItem)
        TestPlayerRunHelper.run(spare).untilState(Player.STATE_READY)
        TestPlayerRunHelper.run(spare).untilPendingCommandsAreFullyHandled()
        val resets = spareAudio.positionResetCount

        engine.rebuildIdleOutputs()
        TestPlayerRunHelper.run(spare).untilPendingCommandsAreFullyHandled()

        assertThat(spareAudio.positionResetCount).isEqualTo(resets + 1)
        assertThat(spare.currentPosition).isEqualTo(1L)
        assertThat(engine.isNextReady()).isTrue()
    }

    @Test fun `the target is never the position itself`() {
        assertThat(rebuildSeekTarget(61_000L)).isEqualTo(60_999L)
        assertThat(rebuildSeekTarget(1L)).isEqualTo(0L)
        assertThat(rebuildSeekTarget(0L)).isEqualTo(1L)
    }

    // --- which players, with mock players ---

    private fun player(playing: Boolean, state: Int, position: Long, items: Int = 1) = mockk<ExoPlayer>(relaxed = true) {
        every { isPlaying } returns playing
        every { playbackState } returns state
        every { currentPosition } returns position
        every { mediaItemCount } returns items
        every { currentMediaItem } returns nextItem
        every { nextMediaItemIndex } returns 1
        every { getMediaItemAt(1) } returns nextItem
    }

    private fun engine(master: ExoPlayer, spare: ExoPlayer, scope: CoroutineScope): CrossfadeEngine {
        val players = ArrayDeque(listOf(master, spare))
        return CrossfadeEngine(context, { players.removeFirst() }, scope).also { it.initialize() }
    }

    @Test fun `a paused master and the primed spare are re-seeked, each 1 ms from where it stands`() = runTest {
        val master = player(playing = false, state = Player.STATE_READY, position = 61_000L)
        val spare = player(playing = false, state = Player.STATE_READY, position = 0L)
        engine(master, spare, backgroundScope).rebuildIdleOutputs()
        verify(exactly = 1) { master.seekTo(60_999L) }
        verify(exactly = 1) { spare.seekTo(1L) }
    }

    @Test fun `a playing master rebuilds itself as it plays, and an empty or stopped player is left alone`() = runTest {
        val master = player(playing = true, state = Player.STATE_READY, position = 61_000L)
        val spare = player(playing = false, state = Player.STATE_IDLE, position = 0L, items = 0)
        engine(master, spare, backgroundScope).rebuildIdleOutputs()
        verify(exactly = 0) { master.seekTo(any<Long>()) }
        verify(exactly = 0) { spare.seekTo(any<Long>()) }
    }

    @Test fun `nothing is re-seeked mid-fade`() = runTest {
        val master = player(playing = true, state = Player.STATE_READY, position = 200_000L)
        // Still starting (not yet playing) at the top of the fade: without the guard it would be re-seeked.
        val spare = player(playing = false, state = Player.STATE_READY, position = 1_000L)
        val e = engine(master, spare, backgroundScope)
        e.performTransition(fadeMs = 5_000L) {}
        runCurrent()
        e.rebuildIdleOutputs()
        verify(exactly = 0) { master.seekTo(any<Long>()) }
        verify(exactly = 0) { spare.seekTo(any<Long>()) }
    }
}
