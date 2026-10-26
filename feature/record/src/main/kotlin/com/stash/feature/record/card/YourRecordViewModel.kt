package com.stash.feature.record.card

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.feature.record.art.ArtSong
import com.stash.feature.record.art.RecordArt
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Press your record" hidden with its × on this phone (HomeCard.svelte's `dismissed`). Only the invitation (offered once the
 * import exists: [RecordCard.INVITE_ENABLED]); once there is a record, the card shows whatever this says. Settings › Home
 * layout hides the whole section.
 */
@Singleton
class RecordInvitePreference @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _hidden = MutableStateFlow(prefs.getBoolean(KEY_HIDDEN, false))
    val hidden: StateFlow<Boolean> = _hidden.asStateFlow()

    fun hide() {
        prefs.edit().putBoolean(KEY_HIDDEN, true).apply()
        _hidden.value = true
    }

    private companion object {
        const val FILE = "your_record"
        const val KEY_HIDDEN = "invite_hidden"
    }
}

/**
 * Home's Your record card ([YourRecordSection]): the record's own numbers ([RecordCardSource], read only when the history
 * changes) as [RecordCard]'s state, and the sleeve's and the row's covers, looked up once per set of albums and artists and
 * shown as each batch lands.
 */
@HiltViewModel
class YourRecordViewModel @Inject constructor(
    source: RecordCardSource,
    private val art: RecordArt,
    private val invite: RecordInvitePreference,
) : ViewModel() {
    private val covers = MutableStateFlow(Covers())

    /** The covers found for the view whose albums and artists make [key]. */
    private data class Covers(val key: String = "", val albums: List<String?> = emptyList(), val artists: List<String?> = emptyList())

    /** The card before its covers: worked out off the main thread, and its covers asked for as soon as it is known. */
    private val base = combine(source.data, invite.hidden) { data, hidden -> RecordCard.stateOf(data.summary, data.albums, hidden) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .onEach { if (it is RecordCardState.Record) askCovers(it.view) }

    /** Null until the history is read: Home shows nothing rather than a card that changes its mind. */
    val state: StateFlow<RecordCardState?> = combine(base, covers) { s, c ->
        if (s !is RecordCardState.Record) return@combine s
        val view = s.view
        val mine = c.key == coverKey(view)
        s.copy(
            albumArt = view.albums.mapIndexed { i, a -> a.cover ?: c.albums.getOrNull(i)?.takeIf { mine } },
            artistArt = view.artists.indices.map { i -> c.artists.getOrNull(i)?.takeIf { mine } },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private var coversJob: Job? = null

    fun hideInvite() = invite.hide()

    /**
     * The sleeve's albums that have no cover of their own and the row's artists, in one ask (RecordCard.svelte), once per set:
     * the same albums and artists read again ask nothing.
     */
    private fun askCovers(view: CardView) {
        val key = coverKey(view)
        if (key == covers.value.key) return
        coversJob?.cancel()
        covers.value = Covers(key, view.albums.map { null }, view.artists.map { null })
        val missing = view.albums.indices.filter { view.albums[it].cover == null }
        val asks = missing.map { i -> view.albums[i].let { ArtSong(title = it.title, artist = it.artist, album = it.album) } } +
            view.artists.map { ArtSong(title = it, artist = it, isArtist = true) }
        if (asks.isEmpty()) return
        val put: (List<String?>) -> Unit = { urls ->
            if (covers.value.key == key) {
                covers.value = Covers(
                    key,
                    albums = view.albums.indices.map { i -> missing.indexOf(i).takeIf { it >= 0 }?.let { urls.getOrNull(it) } },
                    artists = view.artists.indices.map { j -> urls.getOrNull(missing.size + j) },
                )
            }
        }
        coversJob = viewModelScope.launch {
            try {
                put(art.covers(asks, viewModelScope, put))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The placeholders stay
                Log.w(TAG, "the card's covers failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun coverKey(view: CardView): String =
        (view.albums.map { "${it.artist}\u0000${it.album}" } + "\u0001" + view.artists).joinToString("\u0002")

    private companion object {
        const val TAG = "YourRecord"
    }
}
