package com.stash.core.addons

import android.util.Log
import com.stash.addons.contract.Album
import com.stash.addons.contract.Artist
import com.stash.addons.contract.CatalogGenres
import com.stash.addons.contract.CatalogPage
import com.stash.addons.contract.Delivery
import com.stash.addons.contract.Descriptor
import com.stash.addons.contract.ErrorCode
import com.stash.addons.contract.Limits
import com.stash.addons.contract.Lyrics
import com.stash.addons.contract.Manifest
import com.stash.addons.contract.Quota
import com.stash.addons.contract.ResolveQuery
import com.stash.addons.contract.Resource
import com.stash.addons.contract.SearchResponse
import com.stash.addons.contract.SearchTop
import com.stash.addons.contract.StreamInfo
import com.stash.addons.contract.StreamRequest
import com.stash.addons.contract.TimeoutsMs
import com.stash.addons.contract.TokenPlacement
import com.stash.addons.contract.Track
import com.stash.addons.contract.Auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The host's HTTP client for server add-ons (Stash Add-ons v1, SPEC.md 3).
 *
 *  - **Per-route timeouts** from the contract ([TimeoutsMs]): 10 s for the manifest, search,
 *    lyrics, catalogs and quota; 15 s for resolve, stream, album and artist; 20 s for download.
 *    One deadline covers the whole call: the wait for a connection slot, redirects and body included.
 *  - **Reject, don't repair**: every answer goes through [AddonValidator] (ported from the web's
 *    `contract.ts`); one that breaks the contract is [AddonFailure.InvalidResponse].
 *  - **A `Retry-After` circuit per add-on** through [AddonRateGate] (the app's
 *    `AggregatorRateLimiter`), keyed [rateKey]: a 429 or a 503 with `Retry-After` pauses the
 *    add-on for that long, repeated failures trip its breaker, and a paused add-on is not asked.
 *    `quota_exceeded` pauses only the metered routes (stream and download, keyed [meteredKey]),
 *    as SPEC.md 7 says: search, lyrics and the rest go on. A 5xx other than 503 is `unavailable`
 *    whatever its body says.
 *  - **Install-signing headers for first-party add-ons only** ([AddonTrust], the signed config's
 *    rule that `RelaySigner.isOwnerRelay` used), on stream, download and quota calls, as the Tidal
 *    relay gets them today. The payload is the id exactly as sent (`td:12345`), and `quota` for
 *    `/quota` (SPEC.md 6, informative).
 *  - **v0 add-ons** (`stash: 0`, the web player's contract) are read through [AddonV0]: their
 *    manifest and streams as v1 (SPEC.md 10.1).
 *  - At most 3 redirects, https only; a header token and the signing headers never go to another
 *    origin. Bodies over 2 MiB are rejected.
 *  - Nothing logs a URL or a token: an add-on's base can carry its token (SPEC.md 6). A token
 *    or header value a request can't carry is a [AddonFailure.Network] with a fixed log line
 *    (OkHttp's own message would repeat the value). Text the add-on sent is logged cut short and
 *    without control characters. The client is built from the app's shared one (its connection
 *    pool, DNS and TLS) WITHOUT the app's interceptors, whose debug logging prints request URLs.
 */
@Singleton
class HttpAddonClient @Inject constructor(
    sharedClient: OkHttpClient,
    private val rateGate: AddonRateGate,
    private val trust: AddonTrust,
    private val signer: AddonInstallSigner,
    hostOptions: AddonHostOptions = StrictAddonHost,
) {
    /**
     * The URL rule's development exception (SPEC.md 1.1): `http:` on localhost, for debug builds
     * ([AddonHostOptions]) and for a MockWebServer add-on in tests.
     */
    internal var options: AddonValidator.Options = AddonValidator.Options(allowLocalHttp = hostOptions.allowLocalHttp)

    /** Tests: the clock the deadline is measured on. */
    internal var nowMs: () -> Long = System::currentTimeMillis

    /** Tests: sees every line this client logs, to prove none carries a secret. */
    internal var logHook: ((String) -> Unit)? = null

    private val http: OkHttpClient = sharedClient.newBuilder()
        .apply {
            interceptors().clear()
            networkInterceptors().clear()
        }
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The routes and their timeouts (SPEC.md 3.1). [resource]: the one the manifest must list.
     * [metered]: a call that spends the caller's allowance (SPEC.md 4.8), paused by `quota_exceeded`.
     */
    enum class Route(val timeoutMs: Long, val resource: Resource?, val metered: Boolean = false) {
        MANIFEST(TimeoutsMs.MANIFEST, null),
        SEARCH(TimeoutsMs.SEARCH, Resource.SEARCH),
        RESOLVE(TimeoutsMs.RESOLVE, Resource.RESOLVE),
        STREAM(TimeoutsMs.STREAM, Resource.STREAM, metered = true),
        DOWNLOAD(TimeoutsMs.DOWNLOAD, Resource.DOWNLOAD, metered = true),
        LYRICS(TimeoutsMs.LYRICS, Resource.LYRICS),
        ALBUM(TimeoutsMs.ALBUM, Resource.ALBUM),
        ARTIST(TimeoutsMs.ARTIST, Resource.ARTIST),
        CATALOG(TimeoutsMs.CATALOG, Resource.CATALOG),
        CATALOG_GENRES(TimeoutsMs.CATALOG, Resource.CATALOG),
        QUOTA(TimeoutsMs.QUOTA, Resource.QUOTA),
    }

    // ---------------------------------------------------------------------------------------------- routes

    /**
     * `GET <base>manifest.json`: never with a header token (SPEC.md 6), never paused or signed. A v1
     * manifest, or a v0 one read as v1 ([AddonV0]); anything else is an invalid response.
     */
    suspend fun manifest(base: String): AddonResult<Manifest> =
        when (val r = manifestWithJson(base)) {
            is AddonResult.Ok -> AddonResult.Ok(r.value.first)
            is AddonResult.Failed -> r
        }

    /**
     * [manifest], with the checked answer as JSON text too, for a host that keeps the last good
     * manifest across restarts ([AddonManifestStore]). The text holds no URL or token of ours.
     */
    suspend fun manifestWithJson(base: String): AddonResult<Pair<Manifest, String>> =
        exchange(null, base, Route.MANIFEST, listOf("manifest.json"), emptyList(), signPayload = null) {
            AddonValidator.anyManifest(it, options) to it.toString()
        }

    /**
     * `GET /search` (SPEC.md 4.2), with artists, albums and ids the manifest can't back dropped.
     * [circuit] false: a caller that keeps its own busy circuit (the named Tidal rung) asks
     * without this client's `Retry-After` gate, which then neither holds the call nor hears of it.
     */
    suspend fun search(
        addon: InstalledAddon, q: String, limit: Int = 20, paced: Boolean = false, circuit: Boolean = true,
    ): AddonResult<SearchResponse> =
        call(
            addon, Route.SEARCH, emptyList(), listOf("q" to q.take(200), "limit" to limit.coerceIn(1, 200).toString()),
            paced = paced, circuit = circuit,
        ) {
            gateSearch(addon.manifest, AddonValidator.searchResponse(it, options))
        }

    /**
     * `GET /resolve` (SPEC.md 4.3, draft 2): the add-on's own item for the same song as [query], or
     * null for no match. An item that breaks rule 3 (length) or rule 7 (`maxTier` below the asked
     * `minTier`) is rejected; its ISRC alone never is.
     */
    suspend fun resolve(addon: InstalledAddon, query: ResolveQuery, paced: Boolean = false): AddonResult<Track?> =
        call(addon, Route.RESOLVE, emptyList(), resolveParams(query), paced = paced) {
            val item = AddonValidator.resolveResponse(it, options).item ?: return@call null
            AddonValidator.checkResolve(item, query)
            gateIds(addon.manifest, item)
        }

    /** `GET /stream/{id}` (SPEC.md 4.4): an answer outside [request] or marked encrypted is rejected. [circuit]: as [search]. */
    suspend fun stream(
        addon: InstalledAddon, id: String, request: StreamRequest, paced: Boolean = false, circuit: Boolean = true,
    ): AddonResult<StreamInfo> =
        streamLike(addon, Route.STREAM, "stream", id, request, paced, circuit)

    /** `GET /download/{id}` (SPEC.md 4.5): as [stream], for a file the user keeps. Paced by default. */
    suspend fun download(
        addon: InstalledAddon, id: String, request: StreamRequest, paced: Boolean = true, circuit: Boolean = true,
    ): AddonResult<StreamInfo> =
        streamLike(addon, Route.DOWNLOAD, "download", id, request, paced, circuit)

    private suspend fun streamLike(
        addon: InstalledAddon, route: Route, segment: String, id: String, request: StreamRequest, paced: Boolean, circuit: Boolean,
    ): AddonResult<StreamInfo> {
        val v0 = addon.manifest.stash == 0
        val result = call(addon, route, listOf(segment, id), request.queryParameters(), signPayload = id, paced = paced, circuit = circuit) {
            if (v0) return@call AddonV0.streamInfo(it, options)
            val info = AddonValidator.streamInfo(it, options)
            if (info.tier !in request.minTier..request.tier) throw AddonContractException("/tier", "outside the asked range")
            if (info.manifest !in request.manifest) throw AddonContractException("/manifest", "a delivery that wasn't asked for")
            if (info.encrypted == true) throw AddonContractException("/encrypted", "v1 defines no keys: not playable")
            info
        }
        // SPEC.md 10.1: v0 ignores the range, so a derived tier outside it is a quality miss, not a broken answer.
        val info = (result as? AddonResult.Ok)?.value
        if (v0 && info != null && (info.tier !in request.minTier..request.tier || Delivery.NONE !in request.manifest)) {
            return AddonResult.Failed(AddonFailure.Refused(ErrorCode.QUALITY_UNAVAILABLE, 404, null, null))
        }
        return result
    }

    /** `GET /lyrics` by the add-on's own [id], else by [d] (SPEC.md 4.6). No lyrics is `not_found`. */
    suspend fun lyrics(addon: InstalledAddon, d: Descriptor?, id: String? = null, paced: Boolean = false): AddonResult<Lyrics> {
        val params = when {
            id != null -> listOf("id" to id)
            d != null -> descriptorParams(d).filterNot { it.first == "explicit" }
            else -> return AddonResult.Failed(AddonFailure.InvalidResponse("", "no id or descriptor"))
        }
        return call(addon, Route.LYRICS, emptyList(), params, paced = paced) { AddonValidator.lyrics(it) }
    }

    suspend fun album(addon: InstalledAddon, id: String): AddonResult<Album> =
        call(addon, Route.ALBUM, listOf("album", id), emptyList(), paced = false) {
            AddonValidator.album(it, options).let { a -> a.copy(tracks = a.tracks.map { t -> gateIds(addon.manifest, t) }) }
        }

    suspend fun artist(addon: InstalledAddon, id: String): AddonResult<Artist> =
        call(addon, Route.ARTIST, listOf("artist", id), emptyList(), paced = false) {
            AddonValidator.artist(it, options).let { a -> a.copy(topTracks = a.topTracks.map { t -> gateIds(addon.manifest, t) }) }
        }

    suspend fun catalog(addon: InstalledAddon, id: String, genre: String? = null, limit: Int = 20, offset: Int = 0): AddonResult<CatalogPage> {
        val params = buildList {
            if (genre != null) add("genre" to genre)
            add("limit" to limit.coerceIn(1, 100).toString())
            add("offset" to offset.coerceAtLeast(0).toString())
        }
        return call(addon, Route.CATALOG, listOf("catalog", id), params, paced = false) { AddonValidator.catalogPage(it, options) }
    }

    suspend fun catalogGenres(addon: InstalledAddon): AddonResult<CatalogGenres> =
        call(addon, Route.CATALOG_GENRES, listOf("catalog-genres"), emptyList(), paced = false) { AddonValidator.catalogGenres(it) }

    /**
     * `GET /quota` (SPEC.md 4.8). Signed for a first-party add-on with the payload `quota`, as the
     * Tidal relay's own `/quota` is (SPEC.md 6, informative): unsigned, it answers 401. [circuit]: as [search].
     */
    suspend fun quota(addon: InstalledAddon, circuit: Boolean = true): AddonResult<Quota> =
        call(addon, Route.QUOTA, listOf("quota"), emptyList(), signPayload = QUOTA_PAYLOAD, paced = false, circuit = circuit) {
            AddonValidator.quota(it)
        }

    // ---------------------------------------------------------------------------------------------- the call

    /**
     * A route of [addon]: refused unasked when its manifest doesn't list the route's resource, when
     * its circuit is open, or (metered routes) while its allowance is paused.
     */
    private suspend fun <T> call(
        addon: InstalledAddon,
        route: Route,
        segments: List<String>,
        params: List<Pair<String, String>>,
        signPayload: String? = null,
        paced: Boolean,
        circuit: Boolean = true,
        parse: (JsonElement) -> T,
    ): AddonResult<T> {
        if (route.resource != null && !addon.offers(route.resource)) return AddonResult.Failed(AddonFailure.NotOffered)
        val key = rateKey(addon.id)
        if (circuit && !rateGate.admit(key, paced)) return AddonResult.Failed(AddonFailure.Paused)
        if (circuit && route.metered && !rateGate.admit(meteredKey(addon.id), paced = false)) {
            return AddonResult.Failed(AddonFailure.Paused)
        }
        val path = if (segments.isEmpty()) listOf(route.name.lowercase().replace('_', '-')) else segments
        val withSettings = params + addon.settings.map { (k, v) -> k to v }
        val result = exchange(addon, addon.base, route, path, withSettings, signPayload, parse)
        if (circuit) report(addon.id, result)
        return result
    }

    /** The circuit's view of [result] (SPEC.md 7 "Host does"). */
    private suspend fun report(addonId: String, result: AddonResult<*>) {
        val key = rateKey(addonId)
        when (val f = (result as? AddonResult.Failed)?.failure) {
            null -> rateGate.onAnswer(key)
            is AddonFailure.Refused -> when (f.code) {
                ErrorCode.RATE_LIMITED -> rateGate.onRateLimited(key, f.retryAfterSec?.let { it * 1000L })
                // "Pauses metered calls until then": the add-on answered, and its other routes go on.
                ErrorCode.QUOTA_EXCEEDED -> {
                    rateGate.onAnswer(key)
                    rateGate.onRateLimited(meteredKey(addonId), f.retryAfterSec?.let { it * 1000L })
                }
                ErrorCode.UNAVAILABLE ->
                    if (f.retryAfterSec != null) rateGate.onRateLimited(key, f.retryAfterSec * 1000L) else rateGate.onFailure(key)
                // "Stops calling; asks the user": until there is a screen to ask on (phase 4), a long pause.
                ErrorCode.UNAUTHORIZED -> rateGate.onRateLimited(key, UNAUTHORIZED_PAUSE_MS)
                ErrorCode.BAD_REQUEST, ErrorCode.NOT_FOUND, ErrorCode.QUALITY_UNAVAILABLE, ErrorCode.REGION_UNAVAILABLE ->
                    rateGate.onAnswer(key)
            }
            AddonFailure.Timeout, AddonFailure.Network, is AddonFailure.InvalidResponse -> rateGate.onFailure(key)
            AddonFailure.Paused, AddonFailure.NotOffered -> Unit
        }
    }

    private suspend fun <T> exchange(
        addon: InstalledAddon?,
        base: String,
        route: Route,
        segments: List<String>,
        params: List<Pair<String, String>>,
        signPayload: String?,
        parse: (JsonElement) -> T,
    ): AddonResult<T> {
        val baseUrl = base.takeIf { AddonValidator.isAllowedUrl(it, options) }?.toHttpUrlOrNull()
            ?: return AddonResult.Failed(AddonFailure.Network)
        var url = baseUrl.newBuilder().apply {
            segments.forEach { addPathSegment(it) }
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val tag = "${addon?.id ?: "manifest"} ${route.name.lowercase()}"
        val deadline = nowMs() + route.timeoutMs
        val firstParty = signPayload != null && trust.isFirstParty(base)
        var redirects = 0
        var mayResign = true
        try {
            while (true) {
                val sameOrigin = url.sameOrigin(baseUrl)
                // Building the request throws IllegalArgumentException, with the value in its
                // message, for a token or header value a request can't carry: caught below.
                val builder = Request.Builder().url(url).get().header("Accept", "application/json")
                val token = addon?.token?.takeIf { route != Route.MANIFEST }
                if (sameOrigin && addon != null && token != null && addon.manifest.auth?.token == TokenPlacement.HEADER) {
                    builder.header(Auth.HEADER_NAME, token)
                }
                val signed = sameOrigin && firstParty && signer.sign(builder, signPayload!!)
                val remaining = deadline - nowMs()
                if (remaining <= 0) return AddonResult.Failed(AddonFailure.Timeout)
                // The call timeout only starts once OkHttp's dispatcher runs the call: this also
                // bounds the wait for a connection slot.
                val reply = withTimeoutOrNull(remaining) { execute(builder.build(), remaining, adoptServerTime = signed) }
                    ?: run {
                        debug("$tag: timed out")
                        return AddonResult.Failed(AddonFailure.Timeout)
                    }
                if (reply.code in 300..399 && reply.location != null) {
                    val next = url.resolve(reply.location)
                    if (next == null || ++redirects > MAX_REDIRECTS || !AddonValidator.isAllowedUrl(next.toString(), options)) {
                        warn("$tag: redirect refused")
                        return AddonResult.Failed(AddonFailure.Network)
                    }
                    url = next
                    continue
                }
                if (reply.code == 401 && signed && mayResign && reply.serverTimeAdopted) {
                    mayResign = false
                    continue
                }
                if (reply.tooLarge) return AddonResult.Failed(AddonFailure.InvalidResponse("", "body over 2 MiB"))
                val element = reply.body?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
                if (reply.code !in 200..299) {
                    debug("$tag: HTTP ${reply.code}")
                    // A first-party 401 that names the server's clock is a skewed signature, not a
                    // wrong token: signed once more above; still refused, it fails this call only
                    // (SPEC.md 7 `bad_request`), never the 1 h `unauthorized` pause.
                    if (reply.code == 401 && signed && reply.serverTimeNamed) {
                        return AddonResult.Failed(AddonFailure.Refused(ErrorCode.BAD_REQUEST, 401, null, null))
                    }
                    return AddonResult.Failed(refusal(reply, element))
                }
                element ?: return AddonResult.Failed(AddonFailure.InvalidResponse("", "not JSON"))
                return try {
                    AddonResult.Ok(parse(element))
                } catch (e: AddonContractException) {
                    warn("$tag: invalid answer at ${loggable(e.path.ifEmpty { "/" })}: ${loggable(e.reason)}")
                    AddonResult.Failed(AddonFailure.InvalidResponse(e.path, e.reason))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedIOException) {
            debug("$tag: timed out")
            return AddonResult.Failed(AddonFailure.Timeout)
        } catch (e: IOException) {
            debug("$tag: ${e.javaClass.simpleName}")
            return AddonResult.Failed(AddonFailure.Network)
        } catch (e: IllegalArgumentException) {
            // Never e.message: OkHttp puts the offending header value (a token) in it.
            warn("$tag: the request could not be built (a token or header value a request can't carry)")
            return AddonResult.Failed(AddonFailure.Network)
        }
    }

    private fun warn(line: String) {
        Log.w(TAG, line)
        logHook?.invoke(line)
    }

    private fun debug(line: String) {
        Log.d(TAG, line)
        logHook?.invoke(line)
    }

    /**
     * SPEC.md 7: a 5xx other than 503 is `unavailable`, whatever the body says; otherwise the
     * body's code when it is the contract's shape and known, else by status. `retryAfter`, else
     * the header.
     */
    private fun refusal(reply: Reply, element: JsonElement?): AddonFailure.Refused {
        val info = element?.let { runCatching { AddonValidator.errorInfo(it) }.getOrNull() }
        val header = reply.retryAfterHeader?.trim()?.toIntOrNull()?.takeIf { it in 1..86_400 }
        return AddonFailure.Refused(
            code = if (reply.code in 500..599 && reply.code != 503) ErrorCode.UNAVAILABLE else ErrorCode.of(reply.code, info?.code),
            status = reply.code,
            retryAfterSec = info?.retryAfter ?: header,
            quota = info?.quota,
        )
    }

    /** One HTTP exchange's outcome, the body already read (at most [Limits.BODY_BYTES]) and the response closed. */
    private class Reply(
        val code: Int,
        val location: String?,
        val retryAfterHeader: String?,
        val serverTimeAdopted: Boolean,
        val body: String?,
        val tooLarge: Boolean,
        /** The answer carries `X-Stash-Server-Time` (a first-party add-on's skew 401). */
        val serverTimeNamed: Boolean = false,
    )

    /** Runs [request] with a call timeout of [timeoutMs]; cancelling the coroutine cancels the call. */
    private suspend fun execute(request: Request, timeoutMs: Long, adoptServerTime: Boolean): Reply = suspendCancellableCoroutine { cont ->
        val call = http.newCall(request)
        call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val reply = try {
                    response.use { readReply(it, adoptServerTime) }
                } catch (e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                    return
                }
                if (cont.isActive) cont.resume(reply)
            }
        })
    }

    /** [adoptServerTime]: only a signed request's 401 may move the signing clock; no other add-on gets to. */
    private fun readReply(r: Response, adoptServerTime: Boolean): Reply {
        val adopted = adoptServerTime && r.code == 401 && signer.adoptServerTime(r)
        val named = r.code == 401 && r.header(SERVER_TIME_HEADER) != null
        if (r.code in 300..399) return Reply(r.code, r.header("Location"), null, false, null, false)
        val source = r.body?.source() ?: return Reply(r.code, null, r.header("Retry-After"), adopted, null, false, named)
        if (source.request(Limits.BODY_BYTES + 1)) return Reply(r.code, null, r.header("Retry-After"), adopted, null, true, named)
        val text = source.buffer.readUtf8()
        return Reply(r.code, null, r.header("Retry-After"), adopted, text, false, named)
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private fun HttpUrl.sameOrigin(other: HttpUrl) = scheme == other.scheme && host == other.host && port == other.port

    /** The resolve query: the song as [descriptorParams] sends it, then `minTier` (SPEC.md 4.3 rule 7). */
    private fun resolveParams(q: ResolveQuery): List<Pair<String, String>> =
        descriptorParams(q.song) + listOfNotNull(q.minTier?.let { "minTier" to it.wire })

    private fun descriptorParams(d: Descriptor): List<Pair<String, String>> = buildList {
        add("title" to d.title)
        add("artist" to d.artist)
        d.album?.takeIf { it.isNotBlank() }?.let { add("album" to it) }
        d.durationMs?.takeIf { it > 0 }?.let { add("durationMs" to it.toString()) }
        d.isrc?.takeIf { it.isNotBlank() }?.let { add("isrc" to it) }
        d.explicit?.let { add("explicit" to it.toString()) }
    }

    companion object {
        private const val TAG = "HttpAddonClient"
        private const val MAX_REDIRECTS = 3

        /** The first-party skew answer's header (SPEC.md 6, informative): the server's clock, epoch seconds. */
        const val SERVER_TIME_HEADER = "X-Stash-Server-Time"

        /** What a first-party `/quota` is signed over (SPEC.md 6, informative). */
        const val QUOTA_PAYLOAD = "quota"

        /** How long an add-on that answered 401 is left alone (its token needs fixing). */
        const val UNAUTHORIZED_PAUSE_MS = 60 * 60_000L

        /** The [AddonRateGate] key of add-on [addonId]: its own bucket and breaker. */
        fun rateKey(addonId: String): String = "addon_$addonId"

        /** The [AddonRateGate] key of add-on [addonId]'s allowance: `quota_exceeded` pauses only this. */
        fun meteredKey(addonId: String): String = "addon_${addonId}_metered"

        /** Add-on text (JSON keys, header names in error paths) as a log can carry it: short, no control characters. */
        internal fun loggable(s: String): String =
            s.take(120).map { if (it < ' ' || it in '\u007f'..'\u009f') ' ' else it }.joinToString("")

        /** The base of a manifest URL (SPEC.md 3): without the final `manifest.json`, ending in `/`. Null when not a manifest URL. */
        fun baseOf(manifestUrl: String): String? {
            val u = manifestUrl.trim()
            val base = when {
                u.endsWith("/manifest.json") -> u.removeSuffix("manifest.json")
                u.endsWith("/") -> u
                else -> return null
            }
            return base.takeIf { it.toHttpUrlOrNull() != null }
        }

        /** SPEC.md 4.1: drop `albumId` / `artistId` the manifest has no route for. */
        fun gateIds(manifest: Manifest, t: Track): Track {
            val album = manifest.offers(Resource.ALBUM)
            val artist = manifest.offers(Resource.ARTIST)
            return if ((album || t.albumId == null) && (artist || t.artistId == null)) t
            else t.copy(albumId = t.albumId.takeIf { album }, artistId = t.artistId.takeIf { artist })
        }

        /** SPEC.md 4.2: ignore artists and albums (and such a top) the manifest has no page for. */
        fun gateSearch(manifest: Manifest, r: SearchResponse): SearchResponse {
            val album = manifest.offers(Resource.ALBUM)
            val artist = manifest.offers(Resource.ARTIST)
            val top = when (val t = r.top) {
                is SearchTop.TrackTop -> SearchTop.TrackTop(gateIds(manifest, t.item))
                is SearchTop.ArtistTop -> t.takeIf { artist }
                is SearchTop.AlbumTop -> t.takeIf { album }
                null -> null
            }
            return SearchResponse(
                tracks = r.tracks.map { gateIds(manifest, it) },
                artists = r.artists?.takeIf { artist && it.isNotEmpty() },
                albums = r.albums?.takeIf { album && it.isNotEmpty() },
                top = top,
            )
        }
    }
}
