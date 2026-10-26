package com.stash.feature.record.art

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * [ArtHttp] on the app's shared client (its TLS, DNS and logging), with the Worker's 6 s limit per request and the Worker's
 * User-Agent. Cancelling the coroutine cancels the call. A body is read only from a success, and at most [MAX_BODY] of it
 * (a search of ten hits is about 10 KB).
 */
class OkHttpArtHttp(client: OkHttpClient) : ArtHttp {
    private val client = client.newBuilder().callTimeout(TIMEOUT_S, TimeUnit.SECONDS).build()

    override suspend fun get(url: String): ArtReply = suspendCancellableCoroutine { cont ->
        val request = try {
            Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept", "application/json").build()
        } catch (e: IllegalArgumentException) {
            cont.resume(ArtReply(0))
            return@suspendCancellableCoroutine
        }
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resume(ArtReply(0))
            }

            override fun onResponse(call: Call, response: Response) {
                val reply = response.use { r ->
                    try {
                        ArtReply(r.code, if (r.isSuccessful) r.peekBody(MAX_BODY).string() else null)
                    } catch (e: IOException) {
                        ArtReply(0)
                    }
                }
                if (cont.isActive) cont.resume(reply)
            }
        })
    }

    private companion object {
        const val TIMEOUT_S = 6L
        const val MAX_BODY = 512L * 1024
        const val USER_AGENT = "Stash/1.0 (stashfm.app)"
    }
}
