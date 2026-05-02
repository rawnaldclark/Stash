package com.stash.data.download.lossless.squid

import android.util.Log
import com.stash.data.download.lossless.LosslessSourcePreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches the `captcha_verified_at` cookie to every `qobuz.squid.wtf`
 * request when the user has provided one. Other hosts are left
 * untouched, so installing this on the shared OkHttp client (or a
 * derived one used by [com.stash.data.download.lossless.qobuz.QobuzApiClient])
 * is a no-op for everything else.
 *
 * **Why a cookie?** squid.wtf's `/api/download-music` endpoint is
 * gated by an ALTCHA proof-of-work the user solves once on the
 * website. The server stores the verification timestamp in an
 * `HttpOnly` cookie with a ~30-min sliding window — when the cookie
 * is present and unexpired, downloads succeed; otherwise the endpoint
 * returns `403 {"success":false,"error":"Captcha required."}`. The
 * cookie value is just a millisecond timestamp; reusing it from a
 * different client (curl, OkHttp) works fine — the server doesn't
 * pin it to a specific user-agent or IP.
 *
 * Cookie value is held in-memory (volatile) and refreshed reactively
 * from [LosslessSourcePreferences]. Reading from DataStore inside an
 * OkHttp interceptor would block the dispatcher thread on every
 * request — the in-memory cache keeps the hot path synchronous.
 */
@Singleton
class SquidWtfCaptchaInterceptor @Inject constructor(
    prefs: LosslessSourcePreferences,
) : Interceptor {

    @Volatile
    private var cookieValue: String? = null

    /**
     * App-scoped supervisor job — this interceptor is a `@Singleton`
     * so the scope outlives any one network call. We don't need
     * cancellation (the singleton lives until process death), and a
     * SupervisorJob means a downstream collector failure won't
     * propagate to a sibling coroutine.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Eager seed: the very first OkHttp call may fire before the
        // Flow has emitted, so block once at construction to make the
        // value available synchronously. Subsequent updates flow in
        // through the launchIn collector. Construction happens at
        // first @Inject use which is typically well before any
        // network request, so this brief block is fine.
        cookieValue = runBlocking { prefs.captchaCookieValueNow() }
        Log.d(TAG, "init: cookie ${if (cookieValue.isNullOrEmpty()) "NOT SET" else "set (len=${cookieValue!!.length})"}")
        prefs.captchaCookieValue
            .onEach {
                cookieValue = it
                Log.d(TAG, "cookie updated: ${if (it.isNullOrEmpty()) "CLEARED" else "set (len=${it.length})"}")
            }
            .launchIn(scope)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != SQUID_WTF_HOST) return chain.proceed(request)

        val cookie = cookieValue
        if (cookie.isNullOrEmpty()) {
            Log.w(TAG, "no cookie set; passing ${request.url.encodedPath} through")
            return chain.proceed(request)
        }
        Log.d(TAG, "attaching cookie (len=${cookie.length}) to ${request.url.encodedPath}")

        // If the upstream caller already set a Cookie header, append
        // ours rather than clobbering. Most callers don't, but layered
        // interceptors elsewhere in the stack might.
        val existing = request.header("Cookie")
        val merged = if (existing.isNullOrBlank()) {
            "$COOKIE_NAME=$cookie"
        } else if (existing.contains("$COOKIE_NAME=")) {
            // Already set by a higher layer — don't double up.
            existing
        } else {
            "$existing; $COOKIE_NAME=$cookie"
        }

        return chain.proceed(
            request.newBuilder()
                .header("Cookie", merged)
                .build(),
        )
    }

    private companion object {
        const val TAG = "SquidWtfCaptcha"
        const val SQUID_WTF_HOST = "qobuz.squid.wtf"
        const val COOKIE_NAME = "captcha_verified_at"
    }
}
