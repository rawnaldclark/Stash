package com.stash.core.media.preview

import android.content.Context
import androidx.media3.exoplayer.source.MediaSource
import com.stash.core.media.equalizer.EqController
import com.stash.core.media.equalizer.LoudnessController
import com.stash.core.media.output.HiResOutputController
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewPlayerRequestLeaseTest {

    private fun player() = PreviewPlayer(
        context = mockk<Context>(relaxed = true),
        eqController = mockk<EqController>(relaxed = true),
        loudnessController = mockk<LoudnessController>(relaxed = true),
        hiResOutputController = mockk<HiResOutputController>(relaxed = true),
        tuningController = mockk<com.stash.core.media.tuning.TuningController>(relaxed = true),
    )

    @Test
    fun `a stale request cannot initialize URL playback`() {
        val player = player()
        val staleRequest = player.claimRequest()
        player.claimRequest()

        val attemptId = player.playUrlIfClaimed(
            requestId = staleRequest,
            videoId = "stale",
            streamUrl = "https://audio.example/stale",
        )

        assertNull(attemptId)
        assertTrue(player.previewState.value is PreviewState.Idle)
    }

    @Test
    fun `a stale request cannot initialize MediaSource playback`() {
        val player = player()
        val staleRequest = player.claimRequest()
        player.claimRequest()

        val attemptId = player.playIfClaimed(
            requestId = staleRequest,
            videoId = "stale",
            mediaSource = mockk<MediaSource>(),
        )

        assertNull(attemptId)
        assertTrue(player.previewState.value is PreviewState.Idle)
    }

    @Test
    fun `cancelling a stale request preserves the latest claim`() {
        val player = player()
        val staleRequest = player.claimRequest()
        val latestRequest = player.claimRequest()

        player.cancelRequest(staleRequest)
        assertTrue(player.isRequestCurrent(latestRequest))

        player.cancelRequest(latestRequest)
        assertFalse(player.isRequestCurrent(latestRequest))
    }
}
