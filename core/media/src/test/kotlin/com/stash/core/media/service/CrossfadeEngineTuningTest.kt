package com.stash.core.media.service

import android.content.Context
import android.media.AudioManager
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A tuning toggle rebuilds the idle players' outputs at once (a re-seek where they stand), so the
 * paused master doesn't re-buffer on resume and the primed spare doesn't at the start of its fade.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class) // ExoPlayer's static init reads Build fields
class CrossfadeEngineTuningTest {
    private val context = mockk<Context> {
        every { getSystemService(Context.AUDIO_SERVICE) } returns mockk<AudioManager>(relaxed = true)
    }
    private val nextItem = MediaItem.Builder().setMediaId("next").build()

    private fun player(playing: Boolean, state: Int, position: Long, items: Int = 1) = mockk<ExoPlayer>(relaxed = true) {
        every { isPlaying } returns playing
        every { playbackState } returns state
        every { currentPosition } returns position
        every { mediaItemCount } returns items
        every { currentMediaItem } returns nextItem
        every { nextMediaItemIndex } returns 1
        every { getMediaItemAt(1) } returns nextItem
    }

    private fun engine(master: ExoPlayer, spare: ExoPlayer, scope: kotlinx.coroutines.CoroutineScope): CrossfadeEngine {
        val players = ArrayDeque(listOf(master, spare))
        return CrossfadeEngine(context, { players.removeFirst() }, scope).also { it.initialize() }
    }

    @Test fun `a paused master and the primed spare are re-seeked where they stand`() = runTest {
        val master = player(playing = false, state = Player.STATE_READY, position = 61_000L)
        val spare = player(playing = false, state = Player.STATE_READY, position = 0L)
        engine(master, spare, backgroundScope).rebuildIdleOutputs()
        verify(exactly = 1) { master.seekTo(61_000L) }
        verify(exactly = 1) { spare.seekTo(0L) }
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
