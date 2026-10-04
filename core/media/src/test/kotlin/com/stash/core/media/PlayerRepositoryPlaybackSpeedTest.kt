package com.stash.core.media

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.prefs.AutoplayRadioPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.TrackIdentityEvents
import com.stash.core.media.diagnostics.PlaybackDiagnosticsLog
import com.stash.core.media.listen.ListenTogetherController
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_ID
import com.stash.core.media.streaming.StreamUrlCache
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The session-only playback speed: what PlayerRepositoryImpl sends to the player, and when it holds back. */
@RunWith(RobolectricTestRunner::class)
class PlayerRepositoryPlaybackSpeedTest {
    private val musicRepository: MusicRepository = mockk { every { trackDeletions } returns MutableSharedFlow() }
    private val streamUrlCache: StreamUrlCache = mockk<StreamUrlCache>(relaxUnitFun = true).also { every { it.get(any()) } returns null }
    private val controller: MediaController = mockk(relaxed = true) { every { isConnected } returns true }
    private val trackIdentityEvents: TrackIdentityEvents = mockk { every { changes } returns MutableSharedFlow() }
    private val together = ListenTogetherController(ApplicationProvider.getApplicationContext())

    private fun item(id: Long): MediaItem = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri("https://example.test/$id")
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle("T$id").setArtist("A")
                .setExtras(Bundle().apply { putLong(EXTRA_TRACK_ID, id) }).build(),
        )
        .build()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun build(): PlayerRepositoryImpl {
        val repo = PlayerRepositoryImpl(
            context = ApplicationProvider.getApplicationContext(),
            playbackStateStore = mockk(relaxed = true),
            musicRepository = musicRepository,
            streamingPreference = mockk(relaxed = true),
            streamResolver = mockk(),
            streamUrlCache = streamUrlCache,
            connectivity = mockk(relaxed = true),
            trackDao = mockk(relaxed = true),
            playbackResumer = mockk(relaxed = true),
            radioGenerator = mockk(),
            trackIdentityEvents = trackIdentityEvents,
            playbackSessionBus = PlaybackSessionBus(),
            autoplayRadioPreference = AutoplayRadioPreference.Off,
            listenTogether = together,
            diagnosticsLog = PlaybackDiagnosticsLog(),
        )
        repo.controllerDeferred = controller
        idle()
        // The session-bus collector's initial "not alive" releases the seam; re-seat it (see PlayerRepositoryIdleResumeTest).
        repo.controllerDeferred = controller
        // A one-song timeline, so the listener's state refresh has a real item to read.
        every { controller.mediaItemCount } returns 1
        every { controller.getMediaItemAt(0) } returns item(1)
        every { controller.currentMediaItem } returns item(1)
        every { controller.currentMediaItemIndex } returns 0
        return repo
    }

    @Test fun `a picked speed is held to 0_1x - 4x and reaches the player`() {
        val repo = build()
        repo.setPlaybackSpeed(10f)
        idle()
        assertThat(repo.playbackSpeed.value).isEqualTo(4f)
        verify { controller.setPlaybackSpeed(4f) }
    }

    @Test fun `a song change puts the chosen speed back if the player lost it`() {
        val repo = build()
        repo.setPlaybackSpeed(1.5f)
        idle()
        every { controller.playbackParameters } returns PlaybackParameters.DEFAULT
        clearMocks(controller, answers = false)
        repo.playerListener.onMediaItemTransition(item(1), Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        verify { controller.setPlaybackSpeed(1.5f) }
    }

    @Test fun `starting a Listen Together session puts the speed back to normal`() {
        val repo = build()
        repo.setPlaybackSpeed(1.5f)
        idle()
        together.setActive(true)
        idle()
        assertThat(repo.playbackSpeed.value).isEqualTo(1f)
    }

    @Test fun `in a session a speed pick is ignored, since the room's clock sets the pace`() {
        val repo = build()
        together.setActive(true)
        idle()
        clearMocks(controller, answers = false)
        repo.setPlaybackSpeed(1.5f)
        idle()
        // Otherwise the button would show 1.5x while the session player stays at 1x, and the
        // next song after the session would pick 1.5x back up out of nowhere.
        assertThat(repo.playbackSpeed.value).isEqualTo(1f)
        verify(exactly = 0) { controller.setPlaybackSpeed(any()) }
    }

    @Test fun `in a session a song change leaves drift correction's speed alone`() {
        val repo = build()
        together.setActive(true)
        idle()
        every { controller.playbackParameters } returns PlaybackParameters(0.98f) // a drift nudge
        clearMocks(controller, answers = false)
        repo.playerListener.onMediaItemTransition(item(1), Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        verify(exactly = 0) { controller.setPlaybackSpeed(any()) }
    }
}
