package com.stash.feature.record.mixes

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.RecordTasteDao
import com.stash.core.data.db.entity.RecordTasteEntity
import com.stash.core.model.weblink.SongKey
import com.stash.feature.record.archive.RecordArchiveSource
import com.stash.feature.record.art.Found
import com.stash.feature.record.art.MemoryArtStore
import com.stash.feature.record.art.RecordArt
import com.stash.feature.record.bridge.RecordPlayback
import com.stash.feature.record.imports.MemoryPrefs
import com.stash.feature.record.taste.SongStat
import com.stash.feature.record.taste.Taste
import com.stash.feature.record.taste.TasteRows
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.EmptyCoroutineContext

/**
 * A mix's page: a record that can't be read says so (never a blank page, never "gone"); a mix saved before opens its playlist
 * while these very songs are the mix and the playlist is still there, and offers Save as playlist again once its songs change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RecordMixViewModelTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun reset() = Dispatchers.resetMain()

    private fun stat(title: String) = SongStat(
        key = SongKey.songKey(title, "Band"), title = title, artist = "Band", starts = 10, plays = 10, full = 10, chosen = 0, skips = 0,
        years = 3, listenedMs = 2_000_000, first = 1_600_000_000_000, last = 1_700_000_000_000, peak = 2, peakAt = 0, weight = 1.0,
        depth = 1.0, avoid = false,
    )

    private fun taste(titles: List<String>, computedAt: Long) = titles.map(::stat).let { songs ->
        Taste(
            computedAt = computedAt, first = 1_577_836_800_000, last = 1_700_000_000_000, plays = 0, listenedMs = 0, artists = 0,
            songs = songs, eras = emptyList(), constants = songs.map { it.key }, lost = emptyList(), obsessions = emptyList(), found = null,
            years = emptyList(), topArtists = emptyList(), seedArtists = emptyList(), yearTops = emptyList(),
        )
    }

    /** The record's taste as the database holds it: [taste] read now, [at] what the mixes follow; or a read that fails. */
    private class Db(var taste: Taste?, var broken: Boolean = false) {
        val at = MutableStateFlow(taste?.computedAt)
        val dao: RecordTasteDao = mockk(relaxed = true) {
            coEvery { computedAt() } answers { if (broken) error("database locked") else taste?.computedAt }
            coEvery { whole() } answers {
                taste?.let { t -> RecordTasteDao.Whole(RecordTasteEntity(v = 1, computedAt = t.computedAt, body = TasteRows.body(t)), TasteRows.songs(t)) }
            }
            coEvery { keepKeys() } returns emptyList()
            every { computedAtFlow() } returns at
            every { keepKeysFlow() } returns flowOf(emptyList())
        }

        fun write(t: Taste) {
            taste = t
            at.value = t.computedAt
        }
    }

    private val nine = (1..9).map { "Song $it" }

    private fun viewModel(db: Db, saves: RecordMixSaves, names: Map<Long, String> = emptyMap(), newId: Long = 7L) = RecordMixViewModel(
        savedState = SavedStateHandle(mapOf(RecordMixViewModel.ID to "constants")),
        mixes = RecordMixes(
            tastes = db.dao,
            between = { _, _ -> emptyList() },
            own = RecordArchiveSource { _, emit -> emit(emptyList(), true) },
            library = { emptyList() },
            libraryStamp = flowOf(Unit),
            io = EmptyCoroutineContext,
        ),
        art = RecordArt(lookUp = { Found.Cover(null) }, store = MemoryArtStore(), now = { 0L }, work = EmptyCoroutineContext),
        playback = RecordPlayback(
            match = { songs -> songs.map { null } },
            setQueue = { _, _, _ -> },
            online = { true },
            toast = {},
            scope = CoroutineScope(SupervisorJob()),
        ),
        saver = RecordMixSaver(rows = { songs -> songs.map { null } }, create = { _, _ -> newId }, nameOf = { names[it] }),
        saves = saves,
    ).also { it.useWords("en-US", ResourceMixWords(context)) }

    /** The page once [ready] holds (the mix's words are worked out off the main thread). */
    private suspend fun RecordMixViewModel.awaitState(ready: (RecordMixViewModel.State) -> Boolean): RecordMixViewModel.State =
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                while (!ready(state.value)) delay(5)
                state.value
            }
        }

    private suspend fun RecordMixViewModel.shown(): RecordMixViewModel.State.Found =
        awaitState { it is RecordMixViewModel.State.Found } as RecordMixViewModel.State.Found

    @Test fun `a record that can't be read says so, and Try again reads it again`() = runTest {
        val db = Db(null, broken = true)
        val vm = viewModel(db, RecordMixSaves(MemoryPrefs()))
        assertThat(vm.state.value).isEqualTo(RecordMixViewModel.State.Failed)
        db.broken = false
        vm.retry()
        // read now: no taste, so the mix is gone (the record says so, with the way back)
        assertThat(vm.state.value).isEqualTo(RecordMixViewModel.State.Gone)
    }

    @Test fun `a saved mix opens its playlist while its songs are the same, and offers Save again once they change`() = runTest {
        val prefs = MemoryPrefs()
        val saves = RecordMixSaves(prefs)
        val names = mapOf(7L to "The ones that stayed")

        // never saved: Save as playlist; saved, the button opens it
        val first = viewModel(Db(taste(nine, 1)), saves, names)
        assertThat(first.shown().mix.items).hasSize(9)
        assertThat(first.saved.value).isNull()
        first.save()
        assertThat(first.saved.value).isEqualTo(RecordMixViewModel.Saved(7L, "The ones that stayed"))

        // come back, the same songs: Open
        val again = viewModel(Db(taste(nine, 1)), saves, names)
        again.shown()
        assertThat(again.saved.value).isEqualTo(RecordMixViewModel.Saved(7L, "The ones that stayed"))

        // come back after the songs changed (another day's week, the year going on): Save as playlist again
        val changed = viewModel(Db(taste(nine + "Song 10", 2)), saves, names)
        assertThat(changed.shown().mix.items).hasSize(10)
        assertThat(changed.saved.value).isNull()

        // the same songs, the playlist deleted since: Save again
        val deleted = viewModel(Db(taste(nine, 1)), saves, emptyMap())
        deleted.shown()
        assertThat(deleted.saved.value).isNull()
    }

    @Test fun `the songs changing while the page is open offer Save again`() = runTest {
        val db = Db(taste(nine, 1))
        val vm = viewModel(db, RecordMixSaves(MemoryPrefs()), mapOf(7L to "The ones that stayed"))
        vm.shown()
        vm.save()
        assertThat(vm.saved.value).isNotNull()
        db.write(taste(nine + "Song 10", 2))
        vm.awaitState { (it as? RecordMixViewModel.State.Found)?.mix?.items?.size == 10 }
        assertThat(vm.saved.value).isNull()
    }

    @Test fun `an entry kept by the mix's id alone reads as contents unknown - Save is offered`() = runTest {
        val prefs = MemoryPrefs().apply { map["mix_saved:constants"] = 7L } // the id-only entry of the build before
        val vm = viewModel(Db(taste(nine, 1)), RecordMixSaves(prefs), mapOf(7L to "The ones that stayed"))
        vm.shown()
        assertThat(vm.saved.value).isNull()
    }

    @Test fun `a mix's songs in brief - the same keys in the same order, the same contents`() {
        fun song(t: String) = MixSong(title = t, artist = "Band", album = null, key = SongKey.songKey(t, "Band"), songKey = SongKey.songKey(t, "Band"))
        val a = listOf(song("One"), song("Two"))
        assertThat(RecordMixSaves.contentsOf(a)).isEqualTo(RecordMixSaves.contentsOf(listOf(song("One"), song("Two"))))
        assertThat(RecordMixSaves.contentsOf(a)).isNotEqualTo(RecordMixSaves.contentsOf(a.reversed()))
        assertThat(RecordMixSaves.contentsOf(a)).isNotEqualTo(RecordMixSaves.contentsOf(a + song("Three")))
        // saved and read back by id and contents; other contents, another mix: nothing
        val saves = RecordMixSaves(MemoryPrefs())
        saves.remember("week", RecordMixSaves.contentsOf(a), 12L)
        assertThat(saves.playlistOf("week", RecordMixSaves.contentsOf(a))).isEqualTo(12L)
        assertThat(saves.playlistOf("week", RecordMixSaves.contentsOf(a.reversed()))).isNull()
        assertThat(saves.playlistOf("year:2026", RecordMixSaves.contentsOf(a))).isNull()
    }

    @Test fun `the contents follow the songs, not their item keys - a song matched to your row since is the same song`() {
        val stray = MixSong(title = "One", artist = "Band", album = null, key = "one|band", songKey = SongKey.songKey("One", "Band"))
        val matched = stray.copy(key = "isrc:GBAYE0000001") // the same song, found in the library since
        assertThat(RecordMixSaves.contentsOf(listOf(matched))).isEqualTo(RecordMixSaves.contentsOf(listOf(stray)))
    }
}
