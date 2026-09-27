package com.stash.core.data.community

import com.stash.core.data.share.ShareJson
import com.stash.core.model.community.CommunityMe
import com.stash.core.model.community.CommunityPost
import com.stash.core.model.share.ShareConfig
import com.stash.core.model.share.SharedTrack
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

/** A vote's answer: the post's counted votes and this phone's vote. */
@Serializable
data class VoteCounts(val up: Int, val down: Int, val myVote: Int)

/** HTTP client for the Worker's `/v1/community` routes. Unlike `shareCall`, it keeps a 4xx's `error` code. */
@Singleton
class CommunityApiClient @Inject constructor(okHttpClient: OkHttpClient) {
    // Bounds the whole call (every address, TLS, the answer): a caller's withTimeout can't cut a blocking execute() short.
    // No redirects: OkHttp would carry X-Stash-Community-Key to another host.
    private val http = okHttpClient.newBuilder().callTimeout(15, TimeUnit.SECONDS).followRedirects(false).build()

    /** Test seam; off the constructor because Hilt rejects @Inject with default params. */
    internal var baseUrl: String = ShareConfig.BASE_URL

    @Serializable private data class Posts(val posts: List<CommunityPost>)
    @Serializable private data class One(val post: CommunityPost)
    @Serializable private data class Created(val id: String)
    @Serializable private data class Vote(val value: Int)
    @Serializable private data class ErrorBody(val error: String? = null)

    suspend fun feed(limit: Int, key: String?): CommunityResult<List<CommunityPost>> =
        call(request("/v1/community/feed?limit=$limit", key)) { ShareJson.decodeFromString(Posts.serializer(), it).posts }

    suspend fun mine(key: String): CommunityResult<List<CommunityPost>> =
        call(request("/v1/community/mine", key)) { ShareJson.decodeFromString(Posts.serializer(), it).posts }

    suspend fun me(key: String): CommunityResult<CommunityMe> =
        call(request("/v1/community/me", key)) { ShareJson.decodeFromString(CommunityMe.serializer(), it) }

    suspend fun open(id: String, key: String?): CommunityResult<CommunityPost> =
        call(request("/v1/community/posts/$id", key)) { ShareJson.decodeFromString(One.serializer(), it).post }

    suspend fun create(post: NewPost, key: String): CommunityResult<String> =
        call(request("/v1/community/posts", key).post(ShareJson.encodeToString(NewPost.serializer(), post).toRequestBody(JSON))) {
            ShareJson.decodeFromString(Created.serializer(), it).id
        }

    suspend fun vote(id: String, value: Int, key: String): CommunityResult<VoteCounts> =
        call(request("/v1/community/posts/$id/vote", key).put(ShareJson.encodeToString(Vote.serializer(), Vote(value)).toRequestBody(JSON))) {
            ShareJson.decodeFromString(VoteCounts.serializer(), it)
        }

    suspend fun takeDown(id: String, key: String): CommunityResult<Unit> =
        call(request("/v1/community/posts/$id", key).delete()) { }

    private fun request(path: String, key: String?): Request.Builder =
        Request.Builder().url(baseUrl + path).apply { if (key != null) header(KEY_HEADER, key) }

    private suspend fun <T> call(builder: Request.Builder, parse: (String) -> T): CommunityResult<T> =
        withContext(Dispatchers.IO) {
            try {
                http.newCall(builder.build()).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    when (r.code) {
                        in 200..299 -> CommunityResult.Ok(parse(text))
                        in 400..499 -> CommunityResult.Rejected(errorCode(r.code, text))
                        else -> CommunityResult.Failed("HTTP ${r.code}")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CommunityResult.Failed(e.message)
            }
        }

    /** The Worker's `error`. A 429 without one is Cloudflare's own rate-limit page. */
    private fun errorCode(status: Int, text: String): String =
        runCatching { ShareJson.decodeFromString(ErrorBody.serializer(), text).error }.getOrNull()
            ?: if (status == 429) "rate_limited" else "unknown"

    companion object {
        const val KEY_HEADER = "X-Stash-Community-Key"
        private val JSON = "application/json".toMediaType()
    }
}
