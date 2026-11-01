package com.stash.core.data.social

import android.util.Log
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.mapper.toDomain
import com.stash.core.data.prefs.LikePreferences
import com.stash.core.data.social.spotify.SpotifyRateLimitException
import com.stash.core.data.social.stash.StashLikedPlaylistRepository
import com.stash.core.model.MusicSource
import com.stash.core.model.Track
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What one drain came to. [retryable]: still waiting and not parked, so worth another try on its own. */
data class LikeDrainReport(val sent: Int, val retryable: Int)

/**
 * v0.9.52: single entry point for the heart button. Every heart that should reach Spotify, YouTube Music or Last.fm calls
 * [setLiked]: Now Playing, the notification and Android Auto, Community, a shared song, and Your record's song rows.
 *
 * Contract:
 *  1. Local first, always. The Stash like/unlike runs synchronously in the caller's coroutine and its failure propagates (callers
 *     keep their snackbar + optimistic-rollback UX). Local state never depends on external success: hearts work offline.
 *  2. Mirroring goes through a persisted outbox ([LikeOutbox]). When a "Like on … too" switch is on, the heart is written there
 *     (one op per song and service, the last intent wins) and sent at once in-process; a heart made offline, or just before the
 *     app was killed, is sent when the connection comes back or by [LikeMirrorWorker], which also retries what failed on an
 *     exponential back-off. Nothing is lost to a dropped connection any more, and "will retry" is true.
 *  3. One sender at a time ([drainLock]), paced [minGapMs] apart; a Spotify Retry-After rests that service (capped at
 *     [MAX_BACKOFF_MS]) while the others go on. Prefs and the song's row are read when an op is sent, not when it was queued:
 *     a switch turned off drops what was waiting for it, and like → unlike → like converges on the `*_saved_at` guards.
 *  4. Failures are visible: the line under each switch ([status]) says how many are waiting and why; an expired sign-in also
 *     raises [mirrorFailures] once per service per session (only while someone is listening, so a background failure is
 *     announced the next time the app is open).
 *  5. Never removes a like Stash didn't make ([LikeDestinationDispatcher]: a song already liked on the service stays theirs).
 *
 * Forward-only by design (no backfill of pre-existing likes: bulk writes are the most bot-shaped action).
 *
 * App-lifetime scope is instantiated inline like LosslessUrlPrefetcher (no Hilt ApplicationScope qualifier exists in this
 * codebase). The internal constructor is the test seam (TestScope + virtual-time minGap and clock).
 */
@Singleton
class LikeCoordinator internal constructor(
    private val stashLikedRepository: StashLikedPlaylistRepository,
    private val likePreferences: LikePreferences,
    private val dispatcher: LikeDestinationDispatcher,
    private val trackDao: TrackDao,
    private val crossPlatformResolver: CrossPlatformLikeResolver,
    private val outbox: LikeOutbox,
    private val scheduler: LikeMirrorScheduler,
    scope: CoroutineScope,
    private val minGapMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Inject constructor(
        stashLikedRepository: StashLikedPlaylistRepository,
        likePreferences: LikePreferences,
        dispatcher: LikeDestinationDispatcher,
        trackDao: TrackDao,
        crossPlatformResolver: CrossPlatformLikeResolver,
        outbox: LikeOutbox,
        scheduler: WorkManagerLikeMirrorScheduler,
    ) : this(
        stashLikedRepository,
        likePreferences,
        dispatcher,
        trackDao,
        crossPlatformResolver,
        outbox,
        scheduler,
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        MIN_GAP_MS_DEFAULT,
    )

    /** Wakes the in-process sender: a new heart, a "tap to retry", a connection back. Conflated: one wake covers many. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** One sender at a time, in-process or the worker: an op is never sent twice at once. */
    private val drainLock = Mutex()

    /** A service resting after a Retry-After, until (epoch millis). In memory: a new process may simply try again. */
    private val restUntil = ConcurrentHashMap<Destination, Long>()

    /** The services whose expired sign-in was already announced this session. */
    private val alerted: MutableSet<Destination> = ConcurrentHashMap.newKeySet()

    private val _mirrorFailures = MutableSharedFlow<Destination>(extraBufferCapacity = 1)

    /** A service no longer takes this phone's sign-in (once per service per session). The app says so in a toast. */
    val mirrorFailures: SharedFlow<Destination> = _mirrorFailures

    init {
        scheduler.watchConnectivity { wake.trySend(Unit) }
        // What a process that died with hearts still queued owes: sent now
        wake.trySend(Unit)
        scope.launch { senderLoop() }
    }

    /**
     * Toggle the heart for [trackId]. [trackId] must be a real Room PK: callers already resolve synthetic/streaming ids
     * (ensureTrackPersisted / resolveTrackIdForLike) before calling.
     */
    suspend fun setLiked(trackId: Long, liked: Boolean) {
        if (liked) stashLikedRepository.add(trackId) else stashLikedRepository.remove(trackId)
        queueMirror(trackId, liked)
    }

    /** The line under [destination]'s switch, live. */
    fun status(destination: Destination): Flow<LikeMirrorStatus> =
        outbox.ops.map { LikeMirrorStatus.of(it, destination) }.distinctUntilChanged()

    /** "Tap to retry": everything waiting for [destination] goes again now, its tries counted from zero. */
    suspend fun retry(destination: Destination) {
        restUntil.remove(destination)
        alerted.remove(destination)
        outbox.retry(destination)
        wake.trySend(Unit)
    }

    /** The worker's run: everything not parked, fresh or failed before, up to [maxGroups] songs. */
    internal suspend fun drainAll(maxGroups: Int): LikeDrainReport = drain(onlyFresh = false, maxGroups = maxGroups)

    private suspend fun queueMirror(trackId: Long, liked: Boolean) {
        try {
            val destinations = enabledDestinations()
            // Fast path: a mirroring-off install never touches the outbox
            if (destinations.isEmpty()) return
            outbox.put(trackId, liked, destinations, now())
            // The safety net if this process dies before the in-process send below gets there
            scheduler.schedule(SAFETY_NET_DELAY_MS)
            wake.trySend(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The heart itself is done; only its mirror couldn't be queued
            Log.w(TAG, "couldn't queue the mirror for track $trackId", e)
        }
    }

    private suspend fun senderLoop() {
        for (signal in wake) {
            val report = try {
                // In-process: new hearts only (and ones that only ever waited for the network). Failed tries belong to the
                // worker's back-off, so a run of hearts can't hammer a service that is saying no.
                drain(onlyFresh = true, maxGroups = IN_PROCESS_MAX_GROUPS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "like mirror send crashed", e)
                null
            }
            if (report == null || report.retryable > 0) scheduler.schedule()
        }
    }

    private suspend fun drain(onlyFresh: Boolean, maxGroups: Int): LikeDrainReport = drainLock.withLock {
        val tried = HashSet<Long>()
        val heldBack = HashSet<Destination>()
        var groups = 0
        var sent = 0
        var pace = false
        while (groups < maxGroups) {
            val enabled = enabledDestinations()
            outbox.dropAllBut(enabled)
            val eligible = outbox.current().filter { op ->
                op.seq !in tried && !op.parked && op.destination !in heldBack && !resting(op.destination) &&
                    (!onlyFresh || op.attempts == 0)
            }
            // Fewest tries first: a song a service keeps refusing goes after the new hearts, never in front of them
            val head = eligible.minWithOrNull(compareBy<LikeOutboxOp>({ it.attempts }, { it.seq })) ?: break
            if (!scheduler.online()) {
                outbox.markWaitingForNetwork(eligible, now())
                break
            }
            if (pace) delay(minGapMs)
            val group = eligible.filter { it.trackId == head.trackId && it.liked == head.liked }
            tried += group.map { it.seq }
            val fired = fire(head.trackId, head.liked, group.mapTo(LinkedHashSet()) { it.destination })
            var offline = false
            for (op in group) {
                val result = fired.results[op.destination]
                val error = result?.exceptionOrNull()
                if (error == null) {
                    // Sent, or nothing to send (the song is gone, or has no id there and none was found)
                    outbox.settle(op)
                    if (result != null) {
                        sent++
                        outbox.freeAfterSuccess(op.destination)
                    }
                    continue
                }
                val kind = LikeMirrorError.of(error)
                Log.w(TAG, "mirror ${verb(op.liked)} → ${op.destination} failed for track ${op.trackId} ($kind): ${error.message}")
                if (kind == LikeMirrorError.PERMANENT) {
                    outbox.settle(op)
                    continue
                }
                outbox.recordFailure(op, kind, now())
                when (kind) {
                    LikeMirrorError.OFFLINE -> offline = true
                    LikeMirrorError.SIGNED_OUT -> {
                        heldBack += op.destination
                        announceSignedOut(op.destination)
                    }
                    LikeMirrorError.RATE_LIMITED -> {
                        heldBack += op.destination
                        rest(op.destination, error)
                    }
                    // A service saying no is asked nothing more this drain: the next try is the worker's, on its back-off
                    LikeMirrorError.REFUSED -> heldBack += op.destination
                    else -> Unit
                }
            }
            groups++
            pace = fired.called
            if (offline) break
        }
        LikeDrainReport(sent = sent, retryable = outbox.current().count { !it.parked })
    }

    /** What sending one song's op to [destinations] came to, by service; a service missing from it had nothing to send. */
    private class Fired(val results: Map<Destination, Result<Unit>>, val called: Boolean)

    private suspend fun fire(trackId: Long, liked: Boolean, destinations: Set<Destination>): Fired {
        var track = trackDao.getById(trackId)?.toDomain() ?: return Fired(emptyMap(), called = false)

        // Cross-platform backfill (LIKE only): a heart should reach BOTH enabled services even when the track was only known
        // to one, so a missing Spotify URI / YouTube id is resolved (search + confidence gate + persist). Skipped on unlike:
        // we can't have saved to a platform we never resolved. A cloud song (the user's own Dropbox / OneDrive file) and a
        // SoundCloud one are never searched for on Spotify or YouTube: that would write an online id onto them, the first
        // step of matching them. They still reach Last.fm, which goes by artist and title.
        if (liked && searchable(track)) {
            if (Destination.SPOTIFY in destinations && track.spotifyUri == null) {
                crossPlatformResolver.ensureSpotifyUri(track)?.let { track = track.copy(spotifyUri = it) }
            }
            if (Destination.YT_MUSIC in destinations && track.youtubeId == null) {
                crossPlatformResolver.ensureYoutubeId(track)?.let { track = track.copy(youtubeId = it) }
            }
        }

        val sendable = destinations.filterTo(LinkedHashSet()) { hasId(track, it) }
        // An id that couldn't be found while the connection was down is not "no match": it waits for the network
        val unresolved = if (liked && sendable.size < destinations.size && !scheduler.online()) {
            (destinations - sendable).associateWith { Result.failure<Unit>(IOException("offline while looking the song up")) }
        } else {
            emptyMap()
        }
        if (sendable.isEmpty()) return Fired(unresolved, called = false)
        val results = if (liked) dispatcher.like(track, sendable) else dispatcher.unlike(track, sendable)
        return Fired(results + unresolved, called = true)
    }

    private fun searchable(track: Track): Boolean =
        track.source != MusicSource.CLOUD && track.source != MusicSource.SOUNDCLOUD && track.soundCloudId == null

    private fun hasId(track: Track, destination: Destination): Boolean = when (destination) {
        Destination.SPOTIFY -> track.spotifyUri != null
        Destination.YT_MUSIC -> track.youtubeId != null
        // No id precondition: Last.fm matches on artist + title, so there is nothing to resolve and nothing to skip for
        Destination.LAST_FM -> true
        Destination.STASH -> false
    }

    private suspend fun enabledDestinations(): Set<Destination> = buildSet {
        if (likePreferences.mirrorLikesSpotifyNow()) add(Destination.SPOTIFY)
        if (likePreferences.mirrorLikesYtMusicNow()) add(Destination.YT_MUSIC)
        if (likePreferences.mirrorLikesLastFmNow()) add(Destination.LAST_FM)
    }

    private fun resting(destination: Destination): Boolean = restUntil[destination]?.let { now() < it } == true

    /**
     * Spotify's Retry-After rests Spotify alone, capped at [MAX_BACKOFF_MS]: it has handed out 86400 s (24 h), and obeying that
     * verbatim once froze every other service's likes for a day. Without one, the worker's back-off decides.
     */
    private fun rest(destination: Destination, error: Throwable) {
        val seconds = (error as? SpotifyRateLimitException)?.retryAfterSeconds ?: return
        restUntil[destination] = now() + minOf(seconds * 1_000L, MAX_BACKOFF_MS)
    }

    private fun announceSignedOut(destination: Destination) {
        // Only counted as said when someone heard it: a failure in the background is announced next time the app is open
        if (_mirrorFailures.subscriptionCount.value > 0 && alerted.add(destination)) {
            _mirrorFailures.tryEmit(destination)
        }
    }

    private fun verb(liked: Boolean) = if (liked) "like" else "unlike"

    companion object {
        private const val TAG = "LikeCoordinator"
        private const val MIN_GAP_MS_DEFAULT = 1_500L

        /**
         * Songs the in-process sender sends per wake. A backlog (hearts made offline for days, a phone back online) trickles:
         * this many now, the rest through the worker's runs on its back-off, never one long burst.
         */
        internal const val IN_PROCESS_MAX_GROUPS = 20

        /** Ceiling on a Retry-After rest, no matter how large the service's answer is. */
        internal const val MAX_BACKOFF_MS = 5 * 60 * 1_000L

        /**
         * When the worker looks again after a heart. The heart is sent at once in-process; this only matters when the process
         * dies before that, so it can wait, and one worker covers a whole run of hearts (KEEP).
         */
        internal const val SAFETY_NET_DELAY_MS = 10 * 60 * 1_000L
    }
}

/** The service's own name, for messages (a brand name: never translated). */
val Destination.serviceName: String
    get() = when (this) {
        Destination.STASH -> "Stash"
        Destination.SPOTIFY -> "Spotify"
        Destination.YT_MUSIC -> "YouTube Music"
        Destination.LAST_FM -> "Last.fm"
    }
