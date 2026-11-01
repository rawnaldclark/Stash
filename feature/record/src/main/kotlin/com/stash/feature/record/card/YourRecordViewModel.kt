package com.stash.feature.record.card

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.prefs.HomeSection
import com.stash.core.data.prefs.HomeSectionsPreference
import com.stash.feature.record.art.ArtSong
import com.stash.feature.record.art.RecordArt
import com.stash.feature.record.imports.ImportInfo
import com.stash.feature.record.imports.ImportState
import com.stash.feature.record.imports.RecordImports
import com.stash.feature.record.mixes.RecordShelfPicks
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Home's Your record card ([YourRecordSection]): the record's own numbers ([RecordCardSource], read only when the history
 * changes) and the import's state ([RecordImports]) as [RecordCard]'s state, and the sleeve's and the row's covers, looked up
 * once per set of albums and artists and shown as each batch lands. "Press your record" hidden with its × stays hidden on this
 * phone until Settings brings it back (HomeCard.svelte's `dismissed`); Settings › Home layout hides the whole section, and so
 * does the record card's long press ([hideCard]), leaving the record's mixes on Home.
 */
@HiltViewModel
class YourRecordViewModel @Inject constructor(
    source: RecordCardSource,
    private val art: RecordArt,
    private val imports: RecordImports,
    taste: com.stash.feature.record.taste.RecordTaste,
    private val homeSections: HomeSectionsPreference,
    shelfPicks: RecordShelfPicks,
) : ViewModel() {
    init {
        // Home opened: the taste again, at most once a local day (ui/spotify/shared.ts `dailyRefresh`)
        taste.daily()
    }

    private val covers = MutableStateFlow(Covers())

    /** The covers found for the view whose albums and artists make [key]. */
    private data class Covers(val key: String = "", val albums: List<String?> = emptyList(), val artists: List<String?> = emptyList())

    private fun cardImport(info: ImportInfo, st: ImportState) =
        CardImport(info.record != null, info.asked, info.inviteHidden, st, info.unfinished != null)

    /** The last card worked out from the history: where a new subscription starts (Home back after a while: no gap, no flicker). */
    @Volatile private var lastBase: RecordCardState? = null

    /** The card before its covers: worked out off the main thread, and its covers asked for as soon as it is known. */
    private val base = combine(source.data, imports.info, imports.state) { data, info, st ->
        RecordCard.stateOf(data.summary, data.albums, cardImport(info, st))
    }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .onEach {
            lastBase = it
            if (it is RecordCardState.Record) askCovers(it.view)
        }

    /** What the import alone decides (on its way, a preview waiting, didn't finish): never waits for the history. */
    private val importOnly = combine(imports.info, imports.state) { info, st -> RecordCard.importOnly(cardImport(info, st)) }

    /**
     * Null until the history is read (Home shows nothing rather than a card that changes its mind), unless the import alone
     * decides the card: that is shown at once.
     */
    val state: StateFlow<RecordCardState?> = combine(
        base.map<RecordCardState, RecordCardState?> { it }.onStart { emit(lastBase) },
        importOnly,
        covers,
    ) { b, io, c ->
        val s = io ?: b ?: return@combine null
        if (s !is RecordCardState.Record) return@combine s
        val view = s.view
        val mine = c.key == coverKey(view)
        s.copy(
            albumArt = view.albums.mapIndexed { i, a -> a.cover ?: c.albums.getOrNull(i)?.takeIf { mine } },
            artistArt = view.artists.indices.map { i -> c.artists.getOrNull(i)?.takeIf { mine } },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private var coversJob: Job? = null

    fun hideInvite() = imports.hideInvite()

    /**
     * Whether Home shows mixes from the record under the card ([mixesStay]): the card's menu says they stay only when they do.
     * False until known, so the menu never promises mixes there may not be.
     */
    val mixesOnHome: StateFlow<Boolean> = combine(homeSections.hidden, shelfPicks.hasPicks, imports.info) { hidden, picks, info ->
        mixesStay(hidden, picks, imported = info.record != null)
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * The card's long press › Hide this card: Home's Your record section hidden, exactly as its switch in Settings › Home
     * layout does (which brings it back). Its mixes are their own section and stay, in the card's place; nothing of the record
     * itself is touched (that's Settings › Your record › Remove your record, never this).
     */
    fun hideCard() {
        viewModelScope.launch {
            try {
                homeSections.setHidden(HomeSection.YOUR_RECORD, hide = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The card stays; Settings › Home layout can still hide it
                Log.w(TAG, "hiding the card failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /** Get started, How it works, Open, Review: the import sheet. */
    fun openImport() = imports.openSheet()

    /** "Your import didn't finish" › Try again: the files kept, read again in the sheet (or the sheet, to bring them). */
    fun retry() {
        imports.openSheet()
        imports.again()
    }

    /** "Your import didn't finish" › ×: the card goes back to what it was, the files kept for it let go. */
    fun dismissUnfinished() = imports.dismissUnfinished()

    /** "Bring the zip": the files chosen from the card go straight in, in the sheet. */
    fun bring(uris: List<Uri>) {
        if (uris.isEmpty()) return
        imports.openSheet()
        imports.bring(uris)
    }

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

/**
 * Whether hiding the card leaves mixes from the record on Home: "From your record" isn't switched off, and its shelf has
 * mixes ([shelfHasPicks], as it last made them). Until the shelf has made them once (null), an import stands in: the card
 * also shows with no import, from this phone's own plays alone, and those make no mixes.
 */
internal fun mixesStay(hidden: Set<HomeSection>, shelfHasPicks: Boolean?, imported: Boolean): Boolean =
    HomeSection.RECORD_MIXES !in hidden && (shelfHasPicks ?: imported)
