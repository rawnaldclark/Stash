package com.stash.core.media.listen

import com.stash.core.data.listen.RecapStore
import com.stash.core.model.listen.SessionRecap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
) {
    @Inject constructor(controller: ListenTogetherController, store: RecapStore) : this(
        controller,
        { store.add(it) },
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        System::currentTimeMillis,
    )

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
        val recap = b.finish(clock(), ended = !left)?.let { it.copy(stationName = stationNames[it.stationId]) } ?: return
        if (!left) _ended.value = recap
        scope.launch { keep(recap) }
    }

    /** The room's screen read the station's name: recaps of its sessions carry it. */
    fun noteStation(id: String, name: String) { stationNames[id] = name }

    /** This person saved the playing song. */
    fun saved() { builder?.saved() }

    fun shown() { _ended.value = null }

    /** Opens a kept recap again (the Sessions screen's Recent list). */
    fun show(recap: SessionRecap) { _ended.value = recap }
}
