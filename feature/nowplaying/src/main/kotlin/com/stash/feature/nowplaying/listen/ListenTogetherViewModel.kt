package com.stash.feature.nowplaying.listen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.share.SharePreference
import com.stash.core.media.PlayerRepository
import com.stash.core.media.listen.ListenTogetherController
import com.stash.core.media.listen.ListenTogetherController.Command
import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.media.listen.SessionEvent
import com.stash.core.model.listen.ServerMessage
import com.stash.core.model.share.SharedTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Now Playing's Listen Together controls (spec §5). All the work happens in the session; this only forwards. */
@HiltViewModel
class ListenTogetherViewModel @Inject constructor(
    private val controller: ListenTogetherController,
    private val sharePreference: SharePreference,
    private val playerRepository: PlayerRepository,
    streamingPreference: StreamingPreference,
) : ViewModel() {
    val state: StateFlow<ListenTogetherState> = controller.state
    val reactions: SharedFlow<ServerMessage.Reaction> = controller.reactions
    /** "Maya joined", "Rawn skipped": named room events for the notice on Now Playing. */
    val events: SharedFlow<SessionEvent> = controller.events

    /**
     * Is this phone in Offline mode right now? The app's one mode switch, read live, so the host's hint under
     * "Let guests add songs" follows a change while the sheet is open. `false` only until the first read: no hint
     * is better than a wrong one for that moment.
     */
    val offlineMode: StateFlow<Boolean> = streamingPreference.enabled
        .map { streamingOn -> !streamingOn }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** The "Show my name as" field. Compose state, so the TextField reads it synchronously. */
    var name by mutableStateOf("")
        private set

    init {
        viewModelScope.launch {
            val saved = sharePreference.displayName().orEmpty()
            if (name.isEmpty()) name = saved // never overwrite what the user already typed
        }
    }

    fun onNameChange(value: String) { name = value.take(40) }

    fun start() {
        viewModelScope.launch {
            sharePreference.setDisplayName(name)
            // A cold start only shows the last session; load it so the room starts from that song, not from nothing.
            playerRepository.loadRestoredQueue()
            controller.send(Command.Host)
        }
    }

    fun leave() = controller.send(Command.Leave)
    fun end() = controller.send(Command.End)
    fun rejoin() = controller.rejoin()
    fun react(emoji: String) = controller.send(Command.React(emoji))
    fun makeHost(memberId: String) = controller.send(Command.MakeHost(memberId))
    fun answerSuggestion(id: String, add: Boolean) = controller.send(Command.Suggestion(id, add))
    /** [qv]: the room version of the queue on screen when the host edited it. */
    fun editQueue(queue: List<SharedTrack>, qv: Int? = null) = controller.send(Command.SetQueue(queue, qv))

    /** A guest's own song out of Up next. */
    fun unqueue(track: SharedTrack, at: Int) = controller.send(Command.Unqueue(track, at))

    /** The host's "Let guests add songs": the room hears it at once, and the next session starts from it. */
    fun setGuestsAdd(on: Boolean) {
        controller.send(Command.SetGuestsAdd(on))
        viewModelScope.launch { sharePreference.setGuestsAdd(on) }
    }
}
