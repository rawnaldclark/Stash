package com.stash.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.weblibrary.ImportSelection
import com.stash.core.data.weblibrary.WebLibraryContent
import com.stash.core.data.weblibrary.WebLibraryImportResult
import com.stash.core.data.weblibrary.WebLibraryImporter
import com.stash.core.data.weblibrary.WebLibraryReader
import com.stash.feature.settings.weblink.LibraryPick
import com.stash.feature.settings.weblink.PickerPlaylist
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where "Import from Stash on the web" is. */
sealed interface WebLibraryImportState {
    data object Idle : WebLibraryImportState
    data object Reading : WebLibraryImportState

    /** The file is read: the picker shows what it holds. */
    data class Choosing(val content: WebLibraryContent, val pick: LibraryPick) : WebLibraryImportState
    data object Importing : WebLibraryImportState
    data class Done(val result: WebLibraryImportResult) : WebLibraryImportState
    data class Failed(val message: String) : WebLibraryImportState
}

/**
 * Settings › Library & Storage › "Import from Stash on the web" (link-sync spec §2.3, §9): a library file from the web player
 * (or another phone) is read, the picker shows what it holds, and what's ticked is merged in: adds, never removes.
 */
@HiltViewModel
class WebLibraryImportViewModel @Inject constructor(
    private val importer: WebLibraryImporter,
) : ViewModel() {

    private val _state = MutableStateFlow<WebLibraryImportState>(WebLibraryImportState.Idle)
    val state: StateFlow<WebLibraryImportState> = _state.asStateFlow()

    fun read(uri: Uri) {
        if (_state.value !is WebLibraryImportState.Idle) return
        _state.value = WebLibraryImportState.Reading
        viewModelScope.launch {
            _state.value = importer.read(uri).fold(
                onSuccess = { WebLibraryImportState.Choosing(it, pickOf(it)) },
                onFailure = { WebLibraryImportState.Failed(it.message ?: WebLibraryReader.NOT_A_BACKUP) },
            )
        }
    }

    fun updatePick(pick: LibraryPick) {
        val s = _state.value as? WebLibraryImportState.Choosing ?: return
        _state.value = s.copy(pick = pick)
    }

    fun import() {
        val s = _state.value as? WebLibraryImportState.Choosing ?: return
        _state.value = WebLibraryImportState.Importing
        viewModelScope.launch {
            _state.value = try {
                WebLibraryImportState.Done(importer.import(s.content, selectionOf(s.pick), WebLibraryImporter.ORIGIN_FILE))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                WebLibraryImportState.Failed("Couldn't import. Try again.")
            }
        }
    }

    fun dismiss() {
        val s = _state.value
        if (s !is WebLibraryImportState.Reading && s !is WebLibraryImportState.Importing) _state.value = WebLibraryImportState.Idle
    }

    companion object {
        /** Everything the file holds, ticked. */
        fun pickOf(c: WebLibraryContent) = LibraryPick(
            likes = c.likes.size,
            plays = c.history.size,
            playlists = c.playlists.map { PickerPlaylist(it.id, it.name, it.items.size, it.follow != null) },
        )

        fun selectionOf(p: LibraryPick) = ImportSelection(likes = p.takesLikes, plays = p.takesPlays, playlistIds = p.ticked)

        /** "This file has 1,204 likes, 5,000 plays and 14 playlists." */
        fun introOf(c: WebLibraryContent, res: android.content.res.Resources, what: String = res.getString(R.string.web_library_import_intro_this_file_has)): String {
            val parts = buildList {
                add(res.getQuantityString(R.plurals.web_library_export_likes, c.likes.size, c.likes.size))
                add(res.getQuantityString(R.plurals.web_library_export_plays, c.history.size, c.history.size))
                add(res.getQuantityString(R.plurals.web_library_export_playlists, c.playlists.size, c.playlists.size))
            }
            return res.getString(R.string.web_library_import_intro_body, what, parts[0], parts[1], parts[2])
        }

        fun introOf(c: WebLibraryContent, what: String, res: android.content.res.Resources): String = introOf(c, res, what)

        /** "Added 40 likes, 3 playlists and 120 plays." (what was new; what was already here is not counted again) */
        fun resultText(r: WebLibraryImportResult, res: android.content.res.Resources): String {
            val added = buildList {
                if (r.likesAdded > 0) add(res.getQuantityString(R.plurals.web_library_export_likes, r.likesAdded, r.likesAdded))
                if (r.playlistsAdded > 0) add(res.getQuantityString(R.plurals.web_library_export_playlists, r.playlistsAdded, r.playlistsAdded))
                if (r.playlistsUpdated > 0) add(res.getQuantityString(R.plurals.web_library_import_playlists_updated, r.playlistsUpdated, r.playlistsUpdated))
                if (r.playsAdded > 0) add(res.getQuantityString(R.plurals.web_library_export_plays, r.playsAdded, r.playsAdded))
            }
            if (added.isEmpty()) return res.getString(R.string.web_library_import_result_nothing_new)
            val list = if (added.size == 1) added[0] else added.dropLast(1).joinToString(", ") + " and " + added.last()
            return res.getString(R.string.web_library_import_result_added, list)
        }
    }
}
