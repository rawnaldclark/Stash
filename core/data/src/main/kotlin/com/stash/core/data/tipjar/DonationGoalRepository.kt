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
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

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

    /** The UTC month of [ms], "2026-11": the month the site counts in. */
    fun utcMonth(ms: Long): String {
        val date = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
        return "%04d-%02d".format(date.year, date.monthValue)
    }

    /**
     * Whether to ask the site now: never asked, or the last ask ([lastAttemptMs], failed ones too) was on another UTC
     * day. At most once a day, and a new month (a new day) is always asked for.
     */
    fun fetchDue(lastAttemptMs: Long, nowMs: Long): Boolean =
        lastAttemptMs <= 0L || Math.floorDiv(lastAttemptMs, DAY_MS) != Math.floorDiv(nowMs, DAY_MS)

    /** [goal] when it counts this UTC month at [nowMs]; last month's total is no answer for "this month". */
    fun forMonth(goal: DonationGoal?, nowMs: Long): DonationGoal? = goal?.takeIf { it.month == utcMonth(nowMs) }
}

private val Context.donationGoalStore: DataStore<Preferences> by preferencesDataStore(
    name = "donation_goal",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * The monthly donation goal for Settings' Support card: fetched from stashfm.app at most once a (UTC) day
 * ([refreshIfDue]), kept in its own small DataStore so the card has it straight away next time, and shown only
 * for the month it counts ([goal]). Best effort throughout: offline, a 503, or a reply that isn't a goal leaves the
 * last good one (or none), and the card simply goes without its goal line.
 */
@Singleton
class DonationGoalRepository(
    private val store: DataStore<Preferences>?,
    httpClient: OkHttpClient,
    private val url: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        httpClient: OkHttpClient,
    ) : this(context.donationGoalStore, httpClient, GOAL_URL)

    /** A short wait: it's one line on a card, and nobody is waiting on it. */
    private val client: OkHttpClient = httpClient.newBuilder().callTimeout(TIMEOUT_S, TimeUnit.SECONDS).build()

    private val payloadKey = stringPreferencesKey("payload_json")
    private val attemptKey = longPreferencesKey("attempted_at_ms")

    private val lock = Mutex()
    private val latest = MutableStateFlow<DonationGoal?>(null)
    private var lastAttemptMs = 0L
    private var loaded = false

    /** This month's goal, or null (never read, unreadable, or a month that has passed). The stored one first. */
    val goal: Flow<DonationGoal?> = flow {
        lock.withLock { loadOnce() }
        emitAll(latest.map { DonationGoals.forMonth(it, clock()) })
    }.distinctUntilChanged()

    /**
     * Asks stashfm.app for the goal if this UTC day hasn't asked yet ([DonationGoals.fetchDue]); a failed ask counts
     * too, so a phone offline all day asks once. Never throws. True when it asked.
     */
    suspend fun refreshIfDue(): Boolean = lock.withLock {
        loadOnce()
        val now = clock()
        if (!DonationGoals.fetchDue(lastAttemptMs, now)) return@withLock false
        lastAttemptMs = now
        persist { it[attemptKey] = now }
        val raw = fetch() ?: return@withLock true
        val parsed = DonationGoals.parse(raw)
        if (parsed == null) {
            Log.w(TAG, "goal.json isn't a goal")
            return@withLock true
        }
        latest.value = parsed
        persist { it[payloadKey] = raw }
        true
    }

    /** The stored goal and last ask, once per process. Called under [lock]. */
    private suspend fun loadOnce() {
        if (loaded) return
        loaded = true
        val prefs = try {
            store?.data?.first()
        } catch (e: IOException) {
            Log.w(TAG, "goal cache unreadable: ${e.message}")
            null
        } ?: return
        lastAttemptMs = prefs[attemptKey] ?: 0L
        latest.value = prefs[payloadKey]?.let(DonationGoals::parse)
    }

    private suspend fun persist(change: (MutablePreferences) -> Unit) {
        try {
            store?.edit { change(it) }
        } catch (e: IOException) {
            Log.w(TAG, "goal cache not written: ${e.message}")
        }
    }

    /** The body of a 2xx from [url], or null. */
    private suspend fun fetch(): String? = withContext(io) {
        try {
            val request = Request.Builder().url(url).header("Accept", "application/json").build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "goal.json HTTP ${resp.code}")
                    null
                } else {
                    resp.body?.string()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "goal.json unreachable: ${e.message}")
            null
        } catch (e: IllegalArgumentException) {
            // A malformed URL (a fork's build): nothing to ask.
            Log.w(TAG, "goal.json bad url: ${e.message}")
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
