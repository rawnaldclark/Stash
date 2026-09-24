package com.stash.core.media

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.AutoplayRadioPreference
import com.stash.core.data.radio.RadioSession
import com.stash.core.data.radio.RadioStationGenerator
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.TrackIdentityEvents
import com.stash.core.media.listen.ListenTogetherController
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_ID
import com.stash.core.media.streaming.StreamUrlCache
import com.stash.core.model.PlaybackSource
import com.stash.core.model.RepeatMode
import com.google.common.truth.Truth.assertThat
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PlayerRepositoryListenTogetherTest {
    private val playbackStateStore: PlaybackStateStore = mockk(relaxed = true)
    private val musicRepository: MusicRepository = mockk { every { trackDeletions } returns MutableSharedFlow() }
    private val streamUrlCache: StreamUrlCache = mockk<StreamUrlCache>(relaxUnitFun = true).also { every { it.get(any()) } returns null }
    private val controller: MediaController = mockk(relaxed = true) { every { isConnected } returns true }
    private val playbackResumer: PlaybackResumer = mockk(relaxed = true)
    private val radioGenerator: RadioStationGenerator = mockk { coEvery { start(any()) } returns (mockk<RadioSession>() to emptyList()) }
    private val trackIdentityEvents: TrackIdentityEvents = mockk { every { changes } returns MutableSharedFlow() }
    private val together = ListenTogetherController(ApplicationProvider.getApplicationContext())

    private val autoplayOn = object : AutoplayRadioPreference {
        override val enabled = flowOf(true)
        override suspend fun setEnabled(value: Boolean) = Unit
    }

    private fun item(id: Long): MediaItem = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri("https://example.test/$id")
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle("T$id").setArtist("A")
                .setExtras(Bundle().apply { putLong(EXTRA_TRACK_ID, id) }).build(),
        )
        .build()

    private fun build(autoplay: AutoplayRadioPreference = AutoplayRadioPreference.Off): PlayerRepositoryImpl {
        val repo = PlayerRepositoryImpl(
            context = ApplicationProvider.getApplicationContext(),
            playbackStateStore = playbackStateStore,
            musicRepository = musicRepository,
            streamingPreference = mockk(relaxed = true),
            streamResolver = mockk(),
            streamUrlCache = streamUrlCache,
            connectivity = mockk(relaxed = true),
            trackDao = mockk(relaxed = true),
            playbackResumer = playbackResumer,
            radioGenerator = radioGenerator,
            trackIdentityEvents = trackIdentityEvents,
            playbackSessionBus = PlaybackSessionBus(),
            autoplayRadioPreference = autoplay,
            listenTogether = together,
        )
        repo.controllerDeferred = controller
        shadowOf(Looper.getMainLooper()).idle()
        // The session-bus collector's initial "not alive" releases the seam; re-seat it (see PlayerRepositoryIdleResumeTest).
        repo.controllerDeferred = controller
        clearMocks(playbackStateStore, playbackResumer, answers = false)
        return repo
    }

    /** The one-song player a session leaves behind, playing. */
    private fun sessionPlayer() {
        every { controller.mediaItemCount } returns 1
        every { controller.getMediaItemAt(0) } returns item(99)
        every { controller.currentMediaItem } returns item(99)
        every { controller.currentMediaItemIndex } returns 0
        every { controller.isPlaying } returns true
    }

    @Test fun `outside a session a refresh saves the queue, which is what the gate must stop`() {
        val repo = build()
        sessionPlayer()
        repo.updateState(controller)
        shadowOf(Looper.getMainLooper()).idle()
        coVerify { playbackStateStore.saveQueue(listOf(99L), any(), any(), any()) }
    }

    @Test fun `during a session the one-song player is never saved over the user's queue`() {
        val repo = build()
        sessionPlayer()
        together.setActive(true)
        repo.updateState(controller)
        shadowOf(Looper.getMainLooper()).idle()
        coVerify(exactly = 0) { playbackStateStore.saveQueue(any(), any(), any(), any()) }
        coVerify(exactly = 0) { playbackStateStore.savePosition(any(), any(), any()) }
    }

    @Test fun `during a session autoplay radio stays out of it`() {
        val repo = build(autoplay = autoplayOn)
        together.setActive(true)
        sessionPlayer()
        repo.updateState(controller)
        shadowOf(Looper.getMainLooper()).idle()
        coVerify(exactly = 0) { radioGenerator.start(any()) }
    }

    @Test fun `without a session the same last song would have started autoplay radio`() {
        val repo = build(autoplay = autoplayOn)
        sessionPlayer()
        repo.updateState(controller)
        shadowOf(Looper.getMainLooper()).idle()
        coVerify(exactly = 1) { radioGenerator.start(any()) }
    }

    @Test fun `during a session next-track prefetch does nothing`() = runTest {
        val repo = build()
        together.setActive(true)
        repo.prefetchNextTrack()
        verify(exactly = 0) { controller.nextMediaItemIndex }
    }

    @Test fun `when a session ends the saved queue comes back paused and unprepared`() {
        val plan = PlaybackResumer.ResumePlan(
            tracks = listOf(TrackEntity(id = 1, title = "15 Step", artist = "Radiohead"), TrackEntity(id = 2, title = "Reckoner", artist = "Radiohead")),
            startIndex = 1, positionMs = 44_000L, isShuffled = false, repeatMode = RepeatMode.OFF, source = PlaybackSource.Unknown,
        )
        build()
        coEvery { playbackResumer.buildResumePlan() } returns plan
        every { controller.mediaItemCount } returns 0
        together.setActive(true)
        together.setActive(false)
        // setQueueInternal builds the items on Dispatchers.IO, so one idle() returns before setMediaItems.
        val deadline = System.currentTimeMillis() + 5_000
        while (together.restorePending && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        verify { controller.setMediaItems(any<List<MediaItem>>(), 1, 44_000L) }
        verify(exactly = 0) { controller.prepare() }
        verify(exactly = 0) { controller.play() }
        assertThat(together.restorePending).isFalse()
    }

    @Test fun `between the end of a session and the restore, the session's song is never saved`() {
        val repo = build()
        coEvery { playbackResumer.buildResumePlan() } returns null
        sessionPlayer()
        together.setActive(true)
        together.setActive(false) // the restore is queued on the main looper, not run yet
        assertThat(together.restorePending).isTrue()
        repo.updateState(controller)
        shadowOf(Looper.getMainLooper()).idle()
        coVerify(exactly = 0) { playbackStateStore.saveQueue(any(), any(), any(), any()) }
        coVerify(exactly = 0) { playbackStateStore.savePosition(any(), any(), any()) }
        assertThat(together.restorePending).isFalse() // cleared even when nothing was saved to put back
        repo.updateState(controller)
        shadowOf(Looper.getMainLooper()).idle()
        coVerify { playbackStateStore.saveQueue(listOf(99L), any(), any(), any()) }
    }

    @Test fun `during a session the host's Next and Previous reach the session, even on a one-song player`() = runTest {
        val repo = build()
        sessionPlayer()
        every { controller.hasNextMediaItem() } returns false
        every { controller.hasPreviousMediaItem() } returns false
        together.setActive(true)
        repo.skipNext()
        repo.skipPrevious()
        // ListenTogetherPlayer turns these into onNext/onPrevious for the room (Task 17).
        verify(exactly = 1) { controller.seekToNext() }
        verify(exactly = 1) { controller.seekToPrevious() }
        verify(exactly = 0) { controller.seekToNextMediaItem() }
    }
}
