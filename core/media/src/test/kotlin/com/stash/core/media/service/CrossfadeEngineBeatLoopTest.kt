package com.stash.core.media.service

import android.content.Context
import android.media.AudioManager
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CrossfadeEngineBeatLoopTest {
    private val context = mockk<Context> {
        every { getSystemService(Context.AUDIO_SERVICE) } returns mockk<AudioManager>(relaxed = true)
    }
    private val master = mockk<ExoPlayer>(relaxed = true) {
        every { playbackParameters } returns PlaybackParameters.DEFAULT
    }
    private val spare = mockk<ExoPlayer>(relaxed = true)
    private val loop = mockk<ExoPlayer>(relaxed = true) {
        every { mediaItemCount } returns 1
        every { playbackState } returns Player.STATE_READY
        every { bufferedPosition } returns 4_000L
    }

    private fun engine(scope: CoroutineScope): CrossfadeEngine {
        val players = ArrayDeque(listOf(master, spare, loop))
        return CrossfadeEngine(context, { players.removeFirst() }, scope).also { it.initialize() }
    }

    @Test fun `loop deck is built lazily and primed with the exact clipped beat bars`() = runTest {
        val engine = engine(backgroundScope)
        val item = MediaItem.Builder().setMediaId("outgoing").setUri("file:///song.mp3").build()
        val captured = slot<MediaItem>()

        engine.prepareBeatLoop(item, 12_000L, 16_000L)

        verify { loop.repeatMode = Player.REPEAT_MODE_ONE }
        verify { loop.setMediaItem(capture(captured)) }
        verify { loop.prepare() }
        verify { loop.seekTo(0L) }
        assertThat(captured.captured.mediaId).isEqualTo("outgoing")
        assertThat(captured.captured.clippingConfiguration.startPositionMs).isEqualTo(12_000L)
        assertThat(captured.captured.clippingConfiguration.endPositionMs).isEqualTo(16_000L)
        assertThat(engine.isBeatLoopPreparedFor("outgoing", 12_000L, 16_000L)).isTrue()
        assertThat(engine.isBeatLoopReady()).isTrue()
    }

    @Test fun `cancelling a transition also stops and forgets the loop deck`() = runTest {
        val engine = engine(backgroundScope)
        val item = MediaItem.Builder().setMediaId("outgoing").setUri("file:///song.mp3").build()
        engine.prepareBeatLoop(item, 12_000L, 16_000L)

        engine.cancelTransition()

        verify { loop.stop() }
        verify { loop.clearMediaItems() }
        verify { loop.repeatMode = Player.REPEAT_MODE_OFF }
        assertThat(engine.isBeatLoopPreparedFor("outgoing", 12_000L, 16_000L)).isFalse()
    }
}
