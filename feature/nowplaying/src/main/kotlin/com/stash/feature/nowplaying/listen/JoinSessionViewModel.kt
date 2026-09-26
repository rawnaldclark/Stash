package com.stash.feature.nowplaying.listen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.listen.RoomApiClient
import com.stash.core.data.share.SharePreference
import com.stash.core.data.share.ShareResult
import com.stash.core.media.listen.ListenTogetherController
import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.model.share.SharedTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The Join screen for a `/l/{code}` link (spec §5–§6): preview first, so a full or ended room says so before connecting. */
@HiltViewModel
class JoinSessionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val api: RoomApiClient,
    private val controller: ListenTogetherController,
    private val sharePreference: SharePreference,
) : ViewModel() {
    sealed interface UiState {
        data object Loading : UiState
        data class Ready(val hostName: String?, val memberCount: Int, val track: SharedTrack?) : UiState
        data object Full : UiState
        data object Ended : UiState
        data object RateLimited : UiState
        data object Failed : UiState
    }

    /** ListenJoinRoute(code). Type-safe navigation stores it under the property name. */
    val code: String = savedStateHandle.get<String>("code").orEmpty()

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** The session this phone is already in, if any: joining this one leaves it. */
    val current: StateFlow<ListenTogetherState> = controller.state

    var name by mutableStateOf("")
        private set

    /** Set on the first Join tap, so a double tap can't send two joins (or navigate twice). */
    var joining by mutableStateOf(false)
        private set

    init {
        load()
        viewModelScope.launch {
            val saved = sharePreference.displayName().orEmpty()
            if (name.isEmpty()) name = saved // never overwrite what the user already typed
        }
    }

    fun onNameChange(value: String) { name = value.take(40) }

    fun retry() = load()

    private fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = when (val r = api.preview(code)) {
                is ShareResult.Ok -> if (r.value.full) UiState.Full else UiState.Ready(r.value.hostName, r.value.memberCount, r.value.track)
                ShareResult.NotFound, ShareResult.Gone -> UiState.Ended
                is ShareResult.Failed -> if (r.message == ShareResult.Failed.RATE_LIMITED) UiState.RateLimited else UiState.Failed
                else -> UiState.Failed
            }
        }
    }

    fun join(onJoined: () -> Unit) {
        if (joining) return
        joining = true
        viewModelScope.launch {
            sharePreference.setDisplayName(name)
            controller.send(ListenTogetherController.Command.Join(code))
            onJoined()
        }
    }
}
