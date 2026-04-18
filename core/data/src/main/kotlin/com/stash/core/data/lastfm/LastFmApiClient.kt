package com.stash.core.data.lastfm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.math.BigInteger
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimal Last.fm 2.0 API client: the three endpoints we need for
 * authenticating + scrobbling from a non-desktop app.
 *
 * Auth flow (web-auth style):
 *   1. [getAuthToken] — returns a one-shot token (expires after ~1 hour
 *      if not authorised).
 *   2. User visits `https://www.last.fm/api/auth/?api_key=X&token=Y` in
 *      their browser and clicks "Yes, allow access."
 *   3. [getSession] — exchanges the authorised token for a persistent
 *      session key. Session keys do not expire; store and reuse.
 *
 * Once the session key is stored, [scrobble] submits play events. Every
 * WRITE call is signed: md5(concat of sorted params + shared secret).
 *
 * This client needs a valid Last.fm API key + shared secret. They're
 * injected as strings so the app layer can source them from BuildConfig
 * (which in turn reads `local.properties` at build time). See the
 * Stash-level README section "Last.fm API setup" for how to obtain one.
 */
@Singleton
class LastFmApiClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val credentials: LastFmCredentials,
) {
    /** Step 1 of web-auth: request a token. User then authorises in browser. */
    suspend fun getAuthToken(): Result<String> = runCatching {
        val params = sortedMapOf(
            "method" to "auth.getToken",
            "api_key" to credentials.apiKey,
        )
        val response = signedGet(params)
        response["token"]!!.jsonPrimitive.content
    }

    /**
     * Step 3 of web-auth: exchange an authorised token for a session key.
     * Returns ([username], [sessionKey]) on success. Returns
     * `Result.failure` if the token hasn't been authorised yet — in that
     * case the user hasn't finished the browser step.
     */
    suspend fun getSession(token: String): Result<Pair<String, String>> = runCatching {
        val params = sortedMapOf(
            "method" to "auth.getSession",
            "api_key" to credentials.apiKey,
            "token" to token,
        )
        val response = signedGet(params)
        val session = response["session"]?.jsonObject ?: error("No session object in response")
        val name = session["name"]!!.jsonPrimitive.content
        val key = session["key"]!!.jsonPrimitive.content
        name to key
    }

    /**
     * Submit a completed play to Last.fm. [timestampEpochSeconds] is the
     * UNIX time when the track *started* playing, not when the scrobble
     * is submitted. Last.fm accepts events submitted hours/days late.
     */
    suspend fun scrobble(
        sessionKey: String,
        artist: String,
        track: String,
        timestampEpochSeconds: Long,
        album: String? = null,
    ): Result<Unit> = runCatching {
        val params = sortedMapOf(
            "method" to "track.scrobble",
            "api_key" to credentials.apiKey,
            "sk" to sessionKey,
            "artist" to artist,
            "track" to track,
            "timestamp" to timestampEpochSeconds.toString(),
        )
        if (!album.isNullOrBlank()) params["album"] = album
        signedPost(params)
        Unit
    }

    // ── Internal: signed request helpers ──────────────────────────────

    private fun sign(params: Map<String, String>): String {
        val sigBase = buildString {
            // Last.fm: params sorted alphabetically by key, concatenated
            // as key1value1key2value2..., then shared secret appended.
            // `format` and `callback` are excluded from the signature.
            params.toSortedMap()
                .filterKeys { it != "format" && it != "callback" }
                .forEach { (k, v) ->
                    append(k)
                    append(v)
                }
            append(credentials.apiSecret)
        }
        val md5 = MessageDigest.getInstance("MD5").digest(sigBase.toByteArray(Charsets.UTF_8))
        return BigInteger(1, md5).toString(16).padStart(32, '0')
    }

    private fun signedGet(params: Map<String, String>): JsonObject {
        val signed = params.toMutableMap().apply {
            put("api_sig", sign(this))
            put("format", "json")
        }
        val url = API_URL.toHttpUrl().newBuilder().apply {
            signed.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val request = Request.Builder().url(url).get().build()
        val body = okHttpClient.newCall(request).execute().use {
            check(it.isSuccessful) { "Last.fm GET failed: HTTP ${it.code}" }
            it.body?.string() ?: error("Empty Last.fm response")
        }
        return json.parseToJsonElement(body).jsonObject
    }

    private fun signedPost(params: Map<String, String>): JsonObject {
        val signed = params.toMutableMap().apply {
            put("api_sig", sign(this))
            put("format", "json")
        }
        val form = FormBody.Builder().apply {
            signed.forEach { (k, v) -> add(k, v) }
        }.build()
        val request = Request.Builder().url(API_URL).post(form).build()
        val body = okHttpClient.newCall(request).execute().use {
            check(it.isSuccessful) { "Last.fm POST failed: HTTP ${it.code}" }
            it.body?.string() ?: error("Empty Last.fm response")
        }
        return json.parseToJsonElement(body).jsonObject
    }

    companion object {
        private const val API_URL = "https://ws.audioscrobbler.com/2.0/"
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Last.fm API credentials. Provided by the app layer (typically from
 * BuildConfig, which reads `local.properties` at build time — see the
 * README for the developer setup).
 *
 * Both values must be non-empty for Last.fm features to function; when
 * empty, the UI should disable the "Connect Last.fm" button. The client
 * itself doesn't check emptiness — that's an app-level concern.
 */
data class LastFmCredentials(
    val apiKey: String,
    val apiSecret: String,
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank() && apiSecret.isNotBlank()
}
