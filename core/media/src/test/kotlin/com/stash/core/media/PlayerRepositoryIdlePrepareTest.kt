package com.stash.core.media

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
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

/**
 * The defensive prepare() on a song change that lands IDLE: it rescues a silent stall, but must stay out
 * of the way of a real error, which onPlayerError handles (a second load of a missing file used to fail
 * after the skip and take the next, playable song down with it).
 */
@RunWith(RobolectricTestRunner::class)
class PlayerRepositoryIdlePrepareTest {
    private val musicRepository: MusicRepository = mockk { every { trackDeletions } returns MutableSharedFlow() }
    private val streamUrlCache: StreamUrlCache = mockk<StreamUrlCache>(relaxUnitFun = true).also { every { it.get(any()) } returns null }
    private val controller: MediaController = mockk(relaxed = true) { every { isConnected } returns true }
    private val trackIdentityEvents: TrackIdentityEvents = mockk { every { changes } returns MutableSharedFlow() }

    private fun item(id: Long): MediaItem = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri("content://example.test/$id")
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
            radioEngines = radioEngines(mockk()),
            trackIdentityEvents = trackIdentityEvents,
            playbackSessionBus = PlaybackSessionBus(),
            autoplayRadioPreference = AutoplayRadioPreference.Off,
            listenTogether = ListenTogetherController(ApplicationProvider.getApplicationContext()),
            diagnosticsLog = PlaybackDiagnosticsLog(),
        )
        repo.controllerDeferred = controller
        idle()
        // The session-bus collector's initial "not alive" releases the seam; re-seat it (see PlayerRepositoryIdleResumeTest).
        repo.controllerDeferred = controller
        every { controller.mediaItemCount } returns 2
        every { controller.getMediaItemAt(0) } returns item(1)
        every { controller.getMediaItemAt(1) } returns item(2)
        every { controller.currentMediaItem } returns item(1)
        every { controller.currentMediaItemIndex } returns 0
        every { controller.playbackState } returns Player.STATE_IDLE
        every { controller.playWhenReady } returns true
        clearMocks(controller, answers = false)
        return repo
    }

    @Test fun `a song change that lands idle with no error is prepared`() {
        val repo = build()
        every { controller.playerError } returns null
        repo.playerListener.onMediaItemTransition(item(1), Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        verify(exactly = 1) { controller.prepare() }
    }

    @Test fun `a song change that lands idle on an error leaves the recovery to onPlayerError`() {
        val repo = build()
        every { controller.playerError } returns
            PlaybackException("Source error", null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        repo.playerListener.onMediaItemTransition(item(1), Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        verify(exactly = 0) { controller.prepare() }
    }

    @Test fun `a paused restore that is idle on purpose is left alone`() {
        val repo = build()
        every { controller.playerError } returns null
        every { controller.playWhenReady } returns false
        repo.playerListener.onMediaItemTransition(item(1), Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        verify(exactly = 0) { controller.prepare() }
    }
}
