package com.stash.feature.record

import android.content.Context
import android.content.MutableContextWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.db.dao.RecordPlayDao
import com.stash.core.data.db.dao.WebLibraryExportDao
import com.stash.feature.record.archive.OwnArchive
import com.stash.feature.record.archive.RecordArchive
import com.stash.feature.record.archive.RecordSource
import com.stash.feature.record.archive.RecordSources
import com.stash.feature.record.archive.RecordSourcesStore
import com.stash.feature.record.archive.SourceCounts
import com.stash.feature.record.archive.SourcesOn
import com.stash.feature.record.art.RecordArt
import com.stash.feature.record.bridge.RecordEvent
import com.stash.feature.record.bridge.RecordHost
import com.stash.feature.record.bridge.RecordNote
import com.stash.feature.record.bridge.RecordPlayback
import com.stash.feature.record.bridge.RecordSaves
import com.stash.feature.record.bridge.RecordSourceSwitch
import com.stash.feature.record.card.RecordCardSource
import com.stash.feature.record.bridge.text
import com.stash.feature.record.imports.RecordImports
import com.stash.feature.record.lastfm.RecordLastfm
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** The switches over the record: a switch for each source with plays (two or more), which are on, and each one's listens. */
data class SourceSwitches(val sources: List<RecordSource>, val on: SourcesOn, val counts: SourceCounts) {
    /** [s] is the one source with plays still on: it stays on (record.ts `toggleSource` refuses it). */
    fun isLast(s: RecordSource): Boolean = on[s] && sources.count { on[it] } == 1
}

/**
 * The record's screen: its [RecordHost] (the bridge's app side) and its WebView, both kept for as long as the screen is open.
 *
 * The WebView outlives configuration changes (rotation, dark mode, font size): the Activity is made again, this ViewModel
 * isn't, and neither is the page, so the record isn't pressed again. It is made on a [MutableContextWrapper] pointed at the
 * Activity showing it ([webView]); when the screen lets go of it ([letGo]: a configuration change, or leaving) it comes out of
 * its parent and the wrapper points at the application, so no Activity is held while it waits. [onCleared] destroys it.
 */
@HiltViewModel
class RecordViewModel @Inject constructor(
    plays: WebLibraryExportDao,
    imported: RecordPlayDao,
    private val imports: RecordImports,
    art: RecordArt,
    playback: RecordPlayback,
    private val files: RecordFiles,
    private val sourcesStore: RecordSourcesStore,
    lastfm: RecordLastfm,
    card: RecordCardSource,
    @ApplicationContext private val app: Context,
    taste: com.stash.feature.record.taste.RecordTaste,
    private val savedState: SavedStateHandle,
    private val tabs: RecordTabStore,
) : ViewModel() {
    private val _tab = MutableStateFlow(
        RecordTab.of(savedState.get<String>(SHOWN)) ?: RecordTab.of(savedState.get<String>(TAB_ARG)) ?: tabs.last,
    )

    /**
     * The tab shown: the one the way in asked for (Home's Show all: the Mixes), else the one picked last on this phone (the web's
     * `stash.spotify.tab`); kept across a recreation.
     */
    val tab: StateFlow<RecordTab> = _tab.asStateFlow()

    /** A tab picked: shown, and kept for next time. */
    fun pick(t: RecordTab) {
        _tab.value = t
        savedState[SHOWN] = t.key
        tabs.last = t
    }

    private val _events = MutableSharedFlow<RecordEvent>(extraBufferCapacity = 8)

    /** Close, and lines for the listener. */
    val events: SharedFlow<RecordEvent> = _events.asSharedFlow()

    /** What a tap came to when it couldn't play ([RecordPlayback] outlives this screen, and says so with a toast after it). */
    val playNotes: SharedFlow<RecordNote> = playback.notes

    private val saves = RecordSaves(File(app.cacheDir, SAVES_DIR), files)

    /**
     * What each source brings: the Home card's last count at first (the same rows, so the switches show as the record opens),
     * then the count of the archive as it goes out, then the page's own.
     */
    private val counts = MutableStateFlow(card.counts)

    /**
     * The record's archive ([RecordArchive]: the Spotify export, this phone's own plays, then the Last.fm scrobbles they don't
     * cover), in the bridge's 20k chunks, and the sources the page shows (the choice kept on this phone).
     */
    private val host = RecordHost(
        scope = viewModelScope,
        archive = RecordArchive(imported, OwnArchive(plays)),
        covers = { songs, retry, onBatch -> art.covers(songs, retry, onBatch) },
        player = playback,
        saves = saves,
        events = { e -> if (e == RecordEvent.Import) imports.openSheet() else _events.tryEmit(e) },
        sources = object : RecordSourceSwitch {
            override fun chosen(): SourcesOn = sourcesStore.on.value

            override fun counted(counts: SourceCounts) {
                this@RecordViewModel.counts.value = counts
            }
        },
    )

    /**
     * The switches over the record (stash-player `src/routes/spotify/+page.svelte`): one per source with plays, shown only with
     * two or more; null otherwise, and until the archive has gone out.
     */
    val switches: StateFlow<SourceSwitches?> = combine(counts, sourcesStore.on) { c, on ->
        val here = RecordSources.present(c)
        if (c == null || here.size < 2) null else SourceSwitches(here, RecordSources.shown(on, c), c)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        // An import written or removed while the record is open, or scrobbles a top-up brought: the page presses itself again
        viewModelScope.launch { imports.archiveChanged.collect { host.reload() } }
        // Opening Your record tops Last.fm up, quietly (at most hourly, once the whole history is in)
        lastfm.topUp()
        // ...and works the taste out again, at most once a local day (ui/spotify/shared.ts `dailyRefresh`)
        taste.daily()
    }

    /**
     * [s]'s switch flipped (record.ts `toggleSource` on what the record shows now): kept on this phone, and the record shows it.
     * The one source with plays left on stays on.
     */
    fun flip(s: RecordSource) {
        val c = counts.value ?: return
        val next = RecordSources.toggle(RecordSources.shown(sourcesStore.on.value, c), s, c) ?: return
        sourcesStore.set(next)
        host.sourcesChanged()
    }

    /** A poster or a video ready for the share sheet, for the screen to open while it is started. */
    val shareReady: StateFlow<ShareReady?> = files.ready

    /** The screen opened [share]'s sheet. */
    fun shareShown(share: ShareReady) = files.shown(share)

    private val _gone = MutableStateFlow(false)

    /** The page's renderer died: the screen offers to open it again ([reopen]). */
    val gone: StateFlow<Boolean> = _gone.asStateFlow()

    private var web: RecordWebView? = null
    private var wrapper: MutableContextWrapper? = null

    /** The record's WebView for the Activity [context] belongs to: the one already made (moved over), else a new one. */
    internal fun webView(context: Context): RecordWebView {
        web?.let { w ->
            wrapper?.baseContext = context
            (w.parent as? ViewGroup)?.removeView(w)
            return w
        }
        // Built on the application, then shown in the Activity: Chromium keeps what it sees at construction for the WebView's
        // life (its autofill keeps the context's AutofillManager), and the WebView outlives an Activity across a rotation or a
        // theme change. Built on the first Activity, it kept that Activity after every recreation (seen on the rig: one
        // Activity too many after night mode was toggled twice). A select's popup and the keyboard use the view's context
        // when they open, so they get the Activity of the moment.
        val holder = MutableContextWrapper(app)
        val w = RecordWebView(
            holder,
            onMessage = host::onMessage,
            onResponsive = { host.unresponsive = !it },
            onGone = ::rendererGone,
        )
        holder.baseContext = context
        web = w
        wrapper = holder
        return w
    }

    /** The screen let go of [view]: out of its parent, and holding the application rather than the Activity, until shown again. */
    fun letGo(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
        if (view === web) wrapper?.baseContext = app
    }

    fun pause() {
        web?.onPause()
    }

    fun resume() {
        web?.onResume()
    }

    fun back() = host.back()

    fun setTheme(light: Boolean) = host.setTheme(light)

    /** After the renderer died: a new page. */
    fun reopen() {
        _gone.value = false
    }

    private fun rendererGone() {
        host.detach()
        web?.let { w ->
            (w.parent as? ViewGroup)?.removeView(w)
            w.release()
        }
        web = null
        wrapper = null
        _gone.value = true
    }

    override fun onCleared() {
        host.detach()
        // viewModelScope is already cancelled here, so nothing of the host's runs any more: the files go now
        saves.clearNow()
        if (files.dropWaiting()) Toast.makeText(app, RecordNote.ShareDropped.text(app.resources), Toast.LENGTH_SHORT).show()
        web?.let { w ->
            (w.parent as? ViewGroup)?.removeView(w)
            w.release()
        }
        web = null
        wrapper = null
    }

    companion object {
        /** Where the record's files are put together before they are saved or shared (cacheDir). */
        private const val SAVES_DIR = "record-saves"

        /** The route's argument (YourRecordRoute's `tab`): the tab to open on ([RecordTab.key]), or none. */
        const val TAB_ARG = "tab"

        /** The tab shown, kept across a recreation. */
        private const val SHOWN = "record_tab_shown"
    }
}
