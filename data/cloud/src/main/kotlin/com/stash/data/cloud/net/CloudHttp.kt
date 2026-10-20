package com.stash.data.cloud.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Runs an OkHttp call without blocking a thread, and cancels it when the coroutine is cancelled.
 *
 * The body is read whole on OkHttp's thread before the caller resumes: callers read it as soon as
 * they get it, and a caller on the main thread (the folder picker) reading a body that was still
 * streaming hit NetworkOnMainThreadException on OneDrive. Every answer here is small (listings,
 * tokens, bounded tag ranges).
 */
suspend fun Call.await(): Response = await(bufferBody = true)

/**
 * The same, with [bufferBody] = false for a body too big to hold in memory (a song download): the
 * caller must then read it on a background thread, as [com.stash.data.download] does in its worker.
 */
suspend fun Call.await(bufferBody: Boolean): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            if (!bufferBody) {
                cont.resume(response) { _, _, _ -> response.close() }
                return
            }
            val buffered = try {
                val body = response.body
                if (body == null) {
                    response
                } else {
                    val bytes = body.bytes()
                    response.newBuilder().body(bytes.toResponseBody(body.contentType())).build()
                }
            } catch (e: IOException) {
                response.close()
                if (!cont.isCancelled) cont.resumeWithException(e)
                return
            }
            cont.resume(buffered) { _, _, _ -> buffered.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
}

/** An API answer read whole: status, body text and the one header the callers need. */
data class ApiResponse(val code: Int, val body: String, val retryAfter: String?) {
    val isSuccessful: Boolean get() = code in 200..299
}

/**
 * Sends one provider's API requests through its [RequestGate], with the two recoveries every
 * provider needs:
 *  - 429 / 503: wait out `Retry-After` (or [RequestGate.DEFAULT_BACKOFF_MS]) for every caller of
 *    this provider, then retry: up to [backgroundRetries] times for the scan, once for a tap.
 *  - 401: ask [accessToken] for a fresh token (passing the rejected one, so a refresh runs once
 *    however many requests saw the 401) and retry once.
 *
 * Bodies are API JSON (small), read whole and closed here. Ranged reads of file bytes do not go
 * through this class (see [RangeFetcher]).
 */
class CloudHttp(
    private val client: OkHttpClient,
    private val gate: RequestGate,
    private val backgroundRetries: Int = 4,
) {
    /**
     * @param accessToken `null` = current token; non-null = the token the server just rejected.
     * @param build Builds the request for a given access token (null for unauthenticated calls).
     */
    /**
     * Pauses every caller of this provider for [millis], for a "slow down" the provider says in
     * its own words rather than with a 429/503 (Google Drive's 403 rate-limit reasons).
     */
    fun backOff(millis: Long) = gate.backOff(millis)

    suspend fun execute(
        priority: RequestPriority,
        accessToken: (suspend (rejected: String?) -> String)?,
        build: (token: String?) -> Request,
    ): ApiResponse {
        var token = accessToken?.invoke(null)
        var refreshed = false
        var throttled = 0
        val maxThrottled = if (priority == RequestPriority.INTERACTIVE) 1 else backgroundRetries
        while (true) {
            gate.acquire(priority)
            val response = client.newCall(build(token)).await()
            val result = response.use { ApiResponse(it.code, it.body?.string().orEmpty(), it.header("Retry-After")) }
            when {
                result.code == 401 && accessToken != null && !refreshed -> {
                    refreshed = true
                    token = accessToken(token)
                }
                (result.code == 429 || result.code == 503) && throttled < maxThrottled -> {
                    throttled++
                    gate.backOff(RequestGate.parseRetryAfter(result.retryAfter) ?: RequestGate.DEFAULT_BACKOFF_MS)
                }
                else -> return result
            }
        }
    }
}
