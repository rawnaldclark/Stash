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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "CommunityHeard"

/**
 * This phone's "heard" marks (contract 2026-11-03): the Community posts it has played for 30 s, which it may vote on.
 * A mark is kept here first, at once, and that is what wakes the vote pill. Then it's sent to the Worker
 * (`PUT /v1/community/posts/{id}/heard`) under the key the vote uses, once per post.
 *
 * Sending:
 *  - Only once this phone has a key. Listening never makes one (the README: "the first time you post or vote"); the
 *    first vote does, and [beforeVote] sends that post's mark ahead of it.
 *  - Offline, a 5xx, rate limited, or a Worker without the route yet (404 `not_found`): the mark waits, and goes again
 *    on the next Community read ([retry]). Gone or refused (404 `gone`, any 403): it's dropped.
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

    /** One send at a time, so a mark never goes out twice at once. */
    private val sending = Mutex()

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
        scope.launch { send(only = null) }
    }

    /** Before a ±1 vote on [id], once the vote has made the key: its mark goes now if it's still waiting. */
    suspend fun beforeVote(id: String) = send(only = id)

    /** A vote was answered `listen_first`: the Worker has no mark for [id], so it isn't heard here either. */
    suspend fun forget(id: String) = edit { prefs, now ->
        prefs[heardPref] = live(prefs[heardPref], now).filterNot { it.substringBefore(':') == id }.toSet()
        prefs[waitingPref] = live(prefs[waitingPref], now).filterNot { it.substringBefore(':') == id }.toSet()
    }

    /** Sends the waiting marks ([only] that post's, when given) under this phone's key; see the class comment. */
    private suspend fun send(only: String?) = sending.withLock {
        try {
            if (!communityOn.first()) return@withLock
            val key = keys.existingKey() ?: return@withLock
            val waiting = store.data.first()[waitingPref].orEmpty()
                .map { it.substringBefore(':') }
                .filter { only == null || it == only }
            for (id in waiting) {
                when (api.heard(id, key)) {
                    HeardSend.SENT, HeardSend.DROPPED -> edit { prefs, now ->
                        prefs[waitingPref] = live(prefs[waitingPref], now).filterNot { it.substringBefore(':') == id }.toSet()
                    }
                    // Offline, or a Worker without the route yet: the rest would fail alike.
                    HeardSend.RETRY -> return@withLock
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "heard marks not sent: ${e.javaClass.simpleName}")
        }
    }

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
