package com.stash.core.data.community

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.stash.core.data.prefs.HomeSectionsPreference
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Two minutes in a station's sessions, in total, before this phone may vote on it (Sessions; the Worker's `heard`).
 * The engine reports a listener's time as it goes ([listened]). At [NEEDED_MS] the station is marked heard here at once
 * ([ids], which wakes the vote pill) and the mark goes to the Worker under the vote's key, the way a post's does
 * ([CommunityHeard]: only once this phone has a key, never while Community is off). All of it lives in the key's own
 * file, which a backup never carries. Time short of two minutes is kept [KEEP_MS] after its last listen, for at most
 * [MAX_STATIONS] stations.
 */
@Singleton
class StationHeard internal constructor(
    private val store: DataStore<Preferences>,
    private val marks: CommunityHeard,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        api: StationsApiClient,
        keys: CommunityKeyStore,
        homeSections: HomeSectionsPreference,
    ) : this(
        context.communityDataStore,
        CommunityHeard(
            context.communityDataStore, api::heard, keys, homeSections.communityOn,
            CoroutineScope(SupervisorJob() + Dispatchers.IO), heardName = "station_heard", waitingName = "station_heard_waiting",
        ),
    )

    /** Each station's time so far, "id:ms:lastListen", until it reaches [NEEDED_MS]. */
    private val listenedPref = stringSetPreferencesKey("station_listened")

    /** The stations heard on this phone, by id. */
    val ids: Flow<Set<String>> get() = marks.ids

    /** [ms] more of this phone's listening in station [id]'s sessions. Never throws. */
    suspend fun listened(id: String, ms: Long) {
        if (ms <= 0) return
        try {
            if (id in marks.ids.first()) return
            var reached = false
            store.edit { prefs ->
                val now = clock()
                val all = prefs[listenedPref].orEmpty().mapNotNull(Time::parse).filter { now - it.lastAt < KEEP_MS }
                val total = (all.firstOrNull { it.id == id }?.ms ?: 0L) + ms
                reached = total >= NEEDED_MS
                val others = all.filter { it.id != id }
                val kept = if (reached) others else others + Time(id, total, now)
                prefs[listenedPref] = kept.sortedByDescending { it.lastAt }.take(MAX_STATIONS).mapTo(HashSet()) { it.text }
            }
            if (reached) marks.mark(id)
        } catch (e: IOException) {
            Log.w(TAG, "station listening time not saved", e)
        }
    }

    /** Sends every mark still waiting, in the background. */
    fun retry() = marks.retry()

    /** Before a ±1 vote on station [id]: see [CommunityHeard.beforeVote]. */
    suspend fun beforeVote(id: String): Boolean = marks.beforeVote(id)

    /** A vote was answered `listen_first`: see [CommunityHeard.forget]. */
    suspend fun forget(id: String): Boolean = marks.forget(id)

    private data class Time(val id: String, val ms: Long, val lastAt: Long) {
        val text: String get() = "$id:$ms:$lastAt"

        companion object {
            fun parse(text: String): Time? {
                val parts = text.split(':')
                if (parts.size != 3) return null
                return Time(parts[0], parts[1].toLongOrNull() ?: return null, parts[2].toLongOrNull() ?: return null)
            }
        }
    }

    companion object {
        private const val TAG = "StationHeard"

        /** The Worker's rule: 2 minutes in a station's sessions, in total, before a vote. */
        const val NEEDED_MS = 120_000L
        const val KEEP_MS = CommunityHeard.KEEP_MS
        const val MAX_STATIONS = 200
    }
}
