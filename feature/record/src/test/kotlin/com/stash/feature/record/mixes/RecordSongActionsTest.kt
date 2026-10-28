package com.stash.feature.record.mixes

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.radio.RadioSeed
import com.stash.core.model.RadioStartResult
import com.stash.core.model.Track
import com.stash.core.model.weblink.SongIndex
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
 * A song row's Like and menu ([RecordSongActions]) on a song that is only words: the row found or made first, then Stash's own
 * action on it; Like goes to Stash's Liked Songs alone. And the hearts ([RowLikes]): a tap shows at once, stays until the likes
 * agree, and goes back when the like couldn't be written.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordSongActionsTest {
    private val song = RecordSong(title = "Lost 03", artist = "Lost Band", album = "Faded", sid = "sid3", cover = "https://cdn/c.jpg")
    private fun row(id: Long, yt: String? = null) = Track(id = id, title = "Lost 03", artist = "Lost Band", youtubeId = yt)

    @Test fun `Like finds or makes the song's row, then likes that row in Stash - and nothing else`() = runTest(UnconfinedTestDispatcher()) {
        val asked = mutableListOf<List<RecordSong>>()
        val liked = mutableListOf<Long>()
        val actions = testSongActions(rows = { asked += it; listOf(row(42)) }, like = { liked += it }, scope = backgroundScope)
        assertThat(actions.like(song).await()).isTrue()
        // the matcher was asked for this one song, with its Spotify id and its cover (a new stream-only row takes it)
        assertThat(asked).containsExactly(listOf(song))
        assertThat(liked).containsExactly(42L)
    }

    @Test fun `a song with no row, or a write that fails, is no like - and says so`() = runTest(UnconfinedTestDispatcher()) {
        val said = mutableListOf<SongLine>()
        val none = testSongActions(rows = { listOf(null) }, toast = { said += it }, scope = backgroundScope)
        assertThat(none.like(song).await()).isFalse()
        val broken = testSongActions(rows = { listOf(row(1)) }, like = { error("database locked") }, toast = { said += it }, scope = backgroundScope)
        assertThat(broken.like(song).await()).isFalse()
        val unbroken = testSongActions(unlike = { error("database locked") }, toast = { said += it }, scope = backgroundScope)
        assertThat(unbroken.unlike(listOf(5)).await()).isFalse()
        assertThat(said).containsExactly(SongLine.LikeFailed, SongLine.LikeFailed, SongLine.UnlikeFailed).inOrder()
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

    @Test fun `a radio that doesn't start says so`() = runTest(UnconfinedTestDispatcher()) {
        val said = mutableListOf<SongLine>()
        testSongActions(startRadio = { RadioStartResult.PlayerNotReady }, toast = { said += it }, scope = backgroundScope).startRadio(song)
        assertThat(said).containsExactly(SongLine.RadioFailed)
    }

    // ── the hearts ─────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun mix(title: String, lib: LibrarySong? = null) =
        MixSong(title, "Lost Band", null, SongKey.textKey(title, "Lost Band"), SongKey.songKey(title, "Lost Band"), lib = lib)

    private fun likes(vararg songs: LibrarySong): SongIndex<LibrarySong> = HistoryMixes.haveIndex(songs.toList())

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

    @Test fun `unlike takes the liked row out - the one the likes know, found by the song's words`() = runTest(UnconfinedTestDispatcher()) {
        val unliked = mutableListOf<Long>()
        val index = MutableStateFlow(likes(LibrarySong(11, "Lost 03", "Lost Band", isrc = "GB0000000011")))
        val rows = RowLikes(backgroundScope, testSongActions(unlike = { unliked += it }, scope = backgroundScope), index)
        var hearts: RowLikes.Hearts? = null
        backgroundScope.launch { rows.hearts.collect { hearts = it } }
        val s = mix("Lost 03")
        assertThat(hearts!!.liked(s)).isTrue()
        rows.toggle(s, liked = true, cover = null)
        assertThat(unliked).containsExactly(11L)
        assertThat(hearts!!.liked(s)).isFalse()
        index.value = likes()
        assertThat(hearts!!.liked(s)).isFalse()
    }
}
