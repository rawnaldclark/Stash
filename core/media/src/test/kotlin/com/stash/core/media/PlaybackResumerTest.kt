package com.stash.core.media

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.PlaybackSource
import com.stash.core.model.RepeatMode
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Unit tests for [PlaybackResumer] — the testable extract of the
 * Bluetooth / Android Auto "resume the full queue" decision wired into
 * [com.stash.core.media.service.StashPlaybackService.onPlaybackResumption].
 *
 * Reproduces the original bug: resumption returned a single-item queue at
 * position 0, so next/prev did nothing and playback restarted the wrong
 * track from the beginning.
 */
class PlaybackResumerTest {

    private val playbackStateStore: PlaybackStateStore = mockk()
    private val trackDao: TrackDao = mockk()

    private val resumer = PlaybackResumer(playbackStateStore, trackDao)

    private fun track(id: Long) = TrackEntity(id = id, title = "t$id", artist = "a$id")

    @Test
    fun fullQueueRestored_atSavedTrackAndPosition() = runTest {
        coEvery { playbackStateStore.getLastPlaybackState() } returns SavedPlaybackState(
            trackId = 20,
            positionMs = 42_000,
            queueIndex = 1,
            queueTrackIds = listOf(10, 20, 30),
            isShuffled = false,
            repeatMode = RepeatMode.OFF,
            source = PlaybackSource.Unknown,
        )
        // `IN` returns rows in arbitrary order — resumer must re-sort.
        coEvery { trackDao.getByIds(listOf(10, 20, 30)) } returns
            listOf(track(30), track(10), track(20))

        val plan = resumer.buildResumePlan()!!

        // The whole queue is restored — this is what makes next/prev work.
        assertThat(plan.tracks.map { it.id }).containsExactly(10L, 20L, 30L).inOrder()
        // Resumes on the saved current track, at the saved position.
        assertThat(plan.startIndex).isEqualTo(1)
        assertThat(plan.positionMs).isEqualTo(42_000)
        assertThat(plan.isShuffled).isFalse()
    }

    @Test
    fun noSavedState_returnsNullForSingleTrackFallback() = runTest {
        coEvery { playbackStateStore.getLastPlaybackState() } returns null

        assertThat(resumer.buildResumePlan()).isNull()
    }

    @Test
    fun emptyPersistedQueue_returnsNullForSingleTrackFallback() = runTest {
        coEvery { playbackStateStore.getLastPlaybackState() } returns SavedPlaybackState(
            trackId = 7,
            positionMs = 0,
            queueIndex = 0,
            queueTrackIds = emptyList(),
            isShuffled = false,
            repeatMode = RepeatMode.OFF,
            source = PlaybackSource.Unknown,
        )

        assertThat(resumer.buildResumePlan()).isNull()
    }

    @Test
    fun savedTrackMissing_startsFromClampedSavedIndex() = runTest {
        // Saved current track (20) was deleted from the DB; the other two
        // remain. Start index falls back to the saved queueIndex, clamped
        // into the rebuilt list's range.
        coEvery { playbackStateStore.getLastPlaybackState() } returns SavedPlaybackState(
            trackId = 20,
            positionMs = 5_000,
            queueIndex = 5,
            queueTrackIds = listOf(10, 20, 30),
            isShuffled = true,
            repeatMode = RepeatMode.ALL,
            source = PlaybackSource.Library,
        )
        coEvery { trackDao.getByIds(listOf(10, 20, 30)) } returns listOf(track(10), track(30))

        val plan = resumer.buildResumePlan()!!

        assertThat(plan.tracks.map { it.id }).containsExactly(10L, 30L).inOrder()
        assertThat(plan.startIndex).isEqualTo(1) // coerced from 5 into [0, 1]
        assertThat(plan.isShuffled).isTrue()
    }

    @Test
    fun allQueueRowsGone_returnsNullForSingleTrackFallback() = runTest {
        coEvery { playbackStateStore.getLastPlaybackState() } returns SavedPlaybackState(
            trackId = 20,
            positionMs = 0,
            queueIndex = 0,
            queueTrackIds = listOf(10, 20),
            isShuffled = false,
            repeatMode = RepeatMode.OFF,
            source = PlaybackSource.Unknown,
        )
        coEvery { trackDao.getByIds(listOf(10, 20)) } returns emptyList()

        assertThat(resumer.buildResumePlan()).isNull()
    }
}
