package com.stash.core.media.widget

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.media.PlaybackResumer
import com.stash.core.model.PlaybackSource
import com.stash.core.model.RepeatMode
import org.junit.Test

/** A widget Next on a stopped service resumes one song on ([skippedOne]). */
class WidgetResumeSkipTest {

    private fun plan(start: Int, size: Int = 3, positionMs: Long = 61_000L, repeat: RepeatMode = RepeatMode.OFF) =
        PlaybackResumer.ResumePlan(
            tracks = (1..size).map { TrackEntity(id = it.toLong(), title = "t$it", artist = "a$it") },
            startIndex = start,
            positionMs = positionMs,
            isShuffled = false,
            repeatMode = repeat,
            source = PlaybackSource.Unknown,
        )

    @Test
    fun `resumes the next song from its start`() {
        // One plan, compared against itself: TrackEntity.dateAdded defaults to Instant.now(), so two
        // separately built queues are never equal.
        val original = plan(start = 0)
        val skipped = original.skippedOne()
        assertThat(skipped.startIndex).isEqualTo(1)
        assertThat(skipped.positionMs).isEqualTo(0L)
        assertThat(skipped.tracks).isSameInstanceAs(original.tracks)
    }

    @Test
    fun `at the end of the queue it wraps under repeat all`() {
        val skipped = plan(start = 2, repeat = RepeatMode.ALL).skippedOne()
        assertThat(skipped.startIndex).isEqualTo(0)
        assertThat(skipped.positionMs).isEqualTo(0L)
    }

    @Test
    fun `at the end of the queue without repeat the song it was left on resumes where it stopped`() {
        val original = plan(start = 2)
        assertThat(original.skippedOne()).isEqualTo(original)
    }

    @Test
    fun `a one-song queue under repeat all doesn't restart the song`() {
        val original = plan(start = 0, size = 1, repeat = RepeatMode.ALL)
        assertThat(original.skippedOne()).isEqualTo(original)
    }
}
