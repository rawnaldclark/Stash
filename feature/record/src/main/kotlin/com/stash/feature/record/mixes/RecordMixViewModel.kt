package com.stash.feature.record.mixes

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.repository.MusicRepository
import com.stash.feature.record.art.RecordArt
import com.stash.feature.record.bridge.RecordMatch
import com.stash.feature.record.bridge.RecordNote
import com.stash.feature.record.bridge.RecordPlayback
import com.stash.feature.record.bridge.RecordSong
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Save as playlist (MixView.svelte `save`): a playlist of the mix as it is now, named for it. Its songs are this phone's rows
 * ([RecordMatch]: your own copy, else the same song found or a stream-only row), each once, with the covers found kept on rows
 * that had none. A CUSTOM playlist made as Library's own New playlist makes one ([MusicRepository.createPlaylistWithTracks]):
 * never kept offline, so nothing downloads.
 */
@Singleton
class RecordMixSaver internal constructor(
    private val rows: suspend (List<RecordSong>) -> List<Long?>,
    private val create: suspend (String, List<Long>) -> Long,
) {
    @Inject constructor(match: RecordMatch, music: MusicRepository) : this(
        rows = { songs -> match.rows(songs).map { it?.id } },
        create = music::createPlaylistWithTracks,
    )

    /** The new playlist's id. */
    suspend fun save(name: String, songs: List<RecordSong>): Long = create(name, rows(songs).filterNotNull().distinct())
}

/** A mix's songs as the record plays them: the words, the Spotify id, your own row when you have it, and the cover found. */
internal fun songsOf(items: List<MixSong>, covers: List<String?>): List<RecordSong> = items.mapIndexed { i, s ->
    RecordSong(title = s.title, artist = s.artist, album = s.album, sid = s.sid, trackId = s.lib?.trackId, cover = covers.getOrNull(i))
}

/**
 * One mix of Your record, opened (stash-player `src/lib/ui/spotify/MixView.svelte`, route `RecordMixRoute(id)`): made again from
 * the taste when the page opens (and when the taste changes under it: an import, a removal), the library read with it, so a
 * song liked here doesn't reshuffle the list. A mix the taste no longer makes says so ([State.Gone]). Then every row's cover, as
 * each album's is found. Play and Shuffle go through the record's player ([RecordPlayback]), with the covers found, so the
 * queue never shows blank squares; Save as playlist keeps it ([RecordMixSaver]).
 */
@HiltViewModel
class RecordMixViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val mixes: RecordMixes,
    private val art: RecordArt,
    private val playback: RecordPlayback,
    private val saver: RecordMixSaver,
) : ViewModel() {
    /** The mix's id ("era:1693526400000", "year:2018", "week"). */
    val id: String = savedState.get<String>(ID) ?: ""

    sealed interface State {
        /** Nothing shows yet, so nothing flashes. */
        data object Looking : State

        /** The taste doesn't make this mix any more: mixes change as the listening does. */
        data object Gone : State

        data class Found(
            val mix: HistoryMix,
            val heading: String,
            val line: String,
            val length: String,
            val covers: List<String?>,
        ) : State
    }

    private val _state = MutableStateFlow<State>(State.Looking)
    val state: StateFlow<State> = _state.asStateFlow()

    /** The playlist just saved: its id and name (the button then opens it). */
    data class Saved(val id: Long, val name: String)

    private val _saved = MutableStateFlow<Saved?>(null)
    val saved: StateFlow<Saved?> = _saved.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    /** A line for the listener after Save as playlist (the screen says it in its language). */
    sealed interface Line {
        data class Saved(val name: String) : Line

        data object SaveFailed : Line
    }

    private val _lines = MutableSharedFlow<Line>(extraBufferCapacity = 4)
    val lines: SharedFlow<Line> = _lines.asSharedFlow()

    /** The words, in the screen's language (by its tags): set by [RecordMixScreen]. */
    private val words = MutableStateFlow<Pair<String, MixWords>?>(null)

    fun useWords(tags: String, w: MixWords) {
        if (words.value?.first != tags) words.value = tags to w
    }

    /** What a tap came to when it couldn't play (none of the songs here, offline). */
    val playNotes: SharedFlow<RecordNote> = playback.notes

    init {
        viewModelScope.launch {
            combine(words.filterNotNull(), mixes.changes) { w, _ -> w.second }.collectLatest { load(it) }
        }
    }

    private suspend fun load(w: MixWords) {
        val now = try {
            mixes.now(w)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The page keeps what it shows: a database that can't be read isn't a mix gone
            Log.w(TAG, "the mix couldn't be made: ${e.javaClass.simpleName}")
            return
        }
        val m = now?.find(id)
        if (now == null || m == null) {
            _state.value = State.Gone
            return
        }
        // aboutLength reads every song of the taste: off the main thread
        val base = withContext(Dispatchers.Default) {
            State.Found(
                mix = m,
                heading = HistoryMixes.mixHeading(m, now.taste),
                line = HistoryMixes.mixLine(m, now.taste, w),
                length = HistoryMixes.aboutLength(m.items, now.taste, w),
                covers = m.items.map { it.lib?.art },
            )
        }
        _state.value = base
        try {
            val all = mixCovers(art, m.items, albums = Int.MAX_VALUE, retry = viewModelScope) { sofar -> show(m, sofar) }
            show(m, all)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "the mix's covers failed: ${e.javaClass.simpleName}")
        }
    }

    private fun show(m: HistoryMix, covers: List<String?>) {
        val s = _state.value as? State.Found ?: return
        if (s.mix.id == m.id && s.mix === m) _state.value = s.copy(covers = covers)
    }

    private fun found(): State.Found? = _state.value as? State.Found

    /** Plays the mix from its [index]th song. */
    fun play(index: Int = 0) {
        val f = found() ?: return
        playback.play(songsOf(f.mix.items, f.covers), index, f.mix.title, shuffle = false)
    }

    /** The mix in a random order (the app's Shuffle, as a playlist's: the queue shuffled, from its first song). */
    fun shuffle() {
        val f = found() ?: return
        playback.play(songsOf(f.mix.items, f.covers), 0, f.mix.title, shuffle = true)
    }

    /** Save as playlist: once at a time; then the button opens it. */
    fun save() {
        val f = found() ?: return
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            try {
                val pid = saver.save(f.mix.title, songsOf(f.mix.items, f.covers))
                _saved.value = Saved(pid, f.mix.title)
                _lines.tryEmit(Line.Saved(f.mix.title))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "the mix couldn't be saved: ${e.javaClass.simpleName}")
                _lines.tryEmit(Line.SaveFailed)
            } finally {
                _saving.value = false
            }
        }
    }

    companion object {
        /** The route's argument ([RecordMixRoute]'s `id`). */
        const val ID = "id"
        private const val TAG = "YourRecord"
    }
}
