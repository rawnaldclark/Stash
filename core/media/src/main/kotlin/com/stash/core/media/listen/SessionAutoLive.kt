package com.stash.core.media.listen

import com.stash.core.data.community.CommunityResult
import com.stash.core.data.community.StationsRepository
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.media.PlayerRepository
import com.stash.core.media.listen.ListenTogetherController.Command
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.model.community.AutoLive
import com.stash.core.model.community.AutoLiveFacts
import com.stash.core.model.community.AutoLiveMode
import com.stash.core.model.community.AutoLiveRoom
import com.stash.core.model.community.AutoLiveStep
import com.stash.core.model.community.Station
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Auto-live (Sessions design §6): puts this phone's station on the air by itself, by [AutoLive]'s rules. It watches
 * playback, the room this phone is in, Download mode and whether Sessions is on; reads the station when playback
 * starts (and again when it's edited); and looks again every [TICK_MS] while the station has an auto-live mode, for
 * a window opening or closing and for the network coming back. A station with auto-live off costs one read at the
 * start of playback and nothing after.
 */
@Singleton
class SessionAutoLive internal constructor(
    private val playing: Flow<Boolean>,
    private val room: StateFlow<ListenTogetherState>,
    private val downloadMode: Flow<Boolean>,
    private val sessionsOn: StateFlow<Boolean?>,
    private val edits: Flow<Int>,
    private val myStation: suspend () -> Station?,
    private val online: () -> Boolean,
    private val send: (Command) -> Unit,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    @Inject constructor(
        player: PlayerRepository,
        controller: ListenTogetherController,
        streaming: StreamingPreference,
        stations: StationsRepository,
        connectivity: ConnectivityMonitor,
    ) : this(
        playing = player.playerState.map { it.isPlaying }.distinctUntilChanged(),
        room = controller.state,
        // Streaming off is Download mode: fully offline, never on the air.
        downloadMode = streaming.enabled.map { !it }.distinctUntilChanged(),
        sessionsOn = stations.sessionsOn,
        edits = stations.revision,
        myStation = { (stations.mine() as? CommunityResult.Ok)?.value },
        online = connectivity::isConnected,
        send = controller::send,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        clock = System::currentTimeMillis,
    )

    private val auto = AutoLive()
    private var station: Station? = null
    private var stationReadAt = 0L
    private var isPlaying = false
    private var isDownloadMode = false
    private var ticker: Job? = null

    private val _wentLive = MutableSharedFlow<Station>(extraBufferCapacity = 1)

    /** Each time auto-live put the station on the air: what the day's first quiet notice hangs on. */
    val wentLive: SharedFlow<Station> = _wentLive.asSharedFlow()

    /** Call once, at app start. */
    fun start() {
        scope.launch {
            combine(playing, downloadMode, room, sessionsOn, edits) { p, d, _, _, e -> Triple(p, d, e) }.collect { (p, d, e) ->
                val started = p && !isPlaying
                val edited = e != lastEdit
                lastEdit = e
                isPlaying = p
                isDownloadMode = d
                // The station is read when the music starts (at most every STATION_FRESH_MS) and after an edit.
                if (edited || (started && clock() - stationReadAt >= STATION_FRESH_MS)) readStation()
                look()
            }
        }
    }

    private var lastEdit = Int.MIN_VALUE

    private suspend fun readStation() {
        // Sessions known to be off: nothing to read, and nothing to ask the Worker.
        if (sessionsOn.value == false) return
        station = runCatching { myStation() }.getOrNull()
        stationReadAt = clock()
        val wanted = AutoLiveMode.of(station?.autoLive) != AutoLiveMode.OFF
        if (wanted && ticker == null) {
            ticker = scope.launch { while (isActive) { delay(TICK_MS); look() } }
        } else if (!wanted) {
            ticker?.cancel()
            ticker = null
        }
    }

    private fun roomNow(): AutoLiveRoom {
        val r = room.value
        return when {
            r is ListenTogetherState.Idle -> AutoLiveRoom.NONE
            r is ListenTogetherState.InRoom && r.isHost && r.session != null && r.session.station == station?.id -> AutoLiveRoom.HOSTING
            else -> AutoLiveRoom.ELSEWHERE
        }
    }

    private fun look() {
        val facts = AutoLiveFacts(
            station = station,
            playing = isPlaying,
            room = roomNow(),
            downloadMode = isDownloadMode,
            communityOn = sessionsOn.value == true,
            online = online(),
        )
        when (val step = auto.look(facts, clock())) {
            AutoLiveStep.Nothing -> Unit
            is AutoLiveStep.GoLive -> {
                send(Command.GoLive(step.station.id, step.station.hostName))
                _wentLive.tryEmit(step.station)
            }
            AutoLiveStep.EndAfterSong -> send(Command.LastSong(true))
        }
    }

    private companion object {
        const val TICK_MS = 30_000L
        const val STATION_FRESH_MS = 5 * 60_000L
    }
}
