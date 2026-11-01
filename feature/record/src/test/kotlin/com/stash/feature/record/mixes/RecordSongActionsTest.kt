package com.stash.feature.record.mixes

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.radio.RadioSeed
import com.stash.core.model.RadioStartResult
import com.stash.core.model.Track
import com.stash.core.model.weblink.SongKey
import com.stash.feature.record.bridge.RecordSong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * A song row's Like and menu ([RecordSongActions]) on a song that is often only words: a blocked song refused before any row is
 * made, else the row found or made, then Stash's own action on it; Like is the app's heart (RecordSongActionsLikeMirrorTest). And the hearts
 * ([RowLikes], [Likes]): a tap shows at once, stays until the likes agree, goes back when it couldn't be written; a song's taps
 * one after another; liked by its words or by a Spotify id a liked row carries; an unlike takes every such row out.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordSongActionsTest {
    private val song = RecordSong(title = "Lost 03", artist = "Lost Band", album = "Faded", sid = "sid3", cover = "https://cdn/c.jpg")
    private fun row(id: Long, yt: String? = null, unavailable: Boolean = false) =
        Track(id = id, title = "Lost 03", artist = "Lost Band", youtubeId = yt, isStreamable = !unavailable, isStreamableCheckedAt = if (unavailable) 1L else null)

    @Test fun `Like finds or makes the song's row, then likes that row in Stash - and nothing else`() = runTest(UnconfinedTestDispatcher()) {
        val asked = mutableListOf<List<RecordSong>>()
        val liked = mutableListOf<Long>()
        val actions = testSongActions(rows = { asked += it; listOf(row(42)) }, like = { liked += it }, scope = backgroundScope)
        assertThat(actions.like(song).await()).isEqualTo(42L)
        // the matcher was asked for this one song, with its Spotify id and its cover (a new stream-only row takes it)
        assertThat(asked).containsExactly(listOf(song))
        assertThat(liked).containsExactly(42L)
    }

    @Test fun `a song with no row, or a write that fails, is no like - and says so`() = runTest(UnconfinedTestDispatcher()) {
        val said = mutableListOf<SongLine>()
        val none = testSongActions(rows = { listOf(null) }, toast = { said += it }, scope = backgroundScope)
        assertThat(none.like(song).await()).isNull()
        val broken = testSongActions(rows = { listOf(row(1)) }, like = { error("database locked") }, toast = { said += it }, scope = backgroundScope)
        assertThat(broken.like(song).await()).isNull()
        val unbroken = testSongActions(unlike = { error("database locked") }, toast = { said += it }, scope = backgroundScope)
        assertThat(unbroken.unlike(listOf(5)).await()).isFalse()
        assertThat(said).containsExactly(SongLine.LikeFailed, SongLine.LikeFailed, SongLine.UnlikeFailed).inOrder()
    }

    @Test fun `a blocked song is refused as Search refuses it - no row made, nothing liked, queued or listed`() = runTest(UnconfinedTestDispatcher()) {
        val said = mutableListOf<SongLine>()
        var rowsAsked = 0
        val checked = mutableListOf<Triple<String, String, String?>>()
        var liked = 0
        var queued = 0
        var listed = 0
        var made = 0
        val actions = testSongActions(
            rows = { rowsAsked++; listOf(row(9)) },
            like = { liked++ },
            addNext = { queued++; true },
            addToQueue = { queued++; true },
            addToPlaylist = { _, _ -> listed++ },
            createPlaylist = { made++; 3L },
            isBlocked = { t, a, uri -> checked += Triple(t, a, uri); true },
            toast = { said += it },
            scope = backgroundScope,
        )
        assertThat(actions.like(song).await()).isNull()
        actions.playNext(song)
        actions.addToQueue(song)
        actions.addToPlaylist(song, 5L, "Road trip")
        actions.createPlaylistWith(song, "New one")
        assertThat(rowsAsked).isEqualTo(0)
        assertThat(listOf(liked, queued, listed, made)).containsExactly(0, 0, 0, 0)
        assertThat(said).containsExactly(SongLine.Blocked, SongLine.Blocked, SongLine.Blocked, SongLine.Blocked, SongLine.Blocked)
        // by its words and its Spotify id
        assertThat(checked.first()).isEqualTo(Triple("Lost 03", "Lost Band", "spotify:track:sid3"))
    }

    @Test fun `the open screen hears the line, else it is a toast`() = runTest(UnconfinedTestDispatcher()) {
        val toasts = mutableListOf<SongLine>()
        val actions = testSongActions(rows = { listOf(row(9)) }, toast = { toasts += it }, scope = backgroundScope)
        actions.playNext(song)
        assertThat(toasts).containsExactly(SongLine.PlayingNext)
        val heard = CompletableDeferred<SongLine>()
        backgroundScope.launch { heard.complete(actions.lines.first()) }
        actions.addToQueue(song)
        assertThat(heard.await()).isEqualTo(SongLine.AddedToQueue)
        assertThat(toasts).hasSize(1)
    }

    @Test fun `the menu - play next, add to queue, a radio, a playlist - each on the song's row`() = runTest(UnconfinedTestDispatcher()) {
        val said = mutableListOf<SongLine>()
        val next = mutableListOf<Long>()
        val queued = mutableListOf<Long>()
        val seeds = mutableListOf<RadioSeed.Song>()
        val added = mutableListOf<Pair<Long, Long>>()
        val made = mutableListOf<String>()
        var rowsAsked = 0
        val actions = testSongActions(
            rows = { rowsAsked++; listOf(row(7, yt = "yt7")) },
            addNext = { next += it.id; true },
            addToQueue = { queued += it.id; false },
            startRadio = { seeds += it; RadioStartResult.Started },
            addToPlaylist = { t, p -> added += t to p },
            createPlaylist = { made += it; 99L },
            toast = { said += it },
            scope = backgroundScope,
        )
        actions.playNext(song)
        actions.addToQueue(song)
        actions.addToPlaylist(song, 5L, "Road trip")
        actions.createPlaylistWith(song, "New one")
        assertThat(next).containsExactly(7L)
        assertThat(queued).containsExactly(7L)
        assertThat(added).containsExactly(7L to 5L, 7L to 99L).inOrder()
        assertThat(made).containsExactly("New one")
        // a radio by the words, never making a row for it; your own row's YouTube id when you have one
        val before = rowsAsked
        actions.startRadio(song)
        assertThat(rowsAsked).isEqualTo(before)
        actions.startRadio(song.copy(trackId = 7L))
        assertThat(seeds.map { Triple(it.title, it.artist, it.ytVideoId) }).containsExactly(Triple("Lost 03", "Lost Band", null), Triple("Lost 03", "Lost Band", "yt7")).inOrder()
        assertThat(said).containsExactly(
            SongLine.PlayingNext, SongLine.QueueFailed, SongLine.AddedToPlaylist("Road trip"), SongLine.AddedToPlaylist("New one"),
        ).inOrder()
    }

    @Test fun `in a Listen Together session an add says nothing - the session confirms it`() = runTest(UnconfinedTestDispatcher()) {
        val said = mutableListOf<SongLine>()
        val actions = testSongActions(rows = { listOf(row(7)) }, inListenTogether = { true }, toast = { said += it }, scope = backgroundScope)
        actions.playNext(song)
        actions.addToQueue(song)
        assertThat(said).isEmpty()
    }

    @Test fun `a row the player can't queue has the player's own line, not a second one`() = runTest(UnconfinedTestDispatcher()) {
        val said = mutableListOf<SongLine>()
        // the player refuses it ("Nothing in this queue is playable right now." is its own line)
        val unplayable = testSongActions(rows = { listOf(row(7, unavailable = true)) }, addNext = { false }, addToQueue = { false }, toast = { said += it }, scope = backgroundScope)
        unplayable.playNext(song)
        unplayable.addToQueue(song)
        assertThat(said).isEmpty()
        // a playable row the player still didn't take (no controller): ours
        val noPlayer = testSongActions(rows = { listOf(row(7)) }, addNext = { false }, toast = { said += it }, scope = backgroundScope)
        noPlayer.playNext(song)
        assertThat(said).containsExactly(SongLine.QueueFailed)
    }

    @Test fun `a new playlist whose song can't go in is taken back - no row, no playlist`() = runTest(UnconfinedTestDispatcher()) {
        val said = mutableListOf<SongLine>()
        val deleted = mutableListOf<Long>()
        var made = 0
        val failing = testSongActions(
            rows = { listOf(row(7)) }, createPlaylist = { made++; 31L }, addToPlaylist = { _, _ -> error("constraint") },
            deletePlaylist = { deleted += it }, toast = { said += it }, scope = backgroundScope,
        )
        failing.createPlaylistWith(song, "New one")
        assertThat(made).isEqualTo(1)
        assertThat(deleted).containsExactly(31L)
        assertThat(said).containsExactly(SongLine.PlaylistFailed)
        // no row for the song: no playlist made at all
        val noRow = testSongActions(rows = { listOf(null) }, createPlaylist = { made++; 32L }, scope = backgroundScope)
        noRow.createPlaylistWith(song, "Another")
        assertThat(made).isEqualTo(1)
    }

    @Test fun `a radio that doesn't start says so`() = runTest(UnconfinedTestDispatcher()) {
        val said = mutableListOf<SongLine>()
        testSongActions(startRadio = { RadioStartResult.PlayerNotReady }, toast = { said += it }, scope = backgroundScope).startRadio(song)
        assertThat(said).containsExactly(SongLine.RadioFailed)
    }

    // ── the hearts ─────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun mix(title: String, lib: LibrarySong? = null, sid: String? = null, artist: String = "Lost Band") =
        MixSong(title, artist, null, SongKey.textKey(title, artist), SongKey.songKey(title, artist), sid = sid, lib = lib)

    private fun likes(vararg songs: LibrarySong) = Likes(songs.toList())

    @Test fun `a heart fills at once, stays while the like is written, and the likes take over`() = runTest(UnconfinedTestDispatcher()) {
        val index = MutableStateFlow(likes())
        val write = CompletableDeferred<Unit>()
        val actions = testSongActions(rows = { listOf(row(3)) }, like = { write.await() }, scope = backgroundScope)
        val rows = RowLikes(backgroundScope, actions, index)
        var hearts: RowLikes.Hearts? = null
        backgroundScope.launch { rows.hearts.collect { hearts = it } }
        val s = mix("Lost 03")
        assertThat(hearts!!.liked(s)).isFalse()
        rows.toggle(s, liked = false, cover = null)
        assertThat(hearts!!.liked(s)).isTrue()
        // written; the likes haven't caught up yet: still filled, never a blink
        write.complete(Unit)
        assertThat(hearts!!.liked(s)).isTrue()
        index.value = likes(LibrarySong(3, "Lost 03", "Lost Band"))
        assertThat(hearts!!.liked(s)).isTrue()
        // the likes now say it: a later unlike elsewhere shows too
        index.value = likes()
        assertThat(hearts!!.liked(s)).isFalse()
    }

    @Test fun `a like that couldn't be written goes back`() = runTest(UnconfinedTestDispatcher()) {
        val actions = testSongActions(rows = { listOf(null) }, scope = backgroundScope)
        val rows = RowLikes(backgroundScope, actions, MutableStateFlow(likes()))
        var hearts: RowLikes.Hearts? = null
        backgroundScope.launch { rows.hearts.collect { hearts = it } }
        val s = mix("Nowhere")
        rows.toggle(s, liked = false, cover = null)
        assertThat(hearts!!.liked(s)).isFalse()
    }

    @Test fun `liked by its Spotify id - a synced row whose words aren't the history's - its heart fills and stays`() = runTest(UnconfinedTestDispatcher()) {
        // the like lands (by the history's Spotify id) on a row a Spotify sync made: "Teardrop" by "Massive Attack, Elizabeth Fraser"
        val synced = LibrarySong(77, "Teardrop", "Massive Attack, Elizabeth Fraser", spotifyId = "sidT")
        val index = MutableStateFlow(likes())
        val actions = testSongActions(rows = { listOf(row(77)) }, scope = backgroundScope)
        val rows = RowLikes(backgroundScope, actions, index)
        var hearts: RowLikes.Hearts? = null
        backgroundScope.launch { rows.hearts.collect { hearts = it } }
        val s = mix("Teardrop", sid = "sidT", artist = "Massive Attack")
        rows.toggle(s, liked = false, cover = null)
        index.value = likes(synced)
        assertThat(hearts!!.liked(s)).isTrue()
        // and the likes alone say so (the tap's answer is let go once they agree)
        assertThat(Likes(listOf(synced)).has(s)).isTrue()
        assertThat(Likes(listOf(synced)).has(mix("Teardrop", artist = "Massive Attack"))).isFalse()
    }

    @Test fun `unlike takes out every row liked in Stash that is the song - by its words, its ISRC copy and its Spotify id`() = runTest(UnconfinedTestDispatcher()) {
        val unliked = mutableListOf<Long>()
        val index = MutableStateFlow(
            likes(
                LibrarySong(11, "Lost 03", "Lost Band", isrc = "GB0000000011"),
                LibrarySong(12, "Lost 03", "lost band"),
                LibrarySong(13, "Lost 03 (Single)", "Lost Band, Guest", spotifyId = "sid3"),
                LibrarySong(14, "Other", "Lost Band"),
            ),
        )
        val rows = RowLikes(backgroundScope, testSongActions(unlike = { unliked += it }, scope = backgroundScope), index)
        var hearts: RowLikes.Hearts? = null
        backgroundScope.launch { rows.hearts.collect { hearts = it } }
        val s = mix("Lost 03", sid = "sid3")
        assertThat(hearts!!.liked(s)).isTrue()
        rows.toggle(s, liked = true, cover = null)
        assertThat(unliked).containsExactly(11L, 12L, 13L)
        assertThat(hearts!!.liked(s)).isFalse()
        index.value = likes(LibrarySong(14, "Other", "Lost Band"))
        assertThat(hearts!!.liked(s)).isFalse()
    }

    @Test fun `a second tap waits for the first - an unlike right after a like takes out the row the like landed on`() = runTest(UnconfinedTestDispatcher()) {
        val write = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val actions = testSongActions(
            rows = { listOf(row(55)) },
            like = { id -> order += "like $id"; write.await(); order += "liked $id" },
            unlike = { id -> order += "unlike $id" },
            scope = backgroundScope,
        )
        // the likes never catch up in between: only the landed row says which row to take out
        val rows = RowLikes(backgroundScope, actions, MutableStateFlow(likes()))
        var hearts: RowLikes.Hearts? = null
        backgroundScope.launch { rows.hearts.collect { hearts = it } }
        val s = mix("Lost 03")
        rows.toggle(s, liked = false, cover = null)
        rows.toggle(s, liked = true, cover = null)
        assertThat(hearts!!.liked(s)).isFalse()
        // the unlike hasn't started: it waits for the like to be written
        assertThat(order).containsExactly("like 55")
        write.complete(Unit)
        assertThat(order).containsExactly("like 55", "liked 55", "unlike 55").inOrder()
    }
}
