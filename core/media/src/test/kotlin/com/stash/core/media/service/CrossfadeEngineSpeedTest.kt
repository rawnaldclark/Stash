package com.stash.core.media.service

import android.content.Context
import android.media.AudioManager
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The incoming track of a crossfade plays on the spare player, which the MediaController never
 * reaches. Unless the engine carries the speed over, the fade plays the next song at 1x and it
 * jumps to the chosen speed at the swap.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class) // ExoPlayer's static init reads Build fields
class CrossfadeEngineSpeedTest {
    private val context = mockk<Context> {
        every { getSystemService(Context.AUDIO_SERVICE) } returns mockk<AudioManager>(relaxed = true)
    }
    private val master = mockk<ExoPlayer>(relaxed = true)
    private val spare = mockk<ExoPlayer>(relaxed = true) {
        every { mediaItemCount } returns 1
        every { isPlaying } returns true
    }

    private fun engine(scope: CoroutineScope): CrossfadeEngine {
        val players = ArrayDeque(listOf(master, spare))
        return CrossfadeEngine(context, { players.removeFirst() }, scope).also { it.initialize() }
    }

    @Test fun `the spare is primed at the master's speed`() = runTest {
        every { master.playbackParameters } returns PlaybackParameters(1.5f)
        engine(backgroundScope).prepareNext(MediaItem.Builder().setMediaId("next").build())
        verify { spare.playbackParameters = PlaybackParameters(1.5f) }
    }

    @Test fun `the incoming track starts the fade at the outgoing track's speed`() = runTest {
        every { master.playbackParameters } returns PlaybackParameters(1.5f)
        engine(backgroundScope).performTransition(fadeMs = 2_000L) {}
        runCurrent()
        verifyOrder {
            spare.playbackParameters = PlaybackParameters(1.5f)
            spare.play()
        }
    }

    @Test fun `a speed picked mid-fade reaches the incoming track too`() = runTest {
        var masterSpeed = PlaybackParameters.DEFAULT
        every { master.playbackParameters } answers { masterSpeed }
        engine(backgroundScope).performTransition(fadeMs = 2_000L) {}
        runCurrent()
        advanceTimeBy(500)
        masterSpeed = PlaybackParameters(2f) // what MediaController.setPlaybackSpeed changes
        advanceTimeBy(200)
        runCurrent()
        verify { spare.playbackParameters = PlaybackParameters(2f) }
    }
}
