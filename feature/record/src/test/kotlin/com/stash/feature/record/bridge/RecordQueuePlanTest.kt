package com.stash.feature.record.bridge

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.Track
import org.junit.Test

/** What a tap in the record queues: the songs Stash found, from the one asked for, with Library's connection rule. */
class RecordQueuePlanTest {
    private fun track(id: Long, file: Boolean = false) = Track(id = id, title = "T$id", artist = "A", filePath = if (file) "/music/$id.flac" else null, dateAdded = 0)

    @Test fun `online, every song found plays, from the one asked for`() {
        val plan = planRecordQueue(listOf(track(1), track(2), track(3)), index = 1, online = true)
        assertThat(plan).isEqualTo(RecordQueuePlan.Play(listOf(track(1), track(2), track(3)), 1))
    }

    @Test fun `a song not found is left out, and the start stays on the song asked for, else the next`() {
        val tracks = listOf(track(1), null, track(3), null, track(5))
        assertThat(planRecordQueue(tracks, index = 2, online = true)).isEqualTo(RecordQueuePlan.Play(listOf(track(1), track(3), track(5)), 1))
        // the one asked for wasn't found: the next one that was
        assertThat(planRecordQueue(tracks, index = 3, online = true)).isEqualTo(RecordQueuePlan.Play(listOf(track(1), track(3), track(5)), 2))
        // nothing after it: the last one
        assertThat(planRecordQueue(listOf(track(1), null), index = 1, online = true)).isEqualTo(RecordQueuePlan.Play(listOf(track(1)), 0))
    }

    @Test fun `offline, only songs on the phone, from the first one there at or after the start`() {
        val tracks = listOf(track(1, file = true), track(2), track(3, file = true), track(4))
        assertThat(planRecordQueue(tracks, index = 1, online = false)).isEqualTo(RecordQueuePlan.Play(listOf(track(1, true), track(3, true)), 1))
    }

    @Test fun `nothing to play says why`() {
        assertThat(planRecordQueue(listOf(track(1), track(2)), index = 0, online = false)).isEqualTo(RecordQueuePlan.Blocked(NONE_DOWNLOADED))
        assertThat(planRecordQueue(listOf(null, null), index = 0, online = true)).isEqualTo(RecordQueuePlan.Blocked(NONE_FOUND))
        assertThat(planRecordQueue(emptyList(), index = 0, online = true)).isEqualTo(RecordQueuePlan.Blocked(NONE_FOUND))
    }
}
