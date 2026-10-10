package com.stash.core.data.weblink.mirror

import android.util.Log
import androidx.room.InvalidationTracker
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.weblink.WebLinkConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * When the mirror runs (spec §9 "Diff, not hooks"): one signal for every writer (the heart, a Spotify sync, an import, the
 * playlist editor) instead of a dozen call sites. Room's invalidations of `tracks`, `playlists`, `playlist_tracks` and
 * `listening_events`, debounced 5 s, then a cheap digest of only the kinds that mirror: a run is asked for only when it moved
 * (a play count going up during playback moves `tracks` but no digest, so it costs nothing). Plus the app coming to the
 * foreground (at most once a minute) and the 6-hour periodic run while mirroring is on. Nothing at all while nothing mirrors.
 */
@OptIn(FlowPreview::class)
class MirrorTriggers internal constructor(
    private val status: StateFlow<MirrorStatus>,
    private val load: suspend () -> Unit,
    private val digest: suspend (MirrorStatus) -> String,
    private val invalidations: Flow<Unit>,
    private val scheduler: MirrorScheduler,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    private var lastDigest: String? = null
    private var lastForeground = Long.MIN_VALUE / 2
    private var started = false

    private val mirroring: Boolean get() = status.value.linked && status.value.config?.anyOn == true

    fun start() {
        if (started) return
        started = true
        scope.launch {
            guard { load() }
            var wasBusy = false
            status.collect { s ->
                scheduler.periodic(s.linked && s.config?.anyOn == true)
                // A run just ended: what it wrote is the new starting point, so its own writes don't ask for another run.
                if (wasBusy && !s.busy && s.config?.anyOn == true) lastDigest = guard { digest(s) }
                wasBusy = s.busy
            }
        }
        scope.launch {
            invalidations.debounce(DEBOUNCE_MS).collect {
                val s = status.value
                if (!mirroring || s.busy) return@collect
                val d = guard { digest(s) } ?: return@collect
                if (d != lastDigest) {
                    lastDigest = d
                    scheduler.changed()
                }
            }
        }
    }

    /** The app came to the foreground: a run, at most once a minute, only while something mirrors. */
    fun onForeground() {
        val t = clock()
        if (t - lastForeground < FOREGROUND_GAP_MS) return
        lastForeground = t
        scope.launch {
            if (status.value.config == null) guard { load() }
            if (mirroring) scheduler.now()
        }
    }

    private suspend fun <T> guard(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "mirror trigger failed: ${e.javaClass.simpleName}")
        null
    }

    companion object {
        private const val TAG = "WebLinkMirror"
        const val DEBOUNCE_MS = 5_000L
        const val FOREGROUND_GAP_MS = 60_000L
        val TABLES = arrayOf("tracks", "playlists", "playlist_tracks", "listening_events")
    }
}

/** The app's [MirrorTriggers]: Room's invalidation tracker, the mirror's digests, WorkManager. */
@Singleton
class LibraryChangeSignal @Inject constructor(
    private val database: StashDatabase,
    private val engine: MirrorEngine,
    private val scheduler: MirrorScheduler,
    private val config: WebLinkConfig,
) {
    private val triggers = MirrorTriggers(
        status = engine.status,
        load = { engine.load() },
        digest = { s -> digestOf(s) },
        invalidations = invalidations(),
        scheduler = scheduler,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        clock = { android.os.SystemClock.elapsedRealtime() },
    )

    fun start() {
        if (config.enabled) triggers.start()
    }

    fun onForeground() {
        if (config.enabled) triggers.onForeground()
    }

    private fun invalidations(): Flow<Unit> = callbackFlow {
        val observer = object : InvalidationTracker.Observer(MirrorTriggers.TABLES) {
            override fun onInvalidated(tables: Set<String>) {
                trySend(Unit)
            }
        }
        database.invalidationTracker.addObserver(observer)
        awaitClose { database.invalidationTracker.removeObserver(observer) }
    }

    /** The digests of the kinds that mirror (one cheap query each). */
    private suspend fun digestOf(s: MirrorStatus): String {
        val cfg = s.config ?: return ""
        val dao = database.mirrorDao()
        return buildString {
            if (cfg.likes.dir != Dir.OFF) append("l:").append(dao.likesDigest())
            if (cfg.plays.dir != Dir.OFF) append("|p:").append(dao.playsDigest())
            if (cfg.playlists.dir != Dir.OFF) {
                val ids = s.mirrored.filterKeys { it in cfg.ids }.values.toList()
                if (ids.isNotEmpty()) append("|pl:").append(dao.playlistsDigest(ids))
                if (cfg.newOnes) append("|n:").append(dao.playlistSetDigest())
            }
        }
    }
}
