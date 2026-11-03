package com.stash.core.data.community

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.stash.core.data.prefs.HomeSectionsPreference
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "CommunityHeard"

/**
 * This phone's "heard" marks (contract 2026-11-03): the Community posts it has played for 30 s, which it may vote on.
 * A mark is kept here first, at once, and that is what wakes the vote pill. Then it's sent to the Worker
 * (`PUT /v1/community/posts/{id}/heard`) under the key the vote uses, once per post.
 *
 * Sending:
 *  - Only once this phone has a key. Listening never makes one (the README: "the first time you post or vote"); the
 *    first vote does, and [beforeVote] sends that post's mark ahead of it. A vote whose mark can't go yet isn't sent.
 *  - Offline, a 5xx, rate limited, or a Worker without the route yet (404 `not_found`): the mark waits, and goes again
 *    on the next Community read ([retry]). Gone or refused (404 `gone`, the Worker's own 403): it's dropped. A Worker
 *    without the route can't want a listen, so a vote goes all the same; any other failure holds the vote back.
 *  - One request per post at a time, shared by a vote and a background round: a mark never goes out twice, and a
 *    vote never waits behind a round's other posts.
 *  - Never while Community is off.
 *
 * Kept in the key's own file, which a backup never carries: a new phone is a new Community identity, with nothing
 * heard. A mark is forgotten after [KEEP_MS], when its post is long gone.
 */
@Singleton
class CommunityHeard internal constructor(
    private val store: DataStore<Preferences>,
    private val api: CommunityApiClient,
    private val keys: CommunityKeyStore,
    private val communityOn: Flow<Boolean>,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        api: CommunityApiClient,
        keys: CommunityKeyStore,
        homeSections: HomeSectionsPreference,
    ) : this(context.communityDataStore, api, keys, homeSections.communityOn, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    /** Each mark as "id:when" (ms): post ids are 8 letters and digits. */
    private val heardPref = stringSetPreferencesKey("community_heard")

    /** The marks the Worker hasn't taken yet, the same way. */
    private val waitingPref = stringSetPreferencesKey("community_heard_waiting")

    /** One background round ([retry]) at a time. */
    private val rounds = Mutex()

    /** The request out for each post's mark, which a vote and a round share; guarded by [flightsLock]. */
    private val flights = HashMap<String, CompletableDeferred<HeardSend>>()
    private val flightsLock = Mutex()

    /** The posts heard on this phone, by id. */
    val ids: Flow<Set<String>> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[heardPref].orEmpty().mapTo(HashSet()) { it.substringBefore(':') } }
        .distinctUntilChanged()

    /** Marks [id] heard, then sends it in the background. Nothing when it's marked already. */
    suspend fun mark(id: String) {
        var added = false
        edit { prefs, now ->
            val heard = live(prefs[heardPref], now)
            if (heard.any { it.substringBefore(':') == id }) return@edit
            prefs[heardPref] = heard + "$id:$now"
            prefs[waitingPref] = live(prefs[waitingPref], now) + "$id:$now"
            added = true
        }
        if (added) retry()
    }

    /** Sends every mark still waiting, in the background. */
    fun retry() {
        scope.launch { round() }
    }

    /**
     * Before a ±1 vote on [id], once the vote has made the key: its mark goes now if it's still waiting, on its own
     * (never behind a background round's other posts). False when it couldn't go for now (offline, a timeout, busy,
     * rate limited, an edge page): then the vote isn't sent, since a Worker that wants a listen would refuse it
     * although the listen is real. The mark keeps waiting. True otherwise: sent now or before, refused (the vote gets
     * the real answer), a Worker without the route yet (404 `not_found`: it can't want a listen; the mark keeps
     * waiting for one that can), or no mark here at all (the Worker's own `heard: true`).
     */
    suspend fun beforeVote(id: String): Boolean =
        try {
            val key = keys.existingKey()
            if (key == null || !communityOn.first() || !isWaiting(id)) true else sendOne(id, key) != HeardSend.RETRY
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "heard mark before a vote not sent: ${e.javaClass.simpleName}")
            false
        }

    /**
     * A vote was answered `listen_first`. When [id]'s mark had reached the Worker (it isn't waiting), the Worker really
     * has no record of it, so it isn't heard here either: forgotten, true. A mark still waiting is kept, false: the
     * Worker never refused it, it just hasn't had it yet.
     */
    suspend fun forget(id: String): Boolean {
        var forgot = false
        edit { prefs, now ->
            if (live(prefs[waitingPref], now).any { it.substringBefore(':') == id }) return@edit
            prefs[heardPref] = live(prefs[heardPref], now).filterNot { it.substringBefore(':') == id }.toSet()
            forgot = true
        }
        return forgot
    }

    /** Sends the waiting marks under this phone's key, one post after another; see the class comment. */
    private suspend fun round() = rounds.withLock {
        try {
            if (!communityOn.first()) return@withLock
            val key = keys.existingKey() ?: return@withLock
            for (id in waitingIds()) {
                // Offline, or a Worker without the route yet: the rest would fail alike.
                when (sendOne(id, key)) {
                    HeardSend.RETRY, HeardSend.NO_ROUTE -> return@withLock
                    HeardSend.SENT, HeardSend.DROPPED -> Unit
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "heard marks not sent: ${e.javaClass.simpleName}")
        }
    }

    /**
     * [id]'s mark, sent, or the request already out for it awaited: one per post at a time, and none once the mark has
     * left the waiting list. Sent or refused, it leaves the list; otherwise (and with no route yet) it stays. The request
     * runs in [scope], so a vote's screen closing doesn't cut it short for anyone sharing it.
     */
    private suspend fun sendOne(id: String, key: String): HeardSend {
        var started: CompletableDeferred<HeardSend>? = null
        val flight = flightsLock.withLock {
            flights[id] ?: run {
                if (!isWaiting(id)) return HeardSend.SENT
                CompletableDeferred<HeardSend>().also { flights[id] = it; started = it }
            }
        }
        started?.let { mine ->
            scope.launch {
                var outcome = HeardSend.RETRY
                try {
                    outcome = api.heard(id, key)
                    if (outcome == HeardSend.SENT || outcome == HeardSend.DROPPED) edit { prefs, now ->
                        prefs[waitingPref] = live(prefs[waitingPref], now).filterNot { it.substringBefore(':') == id }.toSet()
                    }
                } finally {
                    withContext(NonCancellable) { flightsLock.withLock { flights.remove(id) } }
                    mine.complete(outcome)
                }
            }
        }
        return flight.await()
    }

    private suspend fun prefs(): Preferences =
        store.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }.first()

    private suspend fun waitingIds(): List<String> = live(prefs()[waitingPref], clock()).map { it.substringBefore(':') }

    private suspend fun isWaiting(id: String): Boolean = id in waitingIds()

    /** One edit of the marks; a file that can't be written leaves them as they were. */
    private suspend fun edit(change: (prefs: MutablePreferences, now: Long) -> Unit) {
        try {
            val now = clock()
            store.edit { change(it, now) }
        } catch (e: IOException) {
            Log.w(TAG, "heard marks not saved", e)
        }
    }

    /** [entries] without the ones older than [KEEP_MS] (or unreadable). */
    private fun live(entries: Set<String>?, now: Long): Set<String> =
        entries.orEmpty().filterTo(HashSet()) { e -> e.substringAfter(':', "").toLongOrNull()?.let { now - it < KEEP_MS } == true }

    companion object {
        /** A post lives 30 days, and a removed one a day more: a mark is never needed past that. */
        const val KEEP_MS = 31L * 86_400_000L
    }
}
