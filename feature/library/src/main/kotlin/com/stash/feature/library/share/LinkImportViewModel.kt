package com.stash.feature.library.share

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.link.LinkAdded
import com.stash.core.data.link.LinkImporter
import com.stash.core.data.link.LinkLoad
import com.stash.core.data.link.LinkPreview
import com.stash.core.data.link.MusicLink
import com.stash.core.data.link.MusicLinks
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
    data class Failed(val message: String, val retryable: Boolean) : LinkImportUiState
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

/** The screen a pasted or shared Spotify / YouTube link opens: what it is, then Add to library (and Download). */
@HiltViewModel
class LinkImportViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val importer: LinkImporter,
) : ViewModel() {
    private var link: MusicLink? = MusicLinks.find(savedStateHandle.get<String>("link"))
    private val _state = MutableStateFlow<LinkImportUiState>(LinkImportUiState.Loading)
    val state: StateFlow<LinkImportUiState> = _state

    init {
        load()
    }

    fun load() {
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
