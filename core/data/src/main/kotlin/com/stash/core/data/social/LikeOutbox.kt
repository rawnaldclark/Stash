package com.stash.core.data.social

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The like-mirroring file: the outbox ([LikeOutbox]) and the likes a service already had ([LikeOwnership]). Its own file, so a
 * corrupt outbox can never take the switches in `like_preferences` with it. One instance per process (DataStore's rule).
 */
internal val Context.likeMirrorDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "like_mirror",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * One like or unlike still to be sent to one service.
 *
 * @property seq identity and order: a new intent for the same song and service gets a new one, so a send that was already under
 *   way when the listener changed their mind can't settle the newer intent ([LikeOutbox.settle] matches on it).
 * @property attempts tries that failed by the service's fault ([LikeMirrorError.OFFLINE] never counts).
 */
data class LikeOutboxOp(
    val seq: Long,
    val trackId: Long,
    val destination: Destination,
    val liked: Boolean,
    val queuedAt: Long,
    val attempts: Int = 0,
    val lastError: LikeMirrorError? = null,
    val lastAttemptAt: Long? = null,
) {
    /** Tried [LikeOutbox.MAX_ATTEMPTS] times: waits for "tap to retry" or a later success instead of retrying on its own. */
    val parked: Boolean get() = attempts >= LikeOutbox.MAX_ATTEMPTS
}

/** Everything the outbox holds. [nextSeq] only grows, so a seq is never reused while its op could still be in flight. */
data class LikeOutboxState(val nextSeq: Long = 1L, val ops: List<LikeOutboxOp> = emptyList())

/** Where the outbox keeps its state: a DataStore file in the app, memory in tests. [update] is atomic. */
interface LikeOutboxStorage {
    val state: Flow<LikeOutboxState>
    suspend fun update(transform: (LikeOutboxState) -> LikeOutboxState): LikeOutboxState
}

/**
 * The likes and unlikes waiting to reach Spotify, YouTube Music and Last.fm, kept on disk so a heart made offline (a subway, a
 * plane) or just before the app was killed still gets there. [LikeCoordinator] fills and drains it; [LikeMirrorWorker] drains it
 * when the app isn't running.
 *
 * At most one op per song and service: a new heart on the same song replaces what was waiting (the last intent wins). Whether it
 * still needs sending is decided when it is sent, against the song's row as it is then (the `*_saved_at` guards in
 * [LikeDestinationDispatcher]), so like → unlike → like before anything went out converges without a single remote call.
 */
@Singleton
class LikeOutbox internal constructor(private val storage: LikeOutboxStorage) {

    @Inject constructor(@ApplicationContext context: Context) : this(DataStoreLikeOutboxStorage(context.likeMirrorDataStore))

    /** Everything waiting, oldest first. */
    val ops: Flow<List<LikeOutboxOp>> = storage.state.map { it.ops }

    suspend fun current(): List<LikeOutboxOp> = storage.state.first().ops

    /** Queues [liked] for [trackId] on each of [destinations], replacing whatever was waiting for that song there. */
    suspend fun put(trackId: Long, liked: Boolean, destinations: Set<Destination>, now: Long) {
        storage.update { put(it, trackId, liked, destinations, now) }
    }

    /** [op] went through, or no longer needs to: gone, unless a newer intent replaced it meanwhile. */
    suspend fun settle(op: LikeOutboxOp) {
        storage.update { settle(it, op) }
    }

    /** [op] failed with [error]: one more try counted, unless it was the connection. */
    suspend fun recordFailure(op: LikeOutboxOp, error: LikeMirrorError, now: Long) {
        storage.update { recordFailure(it, op, error, now) }
    }

    /** No network: every op in [ops] says it is waiting for one (no try counted). */
    suspend fun markWaitingForNetwork(ops: Collection<LikeOutboxOp>, now: Long) {
        if (ops.isEmpty()) return
        storage.update { markWaitingForNetwork(it, ops.mapTo(HashSet()) { op -> op.seq }, now) }
    }

    /** A switch was turned off: what was waiting for that service goes too (the listener said no more). */
    suspend fun dropAllBut(enabled: Set<Destination>) {
        if (current().none { it.destination !in enabled }) return
        storage.update { s -> s.copy(ops = s.ops.filter { it.destination in enabled }) }
    }

    /** "Tap to retry": everything waiting for [destination] is tried again now, as if new. */
    suspend fun retry(destination: Destination) {
        storage.update { retry(it, destination) }
    }

    /**
     * [destination] just took a like, so whatever was parked or held back by a sign-in, a rate limit or the network is worth
     * trying again ([REFUSED][LikeMirrorError.REFUSED] ones stay parked: a success elsewhere proves nothing about them).
     */
    suspend fun freeAfterSuccess(destination: Destination) {
        if (current().none { it.destination == destination && it.attempts > 0 && it.lastError in FREED_BY_SUCCESS }) return
        storage.update { freeAfterSuccess(it, destination) }
    }

    companion object {
        private const val TAG = "LikeOutbox"

        /**
         * Tries before an op waits for the listener. On the worker's exponential back-off (30 s doubling, capped at 5 h by
         * WorkManager) ten tries span about eight hours, long enough for an outage or a rate limit to pass.
         */
        const val MAX_ATTEMPTS = 10

        /** A ceiling no one should reach; past it the oldest go first, so the file can't grow without bound. */
        const val MAX_OPS = 5_000

        private val FREED_BY_SUCCESS = setOf(LikeMirrorError.SIGNED_OUT, LikeMirrorError.RATE_LIMITED, LikeMirrorError.OFFLINE)

        internal fun put(
            state: LikeOutboxState,
            trackId: Long,
            liked: Boolean,
            destinations: Set<Destination>,
            now: Long,
        ): LikeOutboxState {
            if (destinations.isEmpty()) return state
            var seq = state.nextSeq
            val kept = state.ops.filterNot { it.trackId == trackId && it.destination in destinations }
            val added = destinations.sortedBy { it.ordinal }.map { dest ->
                LikeOutboxOp(seq = seq++, trackId = trackId, destination = dest, liked = liked, queuedAt = now)
            }
            var ops = kept + added
            if (ops.size > MAX_OPS) {
                Log.w(TAG, "outbox over $MAX_OPS: dropping the ${ops.size - MAX_OPS} oldest")
                ops = ops.sortedBy { it.seq }.takeLast(MAX_OPS)
            }
            return LikeOutboxState(nextSeq = seq, ops = ops)
        }

        internal fun settle(state: LikeOutboxState, op: LikeOutboxOp): LikeOutboxState =
            if (state.ops.none { it.seq == op.seq }) state else state.copy(ops = state.ops.filterNot { it.seq == op.seq })

        internal fun recordFailure(state: LikeOutboxState, op: LikeOutboxOp, error: LikeMirrorError, now: Long): LikeOutboxState =
            state.copy(
                ops = state.ops.map {
                    when {
                        it.seq != op.seq -> it
                        error == LikeMirrorError.OFFLINE -> it.copy(lastError = error, lastAttemptAt = now)
                        else -> it.copy(attempts = it.attempts + 1, lastError = error, lastAttemptAt = now)
                    }
                },
            )

        internal fun markWaitingForNetwork(state: LikeOutboxState, seqs: Set<Long>, now: Long): LikeOutboxState =
            state.copy(ops = state.ops.map { if (it.seq in seqs) it.copy(lastError = LikeMirrorError.OFFLINE, lastAttemptAt = now) else it })

        internal fun retry(state: LikeOutboxState, destination: Destination): LikeOutboxState =
            state.copy(
                ops = state.ops.map {
                    if (it.destination == destination) it.copy(attempts = 0, lastError = null, lastAttemptAt = null) else it
                },
            )

        internal fun freeAfterSuccess(state: LikeOutboxState, destination: Destination): LikeOutboxState =
            state.copy(
                ops = state.ops.map {
                    if (it.destination == destination && it.lastError in FREED_BY_SUCCESS) {
                        it.copy(attempts = 0, lastError = null, lastAttemptAt = null)
                    } else {
                        it
                    }
                },
            )
    }
}

/**
 * The outbox as one JSON value in the `like_mirror` DataStore. Enums are stored by name and an unknown one (written by a newer
 * build) drops just that op, never the file; anything unreadable reads as empty rather than crashing every heart.
 */
internal class DataStoreLikeOutboxStorage(private val store: DataStore<Preferences>) : LikeOutboxStorage {

    override val state: Flow<LikeOutboxState> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { decode(it[KEY]) }

    override suspend fun update(transform: (LikeOutboxState) -> LikeOutboxState): LikeOutboxState {
        var next = LikeOutboxState()
        store.edit { prefs ->
            next = transform(decode(prefs[KEY]))
            prefs[KEY] = encode(next)
        }
        return next
    }

    @Serializable
    private data class StoredOp(
        val seq: Long,
        val trackId: Long,
        val destination: String,
        val liked: Boolean,
        val queuedAt: Long,
        val attempts: Int = 0,
        val lastError: String? = null,
        val lastAttemptAt: Long? = null,
    )

    @Serializable
    private data class Stored(val nextSeq: Long = 1L, val ops: List<StoredOp> = emptyList())

    private fun encode(state: LikeOutboxState): String = json.encodeToString(
        Stored.serializer(),
        Stored(
            nextSeq = state.nextSeq,
            ops = state.ops.map {
                StoredOp(it.seq, it.trackId, it.destination.name, it.liked, it.queuedAt, it.attempts, it.lastError?.name, it.lastAttemptAt)
            },
        ),
    )

    private fun decode(raw: String?): LikeOutboxState {
        if (raw.isNullOrBlank()) return LikeOutboxState()
        val stored = runCatching { json.decodeFromString(Stored.serializer(), raw) }
            .getOrElse { e ->
                Log.w(TAG, "unreadable like outbox, starting empty: ${e.javaClass.simpleName}")
                return LikeOutboxState()
            }
        val ops = stored.ops.mapNotNull { op ->
            val destination = Destination.entries.firstOrNull { it.name == op.destination } ?: return@mapNotNull null
            LikeOutboxOp(
                seq = op.seq,
                trackId = op.trackId,
                destination = destination,
                liked = op.liked,
                queuedAt = op.queuedAt,
                attempts = op.attempts,
                lastError = op.lastError?.let { name -> LikeMirrorError.entries.firstOrNull { it.name == name } },
                lastAttemptAt = op.lastAttemptAt,
            )
        }
        // A seq at or below one already stored would let two ops share an identity
        val nextSeq = maxOf(stored.nextSeq, (ops.maxOfOrNull { it.seq } ?: 0L) + 1)
        return LikeOutboxState(nextSeq = nextSeq, ops = ops)
    }

    private companion object {
        const val TAG = "LikeOutbox"
        val KEY = stringPreferencesKey("outbox_v1")
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
