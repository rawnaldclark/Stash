package com.stash.data.download.lossless.quota

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stash.data.download.lossless.relay.RelaySigner
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * One day's lossless allowance as the relays last reported it: [used] of [limit] lossless
 * answers, [limit] null = unlimited (a supporter), until [resetsAtSec] (UTC midnight, epoch s).
 */
data class LosslessAllowance(val used: Int, val limit: Int?, val resetsAtSec: Long) {
    val unlimited: Boolean get() = limit == null
    val resetsAtMs: Long get() = resetsAtSec * 1000L

    /** The day's allowance is gone (only a numeric limit can be). */
    val exhausted: Boolean get() = limit != null && used >= limit
}

/** A one-time-per-day heads-up for the global message line; [LosslessQuotaText.notice] words it. */
sealed interface LosslessQuotaNotice {
    val resetsAtMs: Long

    /** [left] of [limit] Tidal & Qobuz answers (FLAC or AAC) remain today ([LosslessQuota.WARN_LEFT] or fewer). */
    data class RunningLow(val left: Int, val limit: Int, override val resetsAtMs: Long) : LosslessQuotaNotice

    /** All [limit] are spent; Tidal and Qobuz resume at [resetsAtMs]. */
    data class Spent(val limit: Int, override val resetsAtMs: Long) : LosslessQuotaNotice
}

private val Context.losslessQuotaDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "lossless_quota",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * The daily lossless allowance (500 free a day, reset at UTC midnight, shared by the Qobuz and
 * Tidal relays; spec 2026-10-17). Both relay clients feed it from EVERY answer's
 * `X-Stash-Lossless: <used>/<limit|unlimited>` + `X-Stash-Lossless-Reset: <epoch s>` headers
 * and from the `429 daily_lossless_cap` body; [LosslessQuotaRefresher] asks a `/quota`
 * endpoint once on a cold start when what is stored is from a previous day.
 *
 * [isSpent] is what the rungs read: while it holds, the Qobuz relay mint and the Tidal FLAC
 * call are skipped (no request) and playback falls to the lossy rungs. It clears by itself at
 * [LosslessAllowance.resetsAtSec]: [current] rolls a stale day over to a fresh one.
 *
 * Within a day the relay's count only grows, so out-of-order answers never move it back; a
 * later day replaces it. Persisted in its own DataStore (no Room migration) so a restart
 * keeps the spent state and the counter line has a value before the first relay answer.
 *
 * The day is judged on the relays' clock: the device clock plus the offset [RelaySigner]
 * learned from a relay's `X-Stash-Server-Time`, so a phone whose clock runs ahead does not
 * read the allowance as reset (and ask again) before the relays' day has turned over.
 */
@Singleton
class LosslessQuota internal constructor(
    private val store: DataStore<Preferences>?,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
) {
    @Inject constructor(@ApplicationContext context: Context, signer: Provider<RelaySigner>) :
        this(
            context.losslessQuotaDataStore,
            { System.currentTimeMillis() + signer.get().clockOffsetSec * 1000L },
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val _state = MutableStateFlow<LosslessAllowance?>(null)

    /**
     * The latest allowance reported, as received: it may be from a day that has since reset.
     * Read [current] for "today", or [asOf] on an emitted value.
     */
    val state: StateFlow<LosslessAllowance?> = _state.asStateFlow()

    private val loaded = CompletableDeferred<Unit>()
    private val noticeMutex = Mutex()

    /** Day ([LosslessAllowance.resetsAtSec]) and level (1 low, 2 spent) of the last notice. */
    @Volatile private var noticedDay = 0L
    @Volatile private var noticedLevel = 0

    /**
     * The day ([LosslessAllowance.resetsAtSec]) a supporter claim lifted the spent state on this
     * device ([liftSpent]); 0 = none. Lasts until the next relay answer, which says for itself.
     */
    @Volatile private var liftedDay = 0L

    private val _spentAnswers = MutableSharedFlow<Long>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Every relay answer that reports the day spent, as its reset (epoch s), repeats included:
     * [state] stays put when the same spent day is reported again. A reset sweep that ran
     * before the relays' day turned over is booked again from here.
     */
    val spentAnswers: SharedFlow<Long> = _spentAnswers.asSharedFlow()

    private val notices = Channel<LosslessQuotaNotice>(Channel.CONFLATED)

    /**
     * Heads-ups at [WARN_LEFT] left and at the limit, once per UTC day each. Buffered (conflated)
     * until the app collects it, so one raised in the background shows on return; one whose day
     * has reset by then, or raised before a supporter claim lifted the limit, is dropped.
     */
    val noticeEvents: Flow<LosslessQuotaNotice> = notices.receiveAsFlow()
        .filter { nowMs() < it.resetsAtMs && current()?.unlimited != true && liftedDay == 0L }

    init {
        if (store == null) loaded.complete(Unit) else scope.launch { load(store) }
    }

    /** Suspends until the stored value (if any) is in memory. */
    suspend fun awaitLoaded() = loaded.await()

    /** Today's allowance, or null when nothing is known. A day that has reset reads as 0 used. */
    fun current(): LosslessAllowance? = _state.value?.let { asOf(it, nowMs()) }

    /** True while today's allowance is used up (lossless rungs skip until [resetsAtMs]). */
    fun isSpent(): Boolean = current()?.let { it.exhausted && it.resetsAtSec != liftedDay } == true

    /** When the current allowance resets, epoch ms; the next UTC midnight when nothing is known. */
    fun resetsAtMs(): Long = current()?.resetsAtMs ?: nextUtcMidnightSec(nowMs()) * 1000L

    /** The clock the allowance is judged by (the relays'), epoch ms; one seam for its users' tests. */
    fun nowMs(): Long = clock()

    /** [nowMs] in epoch seconds. */
    fun nowSec(): Long = nowMs() / 1000

    /**
     * A supporter claim just stored a token: stop reading today as spent on this device now, so
     * the lossless rungs ask the relays again (with the token) instead of skipping until the
     * reset. Their next answer sets the real state, unlimited or not. Kept across a restart, so
     * a claim made offline still lifts it once the phone is back online.
     */
    fun liftSpent() {
        val a = current()?.takeIf { it.exhausted } ?: return
        liftedDay = a.resetsAtSec
        persistLift()
    }

    /** Writes [liftedDay] as it is when the write runs, so writes landing out of order still end right. */
    private fun persistLift() {
        val s = store ?: return
        scope.launch {
            ioCatching("lift write") {
                s.edit { p -> liftedDay.let { d -> if (d != 0L) p[KEY_LIFTED_DAY] = d else p.remove(KEY_LIFTED_DAY) } }
            }
        }
    }

    /** True when nothing stored is from today (UTC): the cold-start refresh should ask. */
    fun needsColdRefresh(): Boolean = _state.value.let { it == null || nowMs() >= it.resetsAtMs }

    /** Feeds one relay response's headers in; any response, any status. Junk is ignored. */
    fun onResponse(r: okhttp3.Response) = onHeaders(r.header(HEADER_COUNT), r.header(HEADER_RESET))

    /** `<used>/<limit|unlimited>` and `<epoch s>`; a missing or unreadable pair changes nothing. */
    fun onHeaders(count: String?, reset: String?) {
        val (used, limit) = parseCount(count) ?: return
        val resetsAt = reset?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: return
        record(LosslessAllowance(used, limit, resetsAt))
    }

    /**
     * Reads a `429` body. True when it is `daily_lossless_cap` (the allowance is now recorded as
     * spent); false for any other 429, which the caller handles as before.
     *
     * A body without `resetsAt` (the relays always send it) resets at the next UTC midnight,
     * where the day really turns over. `Retry-After` is not used: "now + seconds" lands a few
     * seconds off midnight, and a reset off the relays' own `resetsAt` would read their later
     * answers for the same day as another day.
     */
    fun onCapBody(body: String): Boolean {
        val o = parseObject(body) ?: return false
        if ((o["error"] as? JsonPrimitive)?.content != CAP_ERROR) return false
        val limit = (readLimit(o) ?: DEFAULT_LIMIT).takeIf { it != UNLIMITED }
        val used = (o["used"] as? JsonPrimitive)?.intOrNull ?: limit ?: DEFAULT_LIMIT
        val resetsAt = (o["resetsAt"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
            ?: nextUtcMidnightSec(nowMs())
        record(LosslessAllowance(maxOf(used, limit ?: used), limit, resetsAt))
        return true
    }

    /** Reads a `/quota` body (`{used, limit, resetsAt, supporter}`). False when unusable. */
    fun onQuotaBody(body: String): Boolean {
        val o = parseObject(body) ?: return false
        val used = (o["used"] as? JsonPrimitive)?.intOrNull ?: return false
        val resetsAt = (o["resetsAt"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: return false
        val supporter = (o["supporter"] as? JsonPrimitive)?.booleanOrNull == true
        val limit = if (supporter) null else (readLimit(o) ?: return false).takeIf { it != UNLIMITED }
        record(LosslessAllowance(used, limit, resetsAt))
        return true
    }

    /**
     * Merges [next] in: a later day replaces; the same day keeps the higher count (answers can
     * land out of order) and takes the newer limit (a supporter claim lifts it mid-day).
     */
    private fun record(next: LosslessAllowance) {
        // A relay answered: it, not a claim's lift, now says whether today is spent.
        if (liftedDay != 0L) {
            liftedDay = 0L
            persistLift()
        }
        if (next.exhausted) _spentAnswers.tryEmit(next.resetsAtSec)
        var saved: LosslessAllowance
        while (true) {
            val prev = _state.value
            saved = when {
                prev == null || next.resetsAtSec > prev.resetsAtSec -> next
                next.resetsAtSec < prev.resetsAtSec -> return // yesterday's answer, landing late
                else -> LosslessAllowance(maxOf(prev.used, next.used), next.limit, prev.resetsAtSec)
            }
            if (saved == prev) return
            if (_state.compareAndSet(prev, saved)) break
        }
        store?.let { s -> scope.launch { persist(s, saved) } }
        scope.launch { maybeNotify(saved) }
    }

    /** Raises the day's [LosslessQuotaNotice] when [a] crosses a threshold not yet announced. */
    private suspend fun maybeNotify(a: LosslessAllowance) = noticeMutex.withLock {
        // The stored "already announced today" must be in before it is judged, or an answer that
        // lands during the cold-start read would announce the day's limit a second time.
        loaded.await()
        val limit = a.limit ?: return@withLock
        if (nowMs() >= a.resetsAtMs) return@withLock
        val left = limit - a.used
        val level = when {
            left <= 0 -> 2
            limit > WARN_LEFT && left <= WARN_LEFT -> 1
            else -> return@withLock
        }
        val already = if (noticedDay == a.resetsAtSec) noticedLevel else 0
        if (level <= already) return@withLock
        noticedDay = a.resetsAtSec
        noticedLevel = level
        notices.trySend(
            if (level == 2) LosslessQuotaNotice.Spent(limit, a.resetsAtMs) else LosslessQuotaNotice.RunningLow(left, limit, a.resetsAtMs),
        )
        store?.let { s -> ioCatching("notice write") { s.edit { it[KEY_NOTICED_DAY] = a.resetsAtSec; it[KEY_NOTICED_LEVEL] = level } } }
    }

    private suspend fun load(s: DataStore<Preferences>) {
        try {
            ioCatching("read") { s.data.first() }?.let { p ->
                noticedDay = p[KEY_NOTICED_DAY] ?: 0L
                noticedLevel = p[KEY_NOTICED_LEVEL] ?: 0
                // Unless a relay answer landed during the read (which ends a lift).
                if (_state.value == null) liftedDay = p[KEY_LIFTED_DAY] ?: 0L
                val used = p[KEY_USED]
                val resetsAt = p[KEY_RESETS_AT]
                val limit = p[KEY_LIMIT]
                if (used != null && resetsAt != null && limit != null) {
                    // A relay answer that landed during the read wins over the disk copy.
                    _state.compareAndSet(null, LosslessAllowance(used, limit.takeIf { it >= 0 }, resetsAt))
                }
            }
        } finally {
            loaded.complete(Unit)
        }
    }

    private suspend fun persist(s: DataStore<Preferences>, a: LosslessAllowance) {
        ioCatching("write") {
            s.edit {
                // Re-read under the edit: a slower write must not overwrite a newer value.
                val current = _state.value ?: a
                it[KEY_USED] = current.used
                it[KEY_LIMIT] = current.limit ?: UNLIMITED
                it[KEY_RESETS_AT] = current.resetsAtSec
            }
        }
    }

    private inline fun <T> ioCatching(label: String, block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "lossless quota $label failed: ${e.javaClass.simpleName}")
        null
    }

    private fun parseObject(body: String): JsonObject? =
        runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()

    /**
     * `limit` as a positive number, or [UNLIMITED] for the word `"unlimited"` or a JSON null;
     * null when absent or unreadable.
     */
    private fun readLimit(o: JsonObject): Int? {
        val p = o["limit"] as? JsonPrimitive ?: return null
        p.intOrNull?.let { return it.takeIf { n -> n > 0 } }
        return if (p is JsonNull || p.content.equals("unlimited", ignoreCase = true)) UNLIMITED else null
    }

    companion object {
        internal const val TAG = "LosslessQuota"
        internal const val HEADER_COUNT = "X-Stash-Lossless"
        internal const val HEADER_RESET = "X-Stash-Lossless-Reset"
        internal const val CAP_ERROR = "daily_lossless_cap"
        /** The free allowance, assumed only when a cap body omits its limit. */
        internal const val DEFAULT_LIMIT = 500
        /** The "running low" heads-up fires with this many or fewer left. */
        internal const val WARN_LEFT = 50
        private const val DAY_SEC = 86_400L

        /** [readLimit]'s "unlimited", distinct from its null "unknown". */
        private const val UNLIMITED = -1

        private val KEY_USED = intPreferencesKey("used")
        private val KEY_LIMIT = intPreferencesKey("limit")
        private val KEY_RESETS_AT = longPreferencesKey("resets_at")
        private val KEY_NOTICED_DAY = longPreferencesKey("noticed_day")
        private val KEY_NOTICED_LEVEL = intPreferencesKey("noticed_level")
        private val KEY_LIFTED_DAY = longPreferencesKey("lifted_day")

        /** No DataStore, work run in place: for tests that only need the state. */
        internal fun inMemory(nowMs: () -> Long = System::currentTimeMillis): LosslessQuota =
            LosslessQuota(null, nowMs, CoroutineScope(Dispatchers.Unconfined))

        /** The UTC midnight after [nowMs], epoch seconds: when a day's allowance resets. */
        fun nextUtcMidnightSec(nowMs: Long): Long = (nowMs / 1000 / DAY_SEC + 1) * DAY_SEC

        /** [a] as of [nowMs]: once its reset has passed, a fresh day with nothing used. */
        fun asOf(a: LosslessAllowance, nowMs: Long): LosslessAllowance =
            if (nowMs < a.resetsAtMs) a else LosslessAllowance(0, a.limit, nextUtcMidnightSec(nowMs))

        /** `"412/500"` → (412, 500); `"412/unlimited"` → (412, null); junk → null. */
        internal fun parseCount(v: String?): Pair<Int, Int?>? {
            val parts = v?.trim()?.split('/')?.takeIf { it.size == 2 } ?: return null
            val used = parts[0].trim().toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val l = parts[1].trim()
            if (l.equals("unlimited", ignoreCase = true)) return used to null
            return used to (l.toIntOrNull()?.takeIf { it > 0 } ?: return null)
        }
    }
}
