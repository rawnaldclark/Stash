package com.stash.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.weblibrary.ExportCatalog
import com.stash.core.data.weblibrary.ExportSelection
import com.stash.core.data.weblibrary.WebLibraryExportResult
import com.stash.core.data.weblibrary.WebLibraryExporter
import com.stash.core.data.weblibrary.WebLibraryPickerPrefs
import com.stash.core.data.weblink.WebLinkConfig
import com.stash.core.data.weblink.inbox.SendOutcome
import com.stash.core.data.weblink.inbox.SendTarget
import com.stash.core.data.weblink.inbox.WebLinkInbox
import com.stash.feature.settings.weblink.LibraryPick
import com.stash.feature.settings.weblink.PickerPlaylist
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where "Export for Stash on the web" (or a send) is. */
sealed interface WebLibraryExportState {
    data object Idle : WebLibraryExportState
    data object Working : WebLibraryExportState
    data class Done(val result: WebLibraryExportResult) : WebLibraryExportState

    /** Sent to [to] (a linked browser's name): it asks there before adding anything. */
    data class Sent(val to: String, val result: WebLibraryExportResult) : WebLibraryExportState
    data object Failed : WebLibraryExportState
}

/** The picker (spec §2.3) while it's open: the choice, where it can be sent, and a send in progress or why it failed. */
data class ExportPickerUi(
    val pick: LibraryPick,
    val targets: List<SendTarget> = emptyList(),
    val sending: Boolean = false,
    val message: String? = null,
)

/**
 * Settings › Library & Storage › "Export for Stash on the web", and Link Stash on the web › "Send my library to a browser"
 * (link-sync spec §2.3): the picker ("What to include": likes, plays, each playlist; the last choice remembered, everything
 * the first time), then the web player's library file written to a document the user created with the system file picker
 * ([WebLibraryExporter]), or the same document sent to a linked browser ([WebLinkInbox]). Its own small ViewModel so the big
 * [SettingsViewModel] stays as it is.
 */
@HiltViewModel
class WebLibraryExportViewModel @Inject constructor(
    private val exporter: WebLibraryExporter,
    private val prefs: WebLibraryPickerPrefs,
    private val inbox: WebLinkInbox,
    private val config: WebLinkConfig,
) : ViewModel() {

    private val _state = MutableStateFlow<WebLibraryExportState>(WebLibraryExportState.Idle)
    val state: StateFlow<WebLibraryExportState> = _state.asStateFlow()

    private val _picker = MutableStateFlow<ExportPickerUi?>(null)
    val picker: StateFlow<ExportPickerUi?> = _picker.asStateFlow()

    /** The choice made on the picker, kept while the system file picker is open (the sheet is closed by then). */
    private var chosen: ExportSelection = ExportSelection.ALL

    /** Opens the picker with what the library holds now and the last choice. */
    fun openPicker() {
        viewModelScope.launch {
            val catalog = runCatching { exporter.catalog() }.getOrNull() ?: run {
                _state.value = WebLibraryExportState.Failed
                return@launch
            }
            val targets = if (config.enabled) inbox.targets() else emptyList()
            _picker.value = ExportPickerUi(pickOf(catalog, prefs.load()), targets)
        }
    }

    fun updatePick(pick: LibraryPick) {
        _picker.value = _picker.value?.copy(pick = pick, message = null)
    }

    fun closePicker() {
        if (_picker.value?.sending == true) return
        _picker.value = null
    }

    /** "Save as file": remembers the choice and closes the sheet; the screen then opens the system file picker. */
    fun chooseFile() {
        val p = _picker.value ?: return
        chosen = remember(p.pick)
        _picker.value = null
    }

    fun export(uri: Uri) {
        if (_state.value is WebLibraryExportState.Working) return
        _state.value = WebLibraryExportState.Working
        val selection = chosen
        viewModelScope.launch {
            _state.value = exporter.export(uri, selection).fold(
                onSuccess = { WebLibraryExportState.Done(it) },
                onFailure = { WebLibraryExportState.Failed },
            )
        }
    }

    /** "Send to Chrome on Windows": the same document, to that browser's inbox. */
    fun send(target: SendTarget) {
        val p = _picker.value ?: return
        if (p.sending) return
        val selection = remember(p.pick)
        _picker.value = p.copy(sending = true, message = null)
        viewModelScope.launch {
            val failure = try {
                val file = exporter.collect(System.currentTimeMillis(), exporter.generator(), selection)
                when (val out = inbox.send(target.id, exporter.text(file))) {
                    SendOutcome.Sent -> {
                        _picker.value = null
                        _state.value = WebLibraryExportState.Sent(target.name, WebLibraryExportResult(file.likes.size, file.playlists.size, file.history.size))
                        null
                    }
                    is SendOutcome.Failed -> out.message
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                "Couldn't send. Try again."
            }
            if (failure != null) _picker.value = _picker.value?.copy(sending = false, message = failure)
        }
    }

    /** Closes the result dialog. While the file is being written there is nothing to close. */
    fun dismiss() {
        if (_state.value !is WebLibraryExportState.Working) _state.value = WebLibraryExportState.Idle
    }

    /** Saves [pick] as the last choice and returns it as the exporter's selection. */
    private fun remember(pick: LibraryPick): ExportSelection {
        val all = pick.playlists.map { it.key.toLong() }
        val ticked = pick.ticked.mapNotNull { it.toLongOrNull() }.toSet()
        prefs.save(WebLibraryPickerPrefs.Remembered(likes = pick.likesOn, plays = pick.playsOn, unticked = all.toSet() - ticked))
        return ExportSelection(
            likes = pick.takesLikes,
            plays = pick.takesPlays,
            // Everything ticked: no list, so the file is exactly today's export.
            playlistIds = if (all.all { it in ticked }) null else ticked,
        )
    }

    companion object {
        fun pickOf(catalog: ExportCatalog, r: WebLibraryPickerPrefs.Remembered) = LibraryPick(
            likes = catalog.likes,
            plays = catalog.plays,
            playlists = catalog.playlists.map { PickerPlaylist(it.id.toString(), it.name, it.songs, it.sharedMix) },
            likesOn = r.likes,
            playsOn = r.plays,
            ticked = catalog.playlists.filter { it.id !in r.unticked }.mapTo(HashSet()) { it.id.toString() },
        )
    }
}
