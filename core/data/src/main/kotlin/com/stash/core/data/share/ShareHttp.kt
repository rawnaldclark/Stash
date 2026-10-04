package com.stash.core.data.share

import android.util.Log
import com.stash.core.model.share.ShareConfig
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * HTTP to the share Worker (mixes, song links, Listen Together, Community): [client], configured by [configure],
 * plus the host fallback below. Each API client builds one and reuses it.
 */
internal class ShareHttp(client: OkHttpClient, configure: OkHttpClient.Builder.() -> Unit = {}) {
    private val http = client.newBuilder().apply(configure).addNetworkInterceptor(MarkReached).build()

    /**
     * Sends one request, trying each of [bases] in turn ([ShareConfig.API_BASE_URLS]: stashfm.app, then the old
     * workers.dev host, the same Worker and data). The next host is tried only when the request never reached this
     * one: no DNS answer (filters often block newly registered domains), refused, or TLS failed. Never on an HTTP
     * status, and never once the request may have gone out, so a create can't happen twice. [read] turns the status
     * and body into a result, off the caller's thread.
     */
    suspend fun <T> exchange(
        bases: List<String>,
        path: String,
        request: Request.Builder.() -> Unit,
        read: (code: Int, body: String) -> T,
    ): T {
        var unreachable: IOException? = null
        for (base in bases) {
            val reached = Reached()
            val call = http.newCall(Request.Builder().url(base + path).apply(request).tag(Reached::class.java, reached).build())
            try {
                return call.await(read)
            } catch (e: IOException) {
                if (reached.value) throw e
                Log.w(TAG, "${base.substringAfter("://")} unreachable (${e.javaClass.simpleName})")
                unreachable = e
            }
        }
        throw unreachable ?: IOException("no share host")
    }

    /** Set once the request is on an open connection, i.e. DNS, TCP and TLS all worked: it may reach the Worker. */
    private class Reached {
        @Volatile var value = false
    }

    /** Network interceptors only run once a connection is open, so reaching one means the host was reachable. */
    private object MarkReached : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            chain.request().tag(Reached::class.java)?.value = true
            return chain.proceed(chain.request())
        }
    }

    private companion object {
        const val TAG = "ShareHttp"
    }
}

/** One request to the stash-share Worker, mapped to a [ShareResult]. Shared by the mix and room clients. */
internal suspend fun <T> ShareHttp.shareCall(
    bases: List<String>,
    path: String,
    request: Request.Builder.() -> Unit = {},
    parse: (String) -> T,
): ShareResult<T> =
    try {
        exchange(bases, path, request) { code, body ->
            when (code) {
                in 200..299 -> ShareResult.Ok(parse(body))
                403 -> ShareResult.Forbidden
                404 -> ShareResult.NotFound
                410 -> ShareResult.Gone
                429 -> ShareResult.Failed(ShareResult.Failed.RATE_LIMITED)
                in 400..499 -> ShareResult.Rejected(code)
                else -> ShareResult.Failed("HTTP $code")
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ShareResult.Failed(e.message)
    }

/**
 * Runs the call on OkHttp's own thread and suspends until it answers; [read] turns the status and body into a
 * result on that thread, off the caller's. Cancelling the caller cancels the call and returns at once, even while
 * that thread is stuck in a DNS lookup, which nothing can interrupt. A blocking execute() inside withContext
 * held the caller until the lookup gave up: the share sheet's 4 s became 10-30 s on weak signal.
 */
internal suspend fun <T> Call.await(read: (code: Int, body: String) -> T): T = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)

        override fun onResponse(call: Call, response: Response) =
            cont.resumeWith(runCatching { response.use { read(it.code, it.body?.string().orEmpty()) } })
    })
}
