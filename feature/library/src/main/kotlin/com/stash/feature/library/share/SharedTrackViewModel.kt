package com.stash.feature.library.share

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.share.ShareApiClient
import com.stash.core.data.share.ShareResult
import com.stash.core.data.social.LikeCoordinator
import com.stash.core.media.PlayerRepository
import com.stash.core.model.share.ShareLinks
import com.stash.core.model.share.SharedTrack
import com.stash.core.model.share.toTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface SharedTrackUiState {
    /** A short link's song is on its way from the share Worker. */
    data object Loading : SharedTrackUiState
    data class Loaded(val track: SharedTrack) : SharedTrackUiState
    data class Error(val message: String, val retryable: Boolean) : SharedTrackUiState
}

/** The card an incoming shared-track link opens (spec §6). */
@HiltViewModel
class SharedTrackViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
    private val likeCoordinator: LikeCoordinator,
    private val shareApi: ShareApiClient,
) : ViewModel() {
    /**
     * Parsed from the route's link, so the card survives process death. A long link carries the song itself;
     * a short one (`/t/{id}`) only its id, so the song is fetched.
     */
    private val parsed = ShareLinks.parse(savedStateHandle.get<String>("link"))
    private val _state = MutableStateFlow(
        when (parsed) {
            is ShareLinks.Parsed.Track -> SharedTrackUiState.Loaded(parsed.track)
            is ShareLinks.Parsed.TrackRef -> SharedTrackUiState.Loading
            else -> SharedTrackUiState.Error("This link has expired. Open it again.", retryable = false)
        },
    )
    val state: StateFlow<SharedTrackUiState> = _state
    private val _liked = MutableStateFlow(false)
    val liked: StateFlow<Boolean> = _liked
    private val _message = MutableStateFlow<String?>(null)
    /** A short error from the last action, shown on the card. */
    val message: StateFlow<String?> = _message

    init {
        load()
    }

    /** Fetches a short link's song; Retry calls it again. A long link has nothing to fetch. */
    fun load() {
        val id = (parsed as? ShareLinks.Parsed.TrackRef)?.id ?: return
        _state.value = SharedTrackUiState.Loading
        viewModelScope.launch {
            _state.value = try {
                // A link made moments ago in another region can 404 until KV catches up (up to ~60 s), so a
                // 404 gets one more look before the card says the link doesn't exist.
                val first = shareApi.getTrack(id)
                val r = if (first == ShareResult.NotFound) { delay(NOT_FOUND_RETRY_MS); shareApi.getTrack(id) } else first
                when (r) {
                    is ShareResult.Ok -> SharedTrackUiState.Loaded(r.value)
                    ShareResult.NotFound, ShareResult.Gone, is ShareResult.Rejected ->
                        SharedTrackUiState.Error("This song link doesn't exist.", retryable = false)
                    else -> SharedTrackUiState.Error(RETRY_MESSAGE, retryable = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "load ${ShareLinks.logId(id)} failed", e)
                SharedTrackUiState.Error(RETRY_MESSAGE, retryable = true)
            }
        }
    }

    fun play() = safely("play") { track ->
        val id = musicRepository.ensureTrackPersisted(track.toTrack())
        val t = musicRepository.observeTrackById(id).first() ?: return@safely
        _liked.value = t.stashLikedAt != null
        playerRepository.setQueue(listOf(t), 0)
    }

    fun like() = safely("add") { track ->
        val id = musicRepository.ensureTrackPersisted(track.toTrack())
        likeCoordinator.setLiked(id, true)
        _liked.value = musicRepository.observeTrackById(id).first()?.stashLikedAt != null
    }

    private var busy = false

    /**
     * Runs [block] on the song shown; does nothing while it's still loading. A failure (DB, IO) becomes a
     * message, never a crash. [busy] drops a double tap.
     */
    private fun safely(action: String, block: suspend (SharedTrack) -> Unit) {
        val track = (_state.value as? SharedTrackUiState.Loaded)?.track ?: return
        if (busy) return
        busy = true
        _message.value = null
        viewModelScope.launch {
            try {
                block(track)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$action failed", e)
                _message.value = "Couldn't $action this song. Try again."
            } finally {
                busy = false
            }
        }
    }

    private companion object {
        const val TAG = "SharedTrackVM"
        const val RETRY_MESSAGE = "Couldn't load this song. Check your connection and try again."
        const val NOT_FOUND_RETRY_MS = 2_000L
    }
}
