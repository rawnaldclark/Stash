package com.stash.core.media.diagnostics

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The last few stream resolves and playback errors of this app run, kept for the
 * diagnostics bundle's "Playback" section ([PlaybackDiagnosticsContributor]). The log
 * tail has them too, but only its last ~1,500 lines, among everything else.
 *
 * [com.stash.core.media.streaming.StreamSourceRegistry] records each chain walk as it
 * ends; [com.stash.core.media.PlayerRepositoryImpl] records each `onPlayerError`.
 * Recording is one small object and a lock, with no I/O, and it never throws into the
 * playback path. Entries hold ids, numbers and fixed words only: a title, a URL (a
 * googlevideo URL carries the user's IP) or a file path never gets in.
 */
@Singleton
class PlaybackDiagnosticsLog @Inject constructor() {

    /** One finished resolve: the rung that served (or why none did), how long it took, the gates it ran under. */
    data class Resolve(
        val atMs: Long,
        val trackId: Long,
        val servedBy: String,
        val elapsedMs: Long,
        val lossless: Boolean,
    )

    /** One `onPlayerError`: what failed, what the player did about it, where the stream came from. */
    data class PlaybackError(
        val atMs: Long,
        val trackId: Long?,
        val code: String,
        val httpStatus: Int?,
        val branch: String,
        val origin: String?,
        val scheme: String?,
    )

    /** One Google Cast event: fixed words and numbers only, never a speaker's name or an address. */
    data class CastEvent(val atMs: Long, val event: String)

    /**
     * Where casting stands, as fixed words ("unavailable", "disconnected",
     * "connecting", "connected"). Written by the Cast integration in the app module.
     */
    @Volatile var castConnection: String = "unavailable"

    /** Test seam for the clock. */
    internal var clock: () -> Long = System::currentTimeMillis

    private val resolves = ArrayDeque<Resolve>()
    private val errors = ArrayDeque<PlaybackError>()
    private val castEvents = ArrayDeque<CastEvent>()

    fun recordResolve(trackId: Long, servedBy: String, elapsedMs: Long, lossless: Boolean) =
        push(resolves, RESOLVES_MAX) { Resolve(clock(), trackId, servedBy, elapsedMs, lossless) }

    /** Keeps [error]'s code name and HTTP status only: its message and causes can carry the URL. */
    fun recordError(trackId: Long?, error: PlaybackException, branch: String, origin: String?, scheme: String?) =
        push(errors, ERRORS_MAX) {
            PlaybackError(clock(), trackId, error.errorCodeName, httpStatusOf(error), branch, origin, scheme)
        }

    /** [event] must be fixed words and numbers: never a speaker's name, a URL or an address. */
    fun recordCast(event: String) = push(castEvents, CAST_EVENTS_MAX) { CastEvent(clock(), event) }

    /** Newest first. */
    fun recentCastEvents(): List<CastEvent> = synchronized(castEvents) { castEvents.reversed() }

    /** Newest first. */
    fun recentResolves(): List<Resolve> = synchronized(resolves) { resolves.reversed() }

    /** Newest first. */
    fun recentErrors(): List<PlaybackError> = synchronized(errors) { errors.reversed() }

    private inline fun <T> push(buffer: ArrayDeque<T>, max: Int, entry: () -> T) {
        // A diagnostics note must never cost playback anything: if building one fails, it is dropped.
        runCatching {
            val e = entry()
            synchronized(buffer) {
                buffer.addLast(e)
                while (buffer.size > max) buffer.removeFirst()
            }
        }
    }

    /** The status behind a bad-HTTP-status error, a few wrappers deep. Never the URL the exception also holds. */
    @OptIn(UnstableApi::class)
    private fun httpStatusOf(error: Throwable): Int? =
        generateSequence(error.cause) { it.cause }.take(MAX_CAUSE_DEPTH)
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()?.responseCode

    internal companion object {
        const val RESOLVES_MAX = 10
        const val ERRORS_MAX = 5
        const val CAST_EVENTS_MAX = 15

        /** Bounds the cause walk, so a cyclic cause chain can't loop. */
        private const val MAX_CAUSE_DEPTH = 5
    }
}
