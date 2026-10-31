package com.stash.feature.library.share

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.link.LinkAdded
import com.stash.core.data.link.LinkImporter
import com.stash.core.data.link.LinkLoad
import com.stash.core.data.link.LinkPreview
import com.stash.core.data.link.MusicLink
import com.stash.core.data.link.MusicLinks
import com.stash.core.data.playlistfile.PlaylistFileImport
import com.stash.core.data.playlistfile.PlaylistFileLoad
import com.stash.core.data.playlistfile.PlaylistFileProblem
import com.stash.feature.library.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface LinkImportUiState {
    data object Loading : LinkImportUiState

    /** [messageRes]: the message from this module's strings (a playlist file's), shown instead of [message] (a link's). */
    data class Failed(
        val message: String = "",
        val retryable: Boolean,
        @StringRes val messageRes: Int? = null,
    ) : LinkImportUiState

    data class Ready(
        val preview: LinkPreview,
        /** The playlist this link made before, when it is still here. */
        val existingId: Long?,
        val download: Boolean = true,
        /** Adding: the songs matched so far of all. */
        val progress: Pair<Int, Int>? = null,
        val added: LinkAdded? = null,
        val message: String? = null,
    ) : LinkImportUiState
}

/**
 * The screen a pasted or shared Spotify / YouTube link opens: what it is, then Add to library (and Download). A playlist file
 * picked from the Library's "+" opens it too: read instead of a link, then added the same way.
 */
@HiltViewModel
class LinkImportViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val importer: LinkImporter,
    private val files: PlaylistFileImport,
) : ViewModel() {
    private var link: MusicLink? = MusicLinks.find(savedStateHandle.get<String>("link"))

    /** "+" › Import a playlist file: the document picked (the file route's `file`), read in place of a link. */
    private val file: String? = savedStateHandle.get<String>("file")

    private val _state = MutableStateFlow<LinkImportUiState>(LinkImportUiState.Loading)
    val state: StateFlow<LinkImportUiState> = _state

    init {
        load()
    }

    fun load() {
        file?.let { return loadFile(it) }
        val link = link ?: run {
            _state.value = LinkImportUiState.Failed(LinkImporter.NOT_MUSIC, retryable = false)
            return
        }
        _state.value = LinkImportUiState.Loading
        viewModelScope.launch {
            _state.value = when (val r = importer.load(link)) {
                is LinkLoad.Ok -> LinkImportUiState.Ready(r.preview, importer.existing(r.preview), download = r.preview.soundCloudSongs?.any { !it.preview } ?: true)
                is LinkLoad.Failed -> LinkImportUiState.Failed(r.message, retryable = r.message == LinkImporter.CANT_OPEN)
            }
        }
    }

    private fun loadFile(uri: String) {
        _state.value = LinkImportUiState.Loading
        viewModelScope.launch {
            _state.value = when (val r = files.load(uri)) {
                is PlaylistFileLoad.Ok -> LinkImportUiState.Ready(r.preview, importer.existing(r.preview))
                // Opening may work a second time (a cloud file's app); a file with nothing to read won't change.
                is PlaylistFileLoad.Failed -> when (r.problem) {
                    PlaylistFileProblem.CANT_OPEN -> LinkImportUiState.Failed(retryable = true, messageRes = R.string.playlist_file_cant_open)
                    PlaylistFileProblem.NOTHING_TO_READ -> LinkImportUiState.Failed(retryable = false, messageRes = R.string.playlist_file_nothing)
                }
            }
        }
    }

    /** A song opened from a playlist: show that playlist instead. */
    fun openList() {
        val s = _state.value as? LinkImportUiState.Ready ?: return
        link = s.preview.fromList ?: return
        load()
    }

    fun setDownload(on: Boolean) = _state.update { (it as? LinkImportUiState.Ready)?.copy(download = on) ?: it }

    fun add() {
        val s = _state.value as? LinkImportUiState.Ready ?: return
        if (s.progress != null) return
        _state.value = s.copy(progress = 0 to s.preview.songs.size, message = null)
        viewModelScope.launch {
            try {
                // Finishes when the listener leaves: what was matched stays, but a half-made playlist would look broken.
                val added = withContext(NonCancellable) {
                    importer.add(s.preview, s.download) { done, total ->
                        _state.update { (it as? LinkImportUiState.Ready)?.copy(progress = done to total) ?: it }
                    }
                }
                _state.update { (it as? LinkImportUiState.Ready)?.copy(progress = null, added = added, existingId = added.playlistId) ?: it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "add failed: ${e.javaClass.simpleName}")
                _state.update { (it as? LinkImportUiState.Ready)?.copy(progress = null, message = "Couldn't add this. Try again.") ?: it }
            }
        }
    }

    private companion object {
        const val TAG = "LinkImportVM"
    }
}
