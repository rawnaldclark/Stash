package com.stash.core.data.tipjar

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stash.core.model.share.ShareConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * This month's donation goal as stashfm.app's front page shows it ("$168 of $200 this month"), read from the
 * site's public `/goal.json` (web/worker/goal-json.js): the same donations, the same target.
 */
data class DonationGoal(
    /** What came in this month, in cents (never negative). */
    val raisedCents: Long,
    /** The month's target, in cents (always more than zero). */
    val goalCents: Long,
    /** The UTC month these count, "2026-11". */
    val month: String,
)

/** `/goal.json` as the site writes it. Every field optional here, so a missing one reads as unusable, not a crash. */
@Serializable
internal data class DonationGoalJson(
    @SerialName("raised_cents") val raisedCents: Long? = null,
    @SerialName("goal_cents") val goalCents: Long? = null,
    val month: String? = null,
)

/** The goal's pure rules: reading the JSON, when to ask again, and which month counts. Tested without Android. */
internal object DonationGoals {
    private val JSON = Json { ignoreUnknownKeys = true }
    private val MONTH = Regex("""\d{4}-(0[1-9]|1[0-2])""")
    private const val DAY_MS = 86_400_000L

    /** How long after an ask that got no usable goal (offline, an error, a bad body, last month's) to ask again. */
    const val RETRY_AFTER_FAILURE_MS = 3_600_000L

    /** [raw] as a goal, or null when it isn't one: not JSON, a field missing, a negative total, no target, no month. */
    fun parse(raw: String): DonationGoal? {
        val j = try {
            JSON.decodeFromString<DonationGoalJson>(raw)
        } catch (e: IllegalArgumentException) {
            // SerializationException is one: not JSON, or a field of the wrong type.
            return null
        }
        val raised = j.raisedCents?.takeIf { it >= 0 } ?: return null
        val goal = j.goalCents?.takeIf { it > 0 } ?: return null
        val month = j.month?.takeIf { MONTH.matches(it) } ?: return null
        return DonationGoal(raised, goal, month)
    }

    /**
     * The UTC month of [ms], "2026-11": the month the site counts in. ISO digits whatever the phone's language
     * (a locale-formatted "%04d" would write Arabic-Indic digits and never match the site's month).
     */
    fun utcMonth(ms: Long): String = YearMonth.from(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC)).toString()

    /**
     * Whether to ask the site now ([nowMs]):
     *  - not when this UTC day already got this month's goal ([lastGoodMs]): at most one good answer a day;
     *  - not within [RETRY_AFTER_FAILURE_MS] of an ask that got none ([lastFailedMs]), so an offline phone or a
     *    site that's down is asked again an hour later, not the next day, and not on every Settings visit;
     *  - otherwise yes. A clock set back before either stamp asks again rather than waiting it out.
     */
    fun fetchDue(lastGoodMs: Long, lastFailedMs: Long, nowMs: Long): Boolean {
        val goodToday = lastGoodMs in 1..nowMs && Math.floorDiv(lastGoodMs, DAY_MS) == Math.floorDiv(nowMs, DAY_MS)
        if (goodToday) return false
        val backingOff = lastFailedMs in 1..nowMs && nowMs - lastFailedMs < RETRY_AFTER_FAILURE_MS
        return !backingOff
    }

    /** [goal] when it counts this UTC month at [nowMs]; last month's total is no answer for "this month". */
    fun forMonth(goal: DonationGoal?, nowMs: Long): DonationGoal? = goal?.takeIf { it.month == utcMonth(nowMs) }
}

private val Context.donationGoalStore: DataStore<Preferences> by preferencesDataStore(
    name = "donation_goal",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * The monthly donation goal for Settings' Support card: fetched from stashfm.app when [DonationGoals.fetchDue] says
 * so ([refreshIfDue]: one good answer a UTC day, an hour's wait after an ask that got none), kept in its own small
 * DataStore so the card has it straight away next time, and shown only for the month it counts ([goal]). Best
 * effort throughout: offline, a 503, or a reply that isn't this month's goal leaves the last good one (or none),
 * and the card simply goes without its goal line.
 *
 * [lock] guards only the quick work (reading the stored state, deciding, stamping), never the network: [goal]
 * answers from the stored goal at once even while an ask is waiting on a slow site.
 */
@Singleton
class DonationGoalRepository(
    private val store: DataStore<Preferences>?,
    httpClient: OkHttpClient,
    private val url: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        httpClient: OkHttpClient,
    ) : this(context.donationGoalStore, httpClient, GOAL_URL)

    /** A short wait: it's one line on a card, and nobody is waiting on it. */
    private val client: OkHttpClient = httpClient.newBuilder().callTimeout(TIMEOUT_S, TimeUnit.SECONDS).build()

    private val payloadKey = stringPreferencesKey("payload_json")
    private val goodAtKey = longPreferencesKey("good_at_ms")
    private val failedAtKey = longPreferencesKey("failed_at_ms")

    private val lock = Mutex()
    private val latest = MutableStateFlow<DonationGoal?>(null)
    private var lastGoodMs = 0L
    private var lastFailedMs = 0L
    private var loaded = false

    /** One ask at a time: a second Settings opening while one is out doesn't send another. */
    private val inFlight = AtomicBoolean(false)

    /** This month's goal, or null (never read, unreadable, or a month that has passed). The stored one first. */
    val goal: Flow<DonationGoal?> = flow {
        lock.withLock { loadOnce() }
        emitAll(latest.map { DonationGoals.forMonth(it, clock()) })
    }.distinctUntilChanged()

    /**
     * Asks stashfm.app for the goal when it's due ([DonationGoals.fetchDue]). The day is stamped only by a usable
     * answer for this month; anything else (offline, an error, a bad body, last month's goal) stamps a failure, asked
     * again an hour later. A cancelled ask stamps nothing. Never throws except to cancel. True when it asked.
     */
    suspend fun refreshIfDue(): Boolean {
        val now = lock.withLock {
            loadOnce()
            val t = clock()
            if (!DonationGoals.fetchDue(lastGoodMs, lastFailedMs, t)) return false
            if (!inFlight.compareAndSet(false, true)) return false
            t
        }
        try {
            val raw = fetch()
            val usable = raw?.let(DonationGoals::parse)?.let { DonationGoals.forMonth(it, now) }
            lock.withLock {
                if (usable != null && raw != null) {
                    latest.value = usable
                    lastGoodMs = now
                    lastFailedMs = 0L
                    persist {
                        it[payloadKey] = raw
                        it[goodAtKey] = now
                        it.remove(failedAtKey)
                    }
                } else {
                    if (raw != null) Log.w(TAG, "goal.json isn't this month's goal")
                    lastFailedMs = now
                    persist { it[failedAtKey] = now }
                }
            }
        } finally {
            inFlight.set(false)
        }
        return true
    }

    /** The stored goal and stamps, once per process. Called under [lock]; a local read, never the network. */
    private suspend fun loadOnce() {
        if (loaded) return
        loaded = true
        val prefs = try {
            store?.data?.first()
        } catch (e: IOException) {
            Log.w(TAG, "goal cache unreadable: ${e.message}")
            null
        } ?: return
        lastGoodMs = prefs[goodAtKey] ?: 0L
        lastFailedMs = prefs[failedAtKey] ?: 0L
        latest.value = prefs[payloadKey]?.let(DonationGoals::parse)
    }

    private suspend fun persist(change: (MutablePreferences) -> Unit) {
        try {
            store?.edit { change(it) }
        } catch (e: IOException) {
            Log.w(TAG, "goal cache not written: ${e.message}")
        }
    }

    /**
     * The body of a 2xx from [url], or null. The call is cancelled with the coroutine (an enqueued call, not a
     * blocked thread), so leaving Settings mid-ask ends it at once.
     */
    private suspend fun fetch(): String? {
        val call = try {
            client.newCall(Request.Builder().url(url).header("Accept", "application/json").build())
        } catch (e: IllegalArgumentException) {
            // A malformed or empty URL (a fork's build): nothing to ask.
            Log.w(TAG, "goal.json bad url: ${e.message}")
            return null
        }
        return try {
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)

                    override fun onResponse(call: Call, response: Response) {
                        val body = try {
                            response.use { r ->
                                if (r.isSuccessful) r.body?.string() else null.also { Log.w(TAG, "goal.json HTTP ${r.code}") }
                            }
                        } catch (e: IOException) {
                            cont.resumeWithException(e)
                            return
                        }
                        cont.resume(body)
                    }
                })
            }
        } catch (e: IOException) {
            Log.w(TAG, "goal.json unreachable: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "DonationGoal"
        private const val TIMEOUT_S = 10L

        /** The site's goal, the same number its front page shows. */
        const val GOAL_URL = "${ShareConfig.BASE_URL}/goal.json"
    }
}
