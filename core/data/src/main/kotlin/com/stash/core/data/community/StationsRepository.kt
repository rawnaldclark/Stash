package com.stash.core.data.community

import com.stash.core.data.prefs.HomeSectionsPreference
import com.stash.core.model.community.FollowState
import com.stash.core.model.community.LiveSession
import com.stash.core.model.community.Station
import com.stash.core.model.community.StationPage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sessions' stations (infra/share-worker/README.md "Contract for the app"): Live now, the stations this phone follows,
 * a station's page, your own station, follows and votes. Every call returns a [CommunityResult] and never throws. While
 * Community is off nothing is sent: `Rejected("off")`. Reads carry the key only once this phone has one; making,
 * changing or deleting a station, a follow and a vote make one.
 */
@Singleton
class StationsRepository @Inject constructor(
    private val api: StationsApiClient,
    private val keys: CommunityKeyStore,
    private val homeSections: HomeSectionsPreference,
    private val heard: StationHeard,
) {
    private val _sessionsOn = MutableStateFlow<Boolean?>(null)

    /**
     * What the Worker has said about its Sessions switch: null until it has said, false after `sessions_off` (or a
     * Worker without the routes), true after any other answer from them. Nothing about Sessions shows until it's true.
     */
    val sessionsOn: StateFlow<Boolean?> = _sessionsOn.asStateFlow()

    private val _revision = MutableStateFlow(0)

    /** Goes up after a station is made, changed or deleted, and after a follow, so lists on screen reload. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** The stations this phone has heard (2 minutes in their sessions), set before the Worker knows. */
    val heardIds: Flow<Set<String>> get() = heard.ids

    private val asking = Mutex()

    /** Asks the Worker whether Sessions is on (`GET live?limit=1`), while that's still unknown. Again after a failure. */
    suspend fun ensureKnown() {
        if (_sessionsOn.value != null) return
        asking.withLock { if (_sessionsOn.value == null) live(1) }
    }

    /** Live now, best first. Heard marks still waiting go again meanwhile. */
    suspend fun live(limit: Int): CommunityResult<List<LiveSession>> = whileOn {
        heard.retry()
        api.live(limit, keys.existingKey()).learned()
    }

    /** The stations this phone follows; none, without asking, before it has a key. */
    suspend fun following(): CommunityResult<List<Station>> = whileOn {
        keys.existingKey()?.let { api.following(it).learned() } ?: CommunityResult.Ok(emptyList())
    }

    /** Stations going live on their own within a day, soonest first (the Sessions screen's Upcoming). */
    suspend fun upcoming(limit: Int = 10): CommunityResult<List<Station>> = whileOn {
        api.upcoming(limit, keys.existingKey()).learned()
    }

    suspend fun station(id: String): CommunityResult<StationPage> = whileOn {
        heard.retry()
        api.station(id, keys.existingKey()).learned()
    }

    /** The code of the session station [id] is on the air in, or null: not live, gone, offline, or Sessions off. */
    suspend fun liveRoom(id: String): String? = (station(id) as? CommunityResult.Ok)?.value?.live?.room

    /** This phone's own station; none, without asking, before it has a key. */
    suspend fun mine(): CommunityResult<Station?> = whileOn {
        keys.existingKey()?.let { api.mine(it).learned() } ?: CommunityResult.Ok(null)
    }

    /** Makes this phone's station: its id. `Rejected("have_station")` when it has one. */
    suspend fun create(edit: StationEdit): CommunityResult<String> = whileOn {
        api.create(edit, keys.key()).learned().changed()
    }

    suspend fun update(id: String, edit: StationEdit): CommunityResult<Station> = whileOn {
        api.update(id, edit, keys.key()).learned().changed()
    }

    /** A station that's already gone counts as deleted: it's what the person wanted. */
    suspend fun delete(id: String): CommunityResult<Unit> = whileOn {
        api.delete(id, keys.key()).learned()
            .let { if (it is CommunityResult.Rejected && it.code == "gone") CommunityResult.Ok(Unit) else it }
            .changed()
    }

    suspend fun follow(id: String, on: Boolean): CommunityResult<FollowState> = whileOn {
        api.follow(id, on, keys.key()).learned().changed()
    }

    /**
     * As a post's vote ([CommunityRepository.vote]): a ±1 sends the station's waiting heard mark first, and when that
     * can't go for now the vote isn't sent either ([CommunityRepository.VOTE_NOT_NOW]). A `listen_first` answer forgets a
     * mark the Worker had taken; one it hadn't is kept, and that's VOTE_NOT_NOW too.
     */
    suspend fun vote(id: String, value: Int): CommunityResult<VoteCounts> = whileOn {
        val key = keys.key()
        if (value != 0 && !heard.beforeVote(id)) return@whileOn CommunityResult.Rejected(CommunityRepository.VOTE_NOT_NOW)
        val r = api.vote(id, value, key).learned()
        if (r is CommunityResult.Rejected && r.code == CommunityRepository.LISTEN_FIRST && !heard.forget(id)) {
            CommunityResult.Rejected(CommunityRepository.VOTE_NOT_NOW)
        } else {
            r
        }
    }

    /** Community off: no request leaves the phone, whichever screen asks. */
    private suspend fun <T> whileOn(call: suspend () -> CommunityResult<T>): CommunityResult<T> =
        if (homeSections.communityOn.first()) call() else CommunityResult.Rejected("off")

    /** What the Worker's answer says about its switch. Offline, rate limited or an edge page says nothing. */
    private fun <T> CommunityResult<T>.learned(): CommunityResult<T> = also { r ->
        when {
            r is CommunityResult.Ok -> _sessionsOn.value = true
            r is CommunityResult.Rejected && r.code in OFF -> _sessionsOn.value = false
            r is CommunityResult.Rejected && r.code !in SAYS_NOTHING -> _sessionsOn.value = true
        }
    }

    private fun <T> CommunityResult<T>.changed(): CommunityResult<T> = also {
        if (it is CommunityResult.Ok) _revision.update { n -> n + 1 }
    }

    private companion object {
        /** The switch is off, or the Worker has no Sessions routes at all (an older one answers any unknown path so). */
        val OFF = setOf(CommunityHttp.SESSIONS_OFF, "not_found")
        val SAYS_NOTHING = setOf("rate_limited", "unknown")
    }
}
