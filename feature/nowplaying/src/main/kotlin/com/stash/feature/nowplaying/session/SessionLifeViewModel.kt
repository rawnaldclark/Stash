package com.stash.feature.nowplaying.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.media.listen.ListenTogetherController
import com.stash.core.media.listen.ListenTogetherController.Command
import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.media.listen.SessionEvent
import com.stash.core.model.listen.RoomProtocol
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The room's life (Sessions step 4): the feed under the crowd, built from what happens in the session and kept only
 * for it; saying a phrase; telling the room about a save; and "Hi all" lit for a newcomer's first seconds.
 */
@HiltViewModel
class SessionLifeViewModel @Inject constructor(
    private val controller: ListenTogetherController,
) : ViewModel() {

    private val _feed = MutableStateFlow(Feed())
    internal val feed: StateFlow<Feed> = _feed.asStateFlow()

    private val _greet = MutableStateFlow(false)

    /** True for a listener's first [GREET_MS] in a session: "Hi all" is lit in the tray. */
    val greet: StateFlow<Boolean> = _greet.asStateFlow()

    /** Test seam for the clock. */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    /** The song (by the room's key) already announced as a favourite, so its line is said once. */
    private var favouriteSaid = -1
    private var lastSongSaid = false

    init {
        // A new session starts with an empty feed, and greets a listener.
        viewModelScope.launch {
            controller.state
                .map { (it as? ListenTogetherState.InRoom)?.takeIf { r -> r.session != null }?.let { r -> r.code to r.isHost } }
                .distinctUntilChanged()
                .collectLatest { entered ->
                    _feed.value = Feed()
                    favouriteSaid = -1
                    lastSongSaid = false
                    _greet.value = entered != null && !entered.second
                    if (_greet.value) {
                        delay(GREET_MS)
                        _greet.value = false
                    }
                }
        }
        viewModelScope.launch {
            controller.said.collect { said ->
                val room = session() ?: return@collect
                if (said.phrase !in RoomProtocol.PHRASES) return@collect // a newer app's: nothing to show
                add(FeedLine.Said(nameOf(room, said.from), said.phrase))
            }
        }
        viewModelScope.launch {
            controller.deckOuts.collect { out ->
                val room = session() ?: return@collect
                if (out.memberId != room.myId) add(FeedLine.OffDecks(nameOf(room, out.memberId)))
            }
        }
        viewModelScope.launch {
            controller.events.collect { event ->
                val room = session() ?: return@collect
                if (event.kind == SessionEvent.Kind.JOINED && event.memberId != room.myId) {
                    // A regular is greeted as one: the room marks them in its members.
                    val regular = room.members.firstOrNull { it.id == event.memberId }?.regular == true
                    add(if (regular) FeedLine.Back(event.name) else FeedLine.Joined(event.name))
                }
            }
        }
        viewModelScope.launch {
            controller.state.collect { state ->
                val room = (state as? ListenTogetherState.InRoom)?.takeIf { it.session != null } ?: return@collect
                // "Last song", once each time the host calls it.
                val last = room.session?.lastSong == true
                if (last && !lastSongSaid) add(FeedLine.LastSong)
                lastSongSaid = last
                val stats = room.songStats ?: return@collect
                val title = room.track?.title ?: return@collect
                if (stats.favourite && stats.trackKey != favouriteSaid) {
                    favouriteSaid = stats.trackKey
                    add(FeedLine.Favourite(title))
                }
            }
        }
    }

    private fun session(): ListenTogetherState.InRoom? =
        (controller.state.value as? ListenTogetherState.InRoom)?.takeIf { it.session != null }

    private fun nameOf(room: ListenTogetherState.InRoom, id: String): String? =
        (room.members.firstOrNull { it.id == id }?.name ?: room.names[id])?.takeIf(String::isNotBlank)

    private fun add(line: FeedLine) = _feed.update { it.add(line, clock()) }

    /** Says a phrase. The first one a newcomer says puts "Hi all"'s light out. */
    fun say(phrase: String) {
        _greet.value = false
        controller.send(Command.Say(phrase))
    }

    /** The heart was just filled for the session's song: the room counts it. */
    fun saved() = controller.send(Command.SavedSong)

    private companion object {
        const val GREET_MS = 10_000L
    }
}
