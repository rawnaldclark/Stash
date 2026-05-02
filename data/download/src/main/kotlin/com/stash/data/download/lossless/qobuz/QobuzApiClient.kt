package com.stash.data.download.lossless.qobuz

import com.stash.data.download.lossless.squid.SquidWtfCaptchaInterceptor
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * HTTP client for the public `qobuz.squid.wtf/api` proxy.
 *
 * squid.wtf is a Next.js front-end that calls the official Qobuz API
 * with the operator's own paid credentials and re-publishes search +
 * download endpoints with no auth required. As a result, this client
 * is *much* simpler than the direct-Qobuz code it replaced: no
 * X-App-Id / X-User-Auth-Token headers, no MD5 request signing, no
 * per-user credential bundle. Just plain GETs against an open API.
 *
 * Every response uses a `{success, data, error}` envelope; the client
 * unwraps it and throws [QobuzApiException] on non-2xx responses or
 * `success: false` payloads so callers can branch on a single signal.
 *
 * The transport is squid.wtf; the catalog underneath is Qobuz, hence
 * the `Qobuz*` types passed through verbatim.
 */
@Singleton
class QobuzApiClient @Inject constructor(
    sharedClient: OkHttpClient,
    captchaInterceptor: SquidWtfCaptchaInterceptor,
) {

    /**
     * Derived OkHttp client that re-uses the shared connection pool +
     * dispatcher + TLS config, but adds a host-scoped interceptor
     * that attaches the `captcha_verified_at` cookie required by
     * squid.wtf's download-music endpoint. Building a derived client
     * (rather than mutating the shared one) keeps the squid.wtf
     * cookie out of unrelated HTTP calls and avoids a cross-module
     * dependency cycle on `:core:network`.
     *
     * `internal var` rather than a `val` so tests can replace it with
     * a MockWebServer-bound client without having to also stand up a
     * real [SquidWtfCaptchaInterceptor] (its `init` does a DataStore
     * read that's awkward in unit tests).
     */
    internal var httpClient: OkHttpClient =
        sharedClient.newBuilder().addInterceptor(captchaInterceptor).build()

    /**
     * Test seam: tests assign a MockWebServer URL before calling any
     * endpoint. Production paths leave this on [DEFAULT_BASE_URL].
     * Kept off the constructor signature so mixing `@Inject` with a
     * default-valued parameter doesn't generate two JVM constructors
     * (which Hilt would reject as ambiguous injection sites).
     */
    internal var baseUrl: String = DEFAULT_BASE_URL

    /** Test seam — override for parsing strictness checks. */
    internal var json: Json = DEFAULT_JSON

    /**
     * Search the squid.wtf-proxied Qobuz catalog. [query] can be free
     * text (`"Radiohead Karma Police"`) or, when squid.wtf detects an
     * `https://(open|play).qobuz.com/(album|track|artist)/<id>` URL,
     * a direct lookup. Stash always passes free text.
     *
     * The server hardcodes `limit=10` — that's not a knob we expose.
     * For more results, paginate via [offset].
     *
     * @param tokenCountry Optional ISO-2 country code; selects which of
     *   squid.wtf's region-bound auth tokens proxies the upstream
     *   request. Useful when a track is region-locked and the random
     *   token happens to be in a region without rights.
     */
    suspend fun search(
        query: String,
        offset: Int = 0,
        tokenCountry: String? = null,
    ): QobuzSearchData = withContext(Dispatchers.IO) {
        val url = "$baseUrl/get-music".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("offset", offset.toString())
            .build()

        val request = Request.Builder()
            .url(url)
            .applyTokenCountry(tokenCountry)
            .get()
            .build()

        executeAndParseEnvelope<QobuzSearchData>(request)
    }

    /**
     * Resolve a Qobuz track id to a signed CDN download URL via
     * squid.wtf's `download-music` route. The returned URL is a direct,
     * pre-signed Akamai link — fetch with a plain GET, no auth, no
     * cookies. Lifetime is short (minutes-to-hours); don't cache it
     * across the full download lifecycle.
     *
     * Squid.wtf surfaces region-lock / non-streamable / upstream-block
     * conditions as HTTP 4xx (typically 403) rather than in-payload
     * `restrictions` — caller should treat [QobuzApiException] with
     * status 403 as "no match here, try the next source" rather than
     * a hard failure.
     *
     * @param trackId Qobuz canonical track id from search results.
     * @param quality One of [QobuzQuality]. Default FLAC 24/192;
     *   server picks highest-available <= requested.
     * @param tokenCountry Optional region override; same semantics as
     *   on [search].
     */
    suspend fun getFileUrl(
        trackId: Long,
        quality: Int = QobuzQuality.FLAC_HIRES_192,
        tokenCountry: String? = null,
    ): QobuzDownloadData = withContext(Dispatchers.IO) {
        val url = "$baseUrl/download-music".toHttpUrl().newBuilder()
            .addQueryParameter("track_id", trackId.toString())
            .addQueryParameter("quality", quality.toString())
            .build()

        val request = Request.Builder()
            .url(url)
            .applyTokenCountry(tokenCountry)
            .get()
            .build()

        executeAndParseEnvelope<QobuzDownloadData>(request)
    }

    // ── Internals ───────────────────────────────────────────────────────

    private fun Request.Builder.applyTokenCountry(country: String?): Request.Builder = apply {
        country?.takeIf { it.isNotBlank() }?.let { header("Token-Country", it) }
    }

    /**
     * Execute, decode the `{success, data, error}` envelope, and unwrap
     * to [T]. Throws [QobuzApiException] on:
     *  - HTTP non-2xx
     *  - `success: false` (squid.wtf validation error)
     *  - HTTP 200 with `data: null` and no error message (treat as
     *    upstream weirdness rather than silently returning a default-
     *    constructed [T])
     */
    private inline fun <reified T> executeAndParseEnvelope(request: Request): T {
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                val parsedMessage = runCatching {
                    json.decodeFromString<SquidWtfEnvelope<T>>(body).error
                }.getOrNull()
                throw QobuzApiException(
                    status = response.code,
                    message = parsedMessage ?: response.message.ifBlank { "HTTP ${response.code}" },
                )
            }

            val envelope = runCatching { json.decodeFromString<SquidWtfEnvelope<T>>(body) }
                .getOrElse { e ->
                    throw QobuzApiException(
                        status = response.code,
                        message = "malformed JSON: ${e.message}",
                    )
                }

            if (!envelope.success || envelope.data == null) {
                throw QobuzApiException(
                    status = response.code,
                    message = envelope.error ?: "empty data with success=${envelope.success}",
                )
            }
            return envelope.data
        }
    }

    companion object {
        /**
         * Default origin. Mirrors `us.qobuz.squid.wtf` and
         * `eu.qobuz.squid.wtf` exist running the same code; surfacing
         * a mirror picker is a future settings concern, not the
         * client's job.
         */
        const val DEFAULT_BASE_URL: String = "https://qobuz.squid.wtf/api"

        /**
         * Tolerant Json instance — squid.wtf passes Qobuz responses
         * through, and Qobuz includes many fields we don't model.
         * Without `ignoreUnknownKeys` deserialisation would fail on
         * every call.
         */
        val DEFAULT_JSON: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
    }
}

/**
 * Thrown when the squid.wtf API returns a non-success response — either
 * a non-2xx status or a 2xx with `success: false`. Callers higher up
 * the chain treat any [QobuzApiException] as "skip this source, try
 * the next" rather than a fatal error; the rate limiter records the
 * failure for circuit-breaker bookkeeping.
 *
 * 429 keeps its specific meaning so the rate limiter can distinguish
 * "you're calling too fast" from "the source is broken".
 */
class QobuzApiException(
    val status: Int,
    override val message: String?,
) : RuntimeException("squid.wtf $status: $message")
