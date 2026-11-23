package com.stash.feature.nowplaying.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.share.SharePreference
import com.stash.core.media.PlayerRepository
import com.stash.core.media.listen.ListenTogetherController
import com.stash.core.media.listen.ListenTogetherController.Command
import com.stash.core.media.listen.ListenTogetherController.RequestAnswer
import com.stash.core.model.Track
import com.stash.core.model.share.SharedTrack
import com.stash.core.model.share.toSharedTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A session's requests (Sessions step 3). For a listener: the search inside the Request sheet and sending a request.
 * For the host and co-hosts: answering requests, Auto-accept, co-hosts and booting. The room itself comes from the
 * engine's state; this only sends what a tap means.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class SessionDjViewModel @Inject constructor(
    private val controller: ListenTogetherController,
    private val player: PlayerRepository,
    music: MusicRepository,
    private val sharePreference: SharePreference,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Songs in this phone's library that match what's typed; nothing until something is. */
    val results: StateFlow<List<Track>> = _query
        .map(String::trim)
        .debounce { if (it.isEmpty()) 0L else SEARCH_WAIT_MS }
        .flatMapLatest { q -> if (q.length < MIN_QUERY) flowOf(emptyList()) else music.search(q).map { it.take(MAX_RESULTS) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(text: String) { _query.value = text }

    /**
     * Asks the room for [track]. A listener's Add to queue is a request (the engine turns it into one, says how it
     * went, and holds it while the socket is down), so this is the same door every song menu in the app uses.
     */
    fun request(track: Track) {
        viewModelScope.launch { player.addToQueue(track) }
    }

    /** A co-host's Skip: the engine passes it to the host's phone, which moves the room on. */
    fun skip() = controller.send(Command.Skip)

    /** Takes back one of this listener's songs that the host already put in Up next. */
    fun takeBack(track: SharedTrack, queueIndex: Int) = controller.send(Command.Unqueue(track, queueIndex))

    /** Takes back one of this listener's requests that's still waiting for an answer. */
    fun cancelRequest(id: String) = controller.send(Command.CancelRequest(id))

    fun answer(id: String, answer: RequestAnswer) = controller.send(Command.AnswerRequest(id, answer))

    /** The host opens the decks, or closes them. */
    fun openDecks(open: Boolean) = controller.send(Command.OpenDecks(open))

    /** A listener's song for the decks: it sits them down (or joins the line) the first time, and replaces their ready song after. */
    fun deckPick(track: Track) {
        controller.send(Command.DeckPick(track.toSharedTrack()))
        _query.value = "" // the next pick starts from an empty search
    }

    fun leaveDecks() = controller.send(Command.LeaveDecks)

    /** The host takes someone off the decks or out of the line. */
    fun removeFromDecks(memberId: String) = controller.send(Command.RemoveFromDecks(memberId))

    /** "End after this song", or taking it back. */
    fun lastSong(on: Boolean) = controller.send(Command.LastSong(on))

    /** The booth's Auto-accept is the station's setting: it is remembered, so the next session starts the same way. */
    fun setAutoAccept(on: Boolean) {
        controller.send(Command.SetAutoAccept(on))
        viewModelScope.launch { sharePreference.setAutoAccept(on) }
    }

    fun setCohost(memberId: String, on: Boolean) = controller.send(Command.SetCohost(memberId, on))

    fun boot(memberId: String) = controller.send(Command.Boot(memberId))

    private companion object {
        const val SEARCH_WAIT_MS = 250L
        const val MIN_QUERY = 2
        const val MAX_RESULTS = 30
    }
}
