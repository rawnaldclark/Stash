package com.stash.feature.nowplaying.listen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.share.SharePreference
import com.stash.core.media.listen.ListenTogetherController
import com.stash.core.media.listen.ListenTogetherController.Command
import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.model.listen.ServerMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Now Playing's Listen Together controls (spec §5). All the work happens in the session; this only forwards. */
@HiltViewModel
class ListenTogetherViewModel @Inject constructor(
    private val controller: ListenTogetherController,
    private val sharePreference: SharePreference,
) : ViewModel() {
    val state: StateFlow<ListenTogetherState> = controller.state
    val reactions: SharedFlow<ServerMessage.Reaction> = controller.reactions

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
            controller.send(Command.Host)
        }
    }

    fun leave() = controller.send(Command.Leave)
    fun end() = controller.send(Command.End)
    fun rejoin() = controller.rejoin()
    fun react(emoji: String) = controller.send(Command.React(emoji))
    fun makeHost(memberId: String) = controller.send(Command.MakeHost(memberId))
    fun answerSuggestion(id: String, add: Boolean) = controller.send(Command.Suggestion(id, add))
}
