package com.stash.core.media.listen

import android.content.Context
import android.content.Intent
import com.stash.core.media.service.StashPlaybackService
import com.stash.core.model.listen.RoomMember
import com.stash.core.model.listen.RoomSuggestion
import com.stash.core.model.listen.ServerMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ListenTogetherState {
    data object Idle : ListenTogetherState
    data class Connecting(val hosting: Boolean) : ListenTogetherState
    data class InRoom(
        val code: String,
        /** The invite link, `https://…/l/{code}`. */
        val url: String,
        val myId: String,
        val isHost: Boolean,
        val hostId: String?,
        val members: List<RoomMember>,
        val suggestions: List<RoomSuggestion>,
        /** "Reconnecting…": playback carries on from the last timeline meanwhile (spec §6). */
        val reconnecting: Boolean = false,
        /** "This song isn't available to you". */
        val unavailable: Boolean = false,
        /** "Your version may be a few seconds off": duration differs from the host's by over 2 s (spec §4). */
        val versionMismatch: Boolean = false,
    ) : ListenTogetherState
}

/**
 * The one door between the UI and the Listen Together engine (spec §5). A singleton, so the UI,
 * PlayerRepositoryImpl and StashPlaybackService share it. The session inside the service reads
 * [commands] and publishes [state]. [active] gates everything else that reacts to the player.
 */
@Singleton
class ListenTogetherController @Inject constructor(@ApplicationContext private val context: Context) {
    sealed interface Command {
        data object Host : Command
        data class Join(val code: String) : Command
        /** A listener leaves; a host hands the room to the longest-joined listener, then leaves. */
        data object Leave : Command
        /** Host only: ends the room for everyone. */
        data object End : Command
        data class React(val emoji: String) : Command
        data class Suggestion(val id: String, val add: Boolean) : Command
        data class MakeHost(val memberId: String) : Command
    }

    private val _active = MutableStateFlow(false)
    /** True from the moment a session starts until it ends (spec §4 "One session-active flag"). */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _sessionEnds = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Fires once each time a session ends; PlayerRepositoryImpl puts the user's queue back on it. */
    val sessionEnds: SharedFlow<Unit> = _sessionEnds.asSharedFlow()

    private val _state = MutableStateFlow<ListenTogetherState>(ListenTogetherState.Idle)
    val state: StateFlow<ListenTogetherState> = _state.asStateFlow()

    private val _reactions = MutableSharedFlow<ServerMessage.Reaction>(extraBufferCapacity = 16)
    val reactions: SharedFlow<ServerMessage.Reaction> = _reactions.asSharedFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** One-line notices for a toast: "Session ended", "This session is full", … */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Read by the session in the playback service; commands queue up until it is running. */
    internal val commands = Channel<Command>(Channel.UNLIMITED)

    @Volatile internal var serviceAttached = false

    fun send(command: Command) {
        commands.trySend(command)
        // The session lives in the playback service, which stops itself after 5 idle minutes.
        // The user is looking at the app when they tap Start or Join, so a plain start is allowed.
        if (!serviceAttached) runCatching { context.startService(Intent(context, StashPlaybackService::class.java)) }
    }

    /**
     * True from a session's end until PlayerRepositoryImpl has put the user's queue back ([sessionEnds]).
     * Until then the player may still hold the session's song, so the repository saves nothing.
     * ponytail: relies on PlayerRepositoryImpl (a singleton the UI creates before any session can start)
     * already collecting [sessionEnds]; an emit with no collector would leave this set until the process dies.
     */
    @Volatile internal var restorePending = false

    /**
     * [restore] = false is the service shutting down: end quietly, with no [sessionEnds]. A restore then
     * would drive the dying service's player; the next launch's cold-start restore brings the queue back.
     */
    internal fun setActive(on: Boolean, restore: Boolean = true) {
        val was = _active.value
        val ending = was && !on && restore
        if (ending) restorePending = true // before `active` drops, so the save gate never opens in between
        _active.value = on
        if (ending) _sessionEnds.tryEmit(Unit)
    }

    internal fun publish(state: ListenTogetherState) { _state.value = state }
    internal fun reaction(reaction: ServerMessage.Reaction) { _reactions.tryEmit(reaction) }
    internal fun message(text: String) { _messages.tryEmit(text) }
}
