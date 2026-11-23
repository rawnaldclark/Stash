package com.stash.core.media.listen

import com.stash.core.data.community.CommunityResult
import com.stash.core.data.community.StationsRepository
import com.stash.core.data.listen.RecapStore
import com.stash.core.model.listen.SessionRecap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watches the session this phone is in and, when its stay is over, makes its recap ([RecapBuilder]), keeps it
 * ([RecapStore]) and offers it ([ended]) when the session ended around the person (one who chose to leave isn't
 * shown a recap; theirs is kept for later). A singleton started with the app, so a host with the phone in a
 * pocket still gets theirs.
 */
@Singleton
class SessionRecapRecorder internal constructor(
    private val controller: ListenTogetherController,
    private val keep: suspend (SessionRecap) -> Unit,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    /** How many follow this phone's own station now; null when it can't be read. Asked as a host's session starts and ends. */
    private val followers: suspend () -> Int? = { null },
    /** When station [String] is next on the air by its schedule; null when it has none or can't be read. Asked as a listener's stay ends. */
    private val nextOn: suspend (String) -> Long? = { null },
) {
    @Inject constructor(controller: ListenTogetherController, store: RecapStore, stations: StationsRepository) : this(
        controller,
        { store.add(it) },
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        System::currentTimeMillis,
        { (stations.mine() as? CommunityResult.Ok)?.value?.followers },
        { id -> (stations.station(id) as? CommunityResult.Ok)?.value?.station?.next?.start },
    )

    /** A host's session: the station's followers as it went on the air, once read. */
    private var followersBefore: Deferred<Int?>? = null

    private var builder: RecapBuilder? = null
    private var code: String? = null
    private val stationNames = HashMap<String, String>()

    private val _ended = MutableStateFlow<SessionRecap?>(null)

    /** The recap of a session that just ended around this phone, until [shown]. */
    val ended: StateFlow<SessionRecap?> = _ended.asStateFlow()

    /** Call once, at app start. */
    fun start() {
        scope.launch {
            controller.state.collect { state ->
                val room = (state as? ListenTogetherState.InRoom)?.takeIf { it.session != null }
                when {
                    room == null -> finish()
                    builder == null || code != room.code -> {
                        finish()
                        controller.userLeft = false
                        code = room.code
                        builder = RecapBuilder(room, clock())
                        followersBefore = if (room.isHost) scope.async { read() } else null
                    }
                    else -> builder?.on(room, clock())
                }
            }
        }
    }

    private fun finish() {
        val b = builder ?: return
        builder = null
        code = null
        val left = controller.userLeft
        controller.userLeft = false
        val before = followersBefore
        followersBefore = null
        val recap = b.finish(clock(), ended = !left)?.let { it.copy(stationName = stationNames[it.stationId]) }
        if (recap == null) { before?.cancel(); return }
        // Shown at once; a host's gains the new followers, and a listener's when the station is on next, as they come back.
        if (!left) _ended.value = recap
        scope.launch {
            val was = before?.await()
            val now = if (was != null) read() else null
            val next = if (recap.wasHost || recap.stationId.isEmpty()) null else {
                withTimeoutOrNull(FOLLOWERS_WAIT_MS) { runCatching { nextOn(recap.stationId) }.getOrNull() }?.takeIf { it > recap.endedAt }
            }
            val full = when {
                was != null && now != null -> recap.copy(newFollowers = (now - was).coerceAtLeast(0))
                next != null -> recap.copy(nextOnAt = next)
                else -> recap
            }
            if (full != recap && _ended.value == recap) _ended.value = full
            keep(full)
        }
    }

    /** The follower count, or null when it doesn't come in time: a recap never waits long for it. */
    private suspend fun read(): Int? = withTimeoutOrNull(FOLLOWERS_WAIT_MS) { runCatching { followers() }.getOrNull() }

    /** The room's screen read the station's name: recaps of its sessions carry it. */
    fun noteStation(id: String, name: String) { stationNames[id] = name }

    /** This person saved the playing song. */
    fun saved() { builder?.saved() }

    fun shown() { _ended.value = null }

    /** Opens a kept recap again (the Sessions screen's Recent list). */
    fun show(recap: SessionRecap) { _ended.value = recap }

    internal companion object {
        const val FOLLOWERS_WAIT_MS = 5_000L
    }
}
