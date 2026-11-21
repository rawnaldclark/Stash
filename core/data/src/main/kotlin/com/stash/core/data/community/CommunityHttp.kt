package com.stash.core.data.community

import com.stash.core.data.share.ShareHttp
import com.stash.core.data.share.ShareJson
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * HTTP for the Worker's keyed routes: Community's posts ([CommunityApiClient]), its stations ([StationsApiClient]) and
 * opening a session (RoomApiClient.createSession). Unlike `shareCall`, it keeps a refusal's `error` code.
 */
internal class CommunityHttp(okHttpClient: OkHttpClient) {
    // Bounds each try on OkHttp's side (every address, TLS, the answer). No redirects: OkHttp would carry
    // X-Stash-Community-Key to another host.
    val http = ShareHttp(okHttpClient) { callTimeout(15, TimeUnit.SECONDS); followRedirects(false) }

    @Serializable private data class ErrorBody(val error: String? = null)

    /**
     * One request to [path] on [bases], with [key] on it when there is one; [method] sets anything beyond a GET. A 2xx
     * is [parse]d; a 4xx is the Worker's `error` ("rate_limited" for a bare 429, else "unknown"); the 503 Sessions
     * answers while switched off is [SESSIONS_OFF]; anything else fails, worth trying again.
     */
    suspend fun <T> call(
        bases: List<String>,
        path: String,
        key: String?,
        method: Request.Builder.() -> Unit = {},
        parse: (String) -> T,
    ): CommunityResult<T> =
        try {
            http.exchange(bases, path, { if (key != null) header(CommunityApiClient.KEY_HEADER, key); method() }) { code, text ->
                when {
                    code in 200..299 -> CommunityResult.Ok(parse(text))
                    code in 400..499 -> CommunityResult.Rejected(errorCode(code, text))
                    code == 503 && workerCode(text) == SESSIONS_OFF -> CommunityResult.Rejected(SESSIONS_OFF)
                    else -> CommunityResult.Failed("HTTP $code")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CommunityResult.Failed(e.message)
        }

    /**
     * `PUT` [path], a heard mark (a post's or a station's), under [key]. Never throws. Dropped only on the Worker's own
     * refusal: 404 `gone`, or a 403 with its JSON code (`own_post`, `own_station`). A 403 without one (a Cloudflare
     * challenge page) is the edge, not the Worker: tried again later.
     */
    suspend fun heard(bases: List<String>, path: String, key: String): HeardSend =
        try {
            http.exchange(bases, path, { header(CommunityApiClient.KEY_HEADER, key); put(EMPTY_BODY) }) { code, text ->
                when {
                    code in 200..299 -> HeardSend.SENT
                    code == 403 && workerCode(text) != null -> HeardSend.DROPPED
                    code == 404 && workerCode(text) == "gone" -> HeardSend.DROPPED
                    code == 404 && workerCode(text) == "not_found" -> HeardSend.NO_ROUTE
                    else -> HeardSend.RETRY
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            HeardSend.RETRY
        }

    /** The Worker's `error`. A 429 without one is Cloudflare's own rate-limit page. */
    private fun errorCode(status: Int, text: String): String =
        workerCode(text) ?: if (status == 429) "rate_limited" else "unknown"

    /** The Worker's JSON error code, or null for any other body (an HTML page from the edge, nothing). */
    fun workerCode(text: String): String? =
        runCatching { ShareJson.decodeFromString(ErrorBody.serializer(), text).error }.getOrNull()?.takeIf { it.isNotBlank() }

    companion object {
        /** What every Sessions route answers (503) while the Worker's `SESSIONS` switch is off. */
        const val SESSIONS_OFF = CommunityResult.SESSIONS_OFF
        val JSON = "application/json".toMediaType()
        private val EMPTY_BODY = "{}".toRequestBody(JSON)
    }
}
