package com.stash.core.data.lastfm

import android.util.Log
import com.stash.core.common.primaryArtist
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.ListeningEventEntity
import com.stash.core.data.db.entity.TrackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pulls pending listening events out of [ListeningEventDao], resolves
 * the Track metadata, and submits them to Last.fm as scrobbles. Marks
 * each event `scrobbled=true` on success; failures are retried at next
 * trigger (app start, new listen event, manual "Sync scrobbles" button).
 *
 * Runs only when the user has connected Last.fm (session key present).
 * Safe to construct-and-inject unconditionally — [start] is a no-op
 * until a session key appears, so users without Last.fm pay nothing.
 *
 * The current implementation drains the queue every time a new listen
 * event is recorded (via a Flow of the pending count). That's fine for
 * this scale (< 100 pending at a time typically); for heavier loads a
 * WorkManager-backed retry-with-backoff would be appropriate.
 */
@Singleton
class LastFmScrobbler @Inject constructor(
    private val apiClient: LastFmApiClient,
    private val sessionPreference: LastFmSessionPreference,
    private val listeningEventDao: ListeningEventDao,
    private val trackDao: TrackDao,
    private val credentials: LastFmCredentials,
    private val rateLimitGate: LastFmRateLimitGate,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Must be called once from Application.onCreate. */
    fun start() {
        if (!credentials.isConfigured) return
        scope.launch {
            combine(
                sessionPreference.session,
                listeningEventDao.pendingScrobbleCount().distinctUntilChanged(),
            ) { session, _ ->
                session
            }.collect { session ->
                if (session != null) drainQueue(session)
            }
        }
    }

    /**
     * Manually drain the pending-scrobble queue once, on demand. Used by
     * the Settings "Sync scrobbles now" button — after the user finishes
     * the Last.fm connect handshake, they can tap this to push the full
     * backlog (cold-start import can leave ~300 synthetic plays +
     * accumulated real plays pending). Returns the pair
     * (submitted, session-present). When the session is null the call is
     * a cheap no-op so the UI can surface a "Connect first" message.
     */
    suspend fun drainNow(): DrainResult {
        val session = runCatching { sessionPreference.session.firstOrNull() }.getOrNull()
            ?: return DrainResult(submitted = 0, sessionPresent = false)
        val before = runCatching { listeningEventDao.pendingScrobbles(limit = Int.MAX_VALUE) }
            .getOrElse { emptyList() }
            .size
        drainQueue(session)
        val after = runCatching { listeningEventDao.pendingScrobbles(limit = Int.MAX_VALUE) }
            .getOrElse { emptyList() }
            .size
        return DrainResult(submitted = (before - after).coerceAtLeast(0), sessionPresent = true)
    }

    /**
     * Fire-and-forget now-playing notification. Call this when the user
     * starts playing a track (including repeats — each play start is a
     * fresh now-playing ping). No retry on failure; Last.fm treats
     * now-playing as best-effort.
     */
    suspend fun notifyNowPlaying(artist: String, track: String, album: String? = null) {
        val session = runCatching { sessionPreference.session.firstOrNull() }.getOrNull()
            ?: return
        val submitArtist = scrobbleArtist(artist)
        runCatching {
            apiClient.updateNowPlaying(
                sessionKey = session.sessionKey,
                artist = submitArtist,
                track = track,
                album = album,
            )
        }.onFailure {
            Log.w(TAG, "now-playing update failed", it)
        }
    }

    /** Result of a [drainNow] invocation, surfaced to the Settings UI. */
    data class DrainResult(val submitted: Int, val sessionPresent: Boolean)

    /**
     * Submits up to 100 pending events per pass. Last.fm allows
     * `track.scrobble` to submit up to 50 events in one call via the
     * `artist[0]`/`track[0]`/etc. array syntax, but the simpler per-call
     * submission works fine for the low volumes we expect here.
     */
    private suspend fun drainQueue(session: LastFmSession) {
        // Offline, every submission in this pass is doomed, and a pass runs
        // on EVERY recorded listen. Backing off after consecutive failures
        // turns a session's worth of guaranteed-to-fail requests — each one
        // paying DNS plus a connect timeout and holding the radio awake —
        // into one probe per cooldown. Rows are never dropped, only deferred.
        val now = System.currentTimeMillis()
        if (rateLimitGate.isOpen(SCROBBLE_GATE_KEY, now)) return

        val pending = runCatching { listeningEventDao.pendingScrobbles(limit = 100) }
            .getOrElse {
                Log.w(TAG, "Failed to load pending scrobbles", it)
                return
            }
        for (event in pending) {
            val track = runCatching { trackDao.getById(event.trackId) }.getOrNull()
            if (track == null) {
                // Track was deleted between recording and scrobbling — mark as
                // scrobbled so we stop retrying a dead row.
                runCatching { listeningEventDao.markScrobbled(event.id) }
                continue
            }
            if (!submit(session, event, track)) {
                // Stop at the first failure rather than marching the rest of
                // the backlog into the same wall: nothing about row 2 can
                // succeed for a reason row 1 did not already disprove. The
                // remaining rows stay pending for the next trigger.
                rateLimitGate.recordRateLimited(SCROBBLE_GATE_KEY, now)
                return
            }
        }
        rateLimitGate.recordSuccess(SCROBBLE_GATE_KEY)
    }

    /** True when the event was accepted (or is permanently unscrobblable). */
    private suspend fun submit(
        session: LastFmSession,
        event: ListeningEventEntity,
        track: TrackEntity,
    ): Boolean {
        val result = apiClient.scrobble(
            sessionKey = session.sessionKey,
            artist = scrobbleArtist(track.artist),
            track = track.title,
            album = track.album.takeIf { it.isNotBlank() },
            timestampEpochSeconds = event.startedAt / 1000,
        )
        if (result.isSuccess) {
            runCatching { listeningEventDao.markScrobbled(event.id) }
            return true
        }
        Log.w(TAG, "Scrobble failed for event ${event.id}", result.exceptionOrNull())
        // Leave unscrobbled; next trigger retries.
        return false
    }

    /**
     * Applies the "only first artist" preference, if the user enabled it.
     */
    private suspend fun scrobbleArtist(artist: String): String =
        if (sessionPreference.firstArtistOnly.firstOrNull() == true) artist.primaryArtist() else artist

    companion object {
        private const val TAG = "LastFmScrobbler"

        /**
         * Breaker key for scrobble submission. Distinct from the read-path
         * API keys sharing [LastFmRateLimitGate], so a throttled read key
         * never blocks writes and a dead network never blocks reads.
         */
        const val SCROBBLE_GATE_KEY = "scrobble-submit"
    }
}
