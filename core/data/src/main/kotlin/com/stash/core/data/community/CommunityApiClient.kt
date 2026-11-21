package com.stash.core.data.community

import com.stash.core.data.share.ShareJson
import com.stash.core.model.community.CommunityMe
import com.stash.core.model.community.CommunityPost
import com.stash.core.model.share.ShareConfig
import com.stash.core.model.share.SharedTrack
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** The result of one Community request (spec 2026-09-26 §3). */
sealed interface CommunityResult<out T> {
    data class Ok<T>(val value: T) : CommunityResult<T>

    /**
     * A 4xx: [code] is the Worker's `error` ("daily_limit", "gone", …), or "unknown" when the body had none
     * ("rate_limited" for a 429). Or one the app makes before sending: "off" (Community is turned off) or
     * "bad_request" (a blank name).
     */
    data class Rejected(val code: String) : CommunityResult<Nothing>

    /** Offline, a timeout, a 5xx (the Worker's catch-all is 503 `unavailable`), a redirect, or a 2xx it can't read: worth trying again. */
    data class Failed(val reason: String?) : CommunityResult<Nothing>
}

/** `POST /v1/community/posts`'s body. [body] is `{covers, tracks}` for a playlist or mix, `{track}` for a song. */
@Serializable
data class NewPost(val kind: String, val name: String, val title: String, val body: Body) {
    @Serializable
    data class Body(val covers: List<String>? = null, val tracks: List<SharedTrack>? = null, val track: SharedTrack? = null)
}

/** What became of a "heard" mark sent to the Worker ([CommunityApiClient.heard]). */
enum class HeardSend {
    /** Recorded (204), or recorded before: done. */
    SENT,

    /** The post is gone (404 `gone`), or the Worker refuses it for good (its own 403, e.g. your own post): never send it again. */
    DROPPED,

    /** Try again later: offline, a timeout, a 5xx, rate limited, a 403 that isn't the Worker's, or any other answer. */
    RETRY,

    /**
     * 404 `not_found`: a Worker without the route yet (it answers that for any path it doesn't know), from before the
     * heard contract. It can't want a listen before a vote either, so a vote goes; the mark waits for a Worker that has it.
     */
    NO_ROUTE,
}

/** A vote's answer: the post's counted votes and this phone's vote. */
@Serializable
data class VoteCounts(val up: Int, val down: Int, val myVote: Int)

/** HTTP client for the Worker's `/v1/community` posts. Unlike `shareCall`, it keeps a 4xx's `error` code ([CommunityHttp]). */
@Singleton
class CommunityApiClient @Inject constructor(okHttpClient: OkHttpClient) {
    private val community = CommunityHttp(okHttpClient)

    /** The hosts tried in turn. Test seam; off the constructor because Hilt rejects @Inject with default params. */
    internal var baseUrls: List<String> = ShareConfig.API_BASE_URLS

    @Serializable private data class Posts(val posts: List<CommunityPost>)
    @Serializable private data class One(val post: CommunityPost)
    @Serializable private data class Created(val id: String)
    @Serializable private data class Vote(val value: Int)

    suspend fun feed(limit: Int, key: String?): CommunityResult<List<CommunityPost>> =
        call("/v1/community/feed?limit=$limit", key) { ShareJson.decodeFromString(Posts.serializer(), it).posts }

    suspend fun mine(key: String): CommunityResult<List<CommunityPost>> =
        call("/v1/community/mine", key) { ShareJson.decodeFromString(Posts.serializer(), it).posts }

    suspend fun me(key: String): CommunityResult<CommunityMe> =
        call("/v1/community/me", key) { ShareJson.decodeFromString(CommunityMe.serializer(), it) }

    suspend fun open(id: String, key: String?): CommunityResult<CommunityPost> =
        call("/v1/community/posts/$id", key) { ShareJson.decodeFromString(One.serializer(), it).post }

    suspend fun create(post: NewPost, key: String): CommunityResult<String> {
        val body = ShareJson.encodeToString(NewPost.serializer(), post).toRequestBody(JSON)
        return call("/v1/community/posts", key, { this.post(body) }) { ShareJson.decodeFromString(Created.serializer(), it).id }
    }

    suspend fun vote(id: String, value: Int, key: String): CommunityResult<VoteCounts> =
        call("/v1/community/posts/$id/vote", key, { put(ShareJson.encodeToString(Vote.serializer(), Vote(value)).toRequestBody(JSON)) }) {
            ShareJson.decodeFromString(VoteCounts.serializer(), it)
        }

    /**
     * `PUT /v1/community/posts/{id}/heard`: this key played the post for 30 s (contract 2026-11-03). Never throws.
     * Dropped only on the Worker's own refusal: 404 `gone`, or a 403 with its JSON code (`own_post`). A 403 without one
     * (a Cloudflare challenge page) is the edge, not the Worker: tried again later.
     */
    suspend fun heard(id: String, key: String): HeardSend =
        community.heard(baseUrls, "/v1/community/posts/$id/heard", key)

    suspend fun takeDown(id: String, key: String): CommunityResult<Unit> =
        call("/v1/community/posts/$id", key, { delete() }) { }

    /** [method] sets anything beyond a GET; the key goes on every request that has one. */
    private suspend fun <T> call(
        path: String,
        key: String?,
        method: Request.Builder.() -> Unit = {},
        parse: (String) -> T,
    ): CommunityResult<T> = community.call(baseUrls, path, key, method, parse)

    companion object {
        const val KEY_HEADER = "X-Stash-Community-Key"
        private val JSON = "application/json".toMediaType()
    }
}
