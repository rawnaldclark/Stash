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
 * foreground while linked (at most once a minute) and the 6-hour periodic run while mirroring is on. No background work while
 * nothing mirrors.
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
                if (wasBusy && !s.busy && s.config?.anyOn == true) {
                    // A run just ended. Its own writes came before its push read the library, so they are in the digest the push
                    // took; anything that differs from that was changed during the run (a heart tapped meanwhile) and goes in a
                    // run of its own (review S6). What is now is the new starting point.
                    val d = guard { digest(s) }
                    if (d != null && s.pushDigest != null && d != s.pushDigest) ask(s.pushDigest, d)
                    lastDigest = d
                }
                wasBusy = s.busy
            }
        }
        scope.launch {
            invalidations.debounce(DEBOUNCE_MS).collect {
                val s = status.value
                if (!mirroring || s.busy) return@collect
                val d = guard { digest(s) } ?: return@collect
                if (d != lastDigest) {
                    val before = lastDigest
                    lastDigest = d
                    ask(before, d)
                }
            }
        }
    }

    /**
     * A change between [before] and [now]: in likes or playlists, a run 10 s from now; only new plays (one per finished song while
     * plays mirror), a run within 15 minutes, so a listening session costs a few runs, not one per song (review N3).
     */
    private fun ask(before: String?, now: String) {
        if (before != null && before.substringBefore(PLAYS_SEP) == now.substringBefore(PLAYS_SEP)) scheduler.playsChanged() else scheduler.changed()
    }

    /**
     * The app came to the foreground: a run, at most once a minute, while linked (also with every kind off: that run is how this
     * phone learns that a browser turned mirroring on, and with nothing on it only reads the settings and the log).
     */
    fun onForeground() {
        val t = clock()
        if (t - lastForeground < FOREGROUND_GAP_MS) return
        lastForeground = t
        scope.launch {
            if (!status.value.linked) guard { load() }
            if (status.value.linked) scheduler.now()
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

        /** A digest is `<likes and playlists>#<plays>`: a change after this is plays only. */
        const val PLAYS_SEP = '#'
    }
}

/** The app's [MirrorTriggers]: Room's invalidation tracker, the mirror's digests, WorkManager. */
@Singleton
class LibraryChangeSignal @Inject constructor(
    private val database: StashDatabase,
    private val engine: MirrorEngine,
    private val scheduler: MirrorScheduler,
    private val config: WebLinkConfig,
    private val repo: com.stash.core.data.weblink.WebLinkRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // The push reads this when it reads the library, so the end of a run can tell what changed meanwhile (S6).
        engine.digester = { s -> digestOf(s) }
    }

    private val triggers = MirrorTriggers(
        status = engine.status,
        load = { engine.load() },
        digest = { s -> digestOf(s) },
        invalidations = invalidations(),
        scheduler = scheduler,
        scope = scope,
        clock = { android.os.SystemClock.elapsedRealtime() },
    )

    fun start() {
        if (!config.enabled) return
        triggers.start()
        // Unlinked: the mirror's state goes with the link, at once (its periodic work stops with the status).
        scope.launch {
            repo.status.collect { if (it == com.stash.core.data.weblink.WebLinkStatus.NotLinked) engine.forget() }
        }
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
            if (cfg.playlists.dir != Dir.OFF) {
                val ids = s.mirrored.filterKeys { it in cfg.ids }.values.toList()
                if (ids.isNotEmpty()) append("|pl:").append(dao.playlistsDigest(ids))
                if (cfg.newOnes) append("|n:").append(dao.playlistSetDigest())
            }
            append(MirrorTriggers.PLAYS_SEP)
            if (cfg.plays.dir != Dir.OFF) append("p:").append(dao.playsDigest())
        }
    }
}
