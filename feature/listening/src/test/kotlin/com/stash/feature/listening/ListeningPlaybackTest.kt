package com.stash.feature.listening

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.Track
import com.stash.feature.listening.FakeHistory.Companion.TODAY
import org.junit.Test

class ListeningPlaybackTest {

    private fun song(id: Long, downloaded: Boolean) =
        Track(id = id, title = "S$id", artist = "A", filePath = if (downloaded) "/music/$id.flac" else null, dateAdded = 0L)

    @Test fun `online, the queue plays as it is from the tapped song`() {
        val q = listOf(song(1, false), song(2, true), song(3, false))
        assertThat(planPlay(q, 2, online = true, tapped = true)).isEqualTo(PlayPlan.Play(q, 2))
    }

    @Test fun `offline, a tapped song that needs a connection is refused`() {
        val q = listOf(song(1, true), song(2, false))
        assertThat(planPlay(q, 1, online = false, tapped = true)).isEqualTo(PlayPlan.Blocked(SONG_NEEDS_CONNECTION))
    }

    @Test fun `offline, only downloaded songs are queued and the tapped one still starts`() {
        val q = listOf(song(1, true), song(2, false), song(3, true), song(1, true))
        val plan = planPlay(q, 3, online = false, tapped = true) as PlayPlan.Play
        assertThat(plan.tracks.map { it.id }).containsExactly(1L, 3L, 1L).inOrder()
        // The second "1", the one tapped, not the first.
        assertThat(plan.startIndex).isEqualTo(2)
    }

    @Test fun `offline play this day starts at its first downloaded song, or says none are`() {
        val q = listOf(song(1, false), song(2, true))
        assertThat(planPlay(q, 0, online = false, tapped = false)).isEqualTo(PlayPlan.Play(listOf(song(2, true)), 0))
        assertThat(planPlay(listOf(song(1, false)), 0, online = false, tapped = false))
            .isEqualTo(PlayPlan.Blocked(DAY_NEEDS_CONNECTION))
        assertThat(planPlay(emptyList(), 0, online = true, tapped = false)).isInstanceOf(PlayPlan.Blocked::class.java)
    }

    @Test fun `a day's queue keeps the order heard, a song repeated in a row once`() {
        val fake = FakeHistory()
        val a = fake.track(1); val b = fake.track(2)
        val rows = listOf(
            fake.add(a, TODAY, 1), fake.add(b, TODAY, 2), fake.add(b, TODAY, 3), fake.add(a, TODAY, 4),
        )
        val fromRepeat = dayQueue(rows, rows[2].event.id)
        assertThat(fromRepeat.tracks.map { it.id }).containsExactly(1L, 2L, 1L).inOrder()
        assertThat(fromRepeat.startIndex).isEqualTo(1)
        assertThat(dayQueue(rows, null).startIndex).isEqualTo(0)
        assertThat(dayQueue(rows, rows[3].event.id).startIndex).isEqualTo(2)
    }

    @Test fun `a tapped listen removed meanwhile starts the queue at the nearest one`() {
        val fake = FakeHistory()
        val a = fake.track(1); val b = fake.track(2); val c = fake.track(3)
        val first = fake.add(a, TODAY, 10)
        val gone = fake.add(b, TODAY, 20)
        val last = fake.add(c, TODAY, 40)
        // The day as read now, without the tapped listen (minute 20); minute 10 is nearer than 40.
        val q = dayQueue(listOf(first, last), gone.event.id, gone.event.startedAt)
        assertThat(q.tracks.map { it.id }).containsExactly(1L, 3L).inOrder()
        assertThat(q.startIndex).isEqualTo(0)
        val q2 = dayQueue(listOf(first, last), gone.event.id, gone.event.startedAt + 15 * 60_000L)
        assertThat(q2.startIndex).isEqualTo(1)
    }
}
