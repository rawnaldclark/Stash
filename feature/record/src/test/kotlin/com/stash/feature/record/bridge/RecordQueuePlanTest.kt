package com.stash.feature.record.bridge

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** What a tap in the record queues, and the player that queues it: the songs Stash found, from the one asked for. */
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
        assertThat(planRecordQueue(listOf(track(1), track(2)), index = 0, online = false)).isEqualTo(RecordQueuePlan.Blocked(RecordNote.NoneDownloaded))
        assertThat(planRecordQueue(listOf(null, null), index = 0, online = true)).isEqualTo(RecordQueuePlan.Blocked(RecordNote.NoneFound))
        assertThat(planRecordQueue(emptyList(), index = 0, online = true)).isEqualTo(RecordQueuePlan.Blocked(RecordNote.NoneFound))
    }

    // ── RecordPlayback ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `the newest tap wins, and it plays on its own scope (the screen can go)`() = runTest {
        val slow = CompletableDeferred<Unit>()
        val queued = mutableListOf<Pair<List<String>, String>>()
        val playback = RecordPlayback(
            match = { songs ->
                if (songs[0].title == "First") slow.await()
                songs.mapIndexed { i, s -> Track(id = i + 1L, title = s.title, artist = s.artist, dateAdded = 0) }
            },
            setQueue = { tracks, _, from -> queued += tracks.map { it.title } to from },
            online = { true },
            toast = {},
            scope = backgroundScope,
        )
        playback.play(listOf(RecordSong("First", "A")), 0, "one")
        runCurrent()
        playback.play(listOf(RecordSong("Second", "A"), RecordSong("Third", "A")), 1, "two")
        slow.complete(Unit)
        runCurrent()
        assertThat(queued).containsExactly(listOf("Second", "Third") to "two")
    }

    @Test fun `when nothing plays it says so, on the screen while it listens, a toast once it has gone`() = runTest {
        val toasts = mutableListOf<RecordNote>()
        val playback = RecordPlayback(
            match = { songs -> songs.map { null } },
            setQueue = { _, _, _ -> error("nothing to queue") },
            online = { true },
            toast = { toasts += it },
            scope = backgroundScope,
        )
        playback.play(listOf(RecordSong("Gone", "A")), 0, "x")
        runCurrent()
        assertThat(toasts).containsExactly(RecordNote.NoneFound)
        val heard = mutableListOf<RecordNote>()
        val listening = backgroundScope.launchCollect(playback) { heard += it }
        runCurrent()
        playback.play(listOf(RecordSong("Gone", "A")), 0, "x")
        runCurrent()
        assertThat(heard).containsExactly(RecordNote.NoneFound)
        assertThat(toasts).hasSize(1)
        listening.cancel()
    }

    @Test fun `a failure while matching is a note, never a crash`() = runTest {
        val toasts = mutableListOf<RecordNote>()
        val playback = RecordPlayback(
            match = { error("database gone") },
            setQueue = { _, _, _ -> },
            online = { true },
            toast = { toasts += it },
            scope = backgroundScope,
        )
        playback.play(listOf(RecordSong("X", "A")), 0, "x")
        runCurrent()
        assertThat(toasts).containsExactly(RecordNote.PlayFailed)
    }

    private fun CoroutineScope.launchCollect(p: RecordPlayback, on: (RecordNote) -> Unit) =
        launch(Dispatchers.Unconfined) { p.notes.collect { on(it) } }
}
