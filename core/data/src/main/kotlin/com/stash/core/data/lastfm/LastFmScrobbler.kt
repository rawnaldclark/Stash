package com.stash.core.data.lastfm

import android.util.Log
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.ListeningEventEntity
import com.stash.core.data.db.entity.TrackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
     * Submits up to 100 pending events per pass. Last.fm allows
     * `track.scrobble` to submit up to 50 events in one call via the
     * `artist[0]`/`track[0]`/etc. array syntax, but the simpler per-call
     * submission works fine for the low volumes we expect here.
     */
    private suspend fun drainQueue(session: LastFmSession) {
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
            submit(session, event, track)
        }
    }

    private suspend fun submit(session: LastFmSession, event: ListeningEventEntity, track: TrackEntity) {
        val result = apiClient.scrobble(
            sessionKey = session.sessionKey,
            artist = track.artist,
            track = track.title,
            album = track.album.takeIf { it.isNotBlank() },
            timestampEpochSeconds = event.startedAt / 1000,
        )
        if (result.isSuccess) {
            runCatching { listeningEventDao.markScrobbled(event.id) }
        } else {
            Log.w(TAG, "Scrobble failed for event ${event.id}", result.exceptionOrNull())
            // Leave unscrobbled; next trigger retries.
        }
    }

    companion object {
        private const val TAG = "LastFmScrobbler"
    }
}
