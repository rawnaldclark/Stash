package com.stash.core.data.community

import com.stash.core.data.share.ShareJson
import com.stash.core.data.share.cut
import com.stash.core.model.community.FollowState
import com.stash.core.model.community.LiveSession
import com.stash.core.model.community.Station
import com.stash.core.model.community.StationPage
import com.stash.core.model.share.ShareConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * A station's fields to set (`POST stations`, `PATCH stations/{id}`): only those given are sent. In an edit, a blank
 * [hostName] or [vibe] clears it; the name can't be cleared. Cut to the Worker's limits (UTF-16 units, after trimming):
 * name 1 to 40, hostName up to 40, vibe up to 60.
 */
data class StationEdit(val name: String? = null, val hostName: String? = null, val vibe: String? = null) {
    internal fun body(): RequestBody = buildJsonObject {
        name?.let { put("name", it.trim().cut(40)) }
        hostName?.let { put("hostName", it.trim().cut(40)) }
        vibe?.let { put("vibe", it.trim().cut(60)) }
    }.toString().toRequestBody(CommunityHttp.JSON)
}

/**
 * The Worker's Sessions routes under `/v1/community/` (infra/share-worker/README.md "Contract for the app"). Answers
 * as [CommunityResult]; while the Worker's switch is off, every one is `Rejected("sessions_off")`.
 */
@Singleton
class StationsApiClient @Inject constructor(okHttpClient: OkHttpClient) {
    private val community = CommunityHttp(okHttpClient)

    /** The hosts tried in turn. Test seam; off the constructor because Hilt rejects @Inject with default params. */
    internal var baseUrls: List<String> = ShareConfig.API_BASE_URLS

    @Serializable private data class Sessions(val sessions: List<LiveSession>)
    @Serializable private data class Stations(val stations: List<Station>)
    @Serializable private data class Mine(val station: Station? = null)
    @Serializable private data class One(val station: Station)
    @Serializable private data class Created(val id: String)
    @Serializable private data class Vote(val value: Int)
    @Serializable private data class Follow(val on: Boolean)

    /** Live now, best first: at most [limit] (1 to 50). With [key], each station says what this phone has done. */
    suspend fun live(limit: Int, key: String?): CommunityResult<List<LiveSession>> =
        call("live?limit=${limit.coerceIn(1, 50)}", key) { ShareJson.decodeFromString(Sessions.serializer(), it).sessions }

    /** The stations this phone follows: live ones first (most listeners first), then the soonest on the air. */
    suspend fun following(key: String): CommunityResult<List<Station>> =
        call("stations/following", key) { ShareJson.decodeFromString(Stations.serializer(), it).stations }

    /** A station, with its session while it's on the air. `Rejected("gone")`: missing, removed, or hidden from this phone. */
    suspend fun station(id: String, key: String?): CommunityResult<StationPage> =
        call("stations/$id", key) { ShareJson.decodeFromString(StationPage.serializer(), it) }

    /** This phone's own station, or null without one. */
    suspend fun mine(key: String): CommunityResult<Station?> =
        call("stations/mine", key) { ShareJson.decodeFromString(Mine.serializer(), it).station }

    /** Makes this phone's station: its id. `Rejected("have_station")`: it has one (one a phone). */
    suspend fun create(edit: StationEdit, key: String): CommunityResult<String> =
        call("stations", key, { post(edit.body()) }) { ShareJson.decodeFromString(Created.serializer(), it).id }

    /** Changes the fields [edit] gives: the station as it is now. */
    suspend fun update(id: String, edit: StationEdit, key: String): CommunityResult<Station> =
        call("stations/$id", key, { patch(edit.body()) }) { ShareJson.decodeFromString(One.serializer(), it).station }

    /** Deletes this phone's station, with its votes, heard marks, follows and its session's row. */
    suspend fun delete(id: String, key: String): CommunityResult<Unit> = call("stations/$id", key, { delete() }) { }

    /** This phone has listened 2 minutes in the station's sessions, in total. Never throws. */
    suspend fun heard(id: String, key: String): HeardSend = community.heard(baseUrls, "$BASE/stations/$id/heard", key)

    /** [value] -1, 0 or 1. A ±1 needs the heard mark first (`Rejected("listen_first")`); 0 always works. */
    suspend fun vote(id: String, value: Int, key: String): CommunityResult<VoteCounts> =
        call("stations/$id/vote", key, { put(ShareJson.encodeToString(Vote.serializer(), Vote(value)).toRequestBody(CommunityHttp.JSON)) }) {
            ShareJson.decodeFromString(VoteCounts.serializer(), it)
        }

    /** Follows ([on]) or stops following. `Rejected("too_many")` at 200 followed. */
    suspend fun follow(id: String, on: Boolean, key: String): CommunityResult<FollowState> =
        call("stations/$id/follow", key, { put(ShareJson.encodeToString(Follow.serializer(), Follow(on)).toRequestBody(CommunityHttp.JSON)) }) {
            ShareJson.decodeFromString(FollowState.serializer(), it)
        }

    private suspend fun <T> call(
        path: String,
        key: String?,
        method: okhttp3.Request.Builder.() -> Unit = {},
        parse: (String) -> T,
    ): CommunityResult<T> = community.call(baseUrls, "$BASE/$path", key, method, parse)

    private companion object {
        const val BASE = "/v1/community"
    }
}
