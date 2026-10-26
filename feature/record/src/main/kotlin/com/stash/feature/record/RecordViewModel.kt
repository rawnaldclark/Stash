package com.stash.feature.record

import android.content.Context
import android.content.MutableContextWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.db.dao.RecordPlayDao
import com.stash.core.data.db.dao.WebLibraryExportDao
import com.stash.feature.record.archive.ImportedArchive
import com.stash.feature.record.archive.OwnArchive
import com.stash.feature.record.archive.StackedArchive
import com.stash.feature.record.art.RecordArt
import com.stash.feature.record.bridge.RecordEvent
import com.stash.feature.record.bridge.RecordHost
import com.stash.feature.record.bridge.RecordNote
import com.stash.feature.record.bridge.RecordPlayback
import com.stash.feature.record.bridge.RecordSaves
import com.stash.feature.record.bridge.text
import com.stash.feature.record.imports.RecordImports
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

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
    @ApplicationContext private val app: Context,
) : ViewModel() {
    private val _events = MutableSharedFlow<RecordEvent>(extraBufferCapacity = 8)

    /** Close, and lines for the listener. */
    val events: SharedFlow<RecordEvent> = _events.asSharedFlow()

    /** What a tap came to when it couldn't play ([RecordPlayback] outlives this screen, and says so with a toast after it). */
    val playNotes: SharedFlow<RecordNote> = playback.notes

    private val saves = RecordSaves(File(app.cacheDir, SAVES_DIR), files)

    /**
     * The record's archive: the imported plays (a Spotify export), then this phone's own, in the bridge's 20k chunks. The page
     * keeps the first row of an id; the two never share one (they are different players).
     */
    private val host = RecordHost(
        scope = viewModelScope,
        archive = StackedArchive(listOf(ImportedArchive(imported), OwnArchive(plays))),
        covers = { songs, retry, onBatch -> art.covers(songs, retry, onBatch) },
        player = playback,
        saves = saves,
        events = { e -> if (e == RecordEvent.Import) imports.openSheet() else _events.tryEmit(e) },
    )

    init {
        // An import written or removed while the record is open: the page presses itself again from the new archive
        viewModelScope.launch { imports.archiveChanged.collect { host.reload() } }
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

    private companion object {
        /** Where the record's files are put together before they are saved or shared (cacheDir). */
        const val SAVES_DIR = "record-saves"
    }
}
