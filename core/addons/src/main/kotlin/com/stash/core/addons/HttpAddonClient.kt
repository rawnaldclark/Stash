package com.stash.core.addons

import android.util.Log
import com.stash.addons.contract.Album
import com.stash.addons.contract.Artist
import com.stash.addons.contract.CatalogGenres
import com.stash.addons.contract.CatalogPage
import com.stash.addons.contract.Descriptor
import com.stash.addons.contract.ErrorCode
import com.stash.addons.contract.Limits
import com.stash.addons.contract.Lyrics
import com.stash.addons.contract.Manifest
import com.stash.addons.contract.Quota
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

/** What one add-on call came to: the checked answer, or why there is none. */
sealed interface AddonResult<out T> {
    data class Ok<T>(val value: T) : AddonResult<T>
    data class Failed(val failure: AddonFailure) : AddonResult<Nothing>

    fun valueOrNull(): T? = (this as? Ok)?.value
}

/** Why an add-on call has no answer (SPEC.md 7, plus the host's own failures). */
sealed interface AddonFailure {
    /** The add-on answered with an error. [code] is the body's when this host knows it, else by status. */
    data class Refused(val code: ErrorCode, val status: Int, val retryAfterSec: Int?, val quota: Quota?) : AddonFailure

    /** No answer within the route's time (SPEC.md 3.1). */
    data object Timeout : AddonFailure

    /** No answer at all: refused connection, no route, a bad redirect. */
    data object Network : AddonFailure

    /** The answer broke the contract ([AddonValidator]) and was dropped whole. */
    data class InvalidResponse(val path: String, val reason: String) : AddonFailure

    /** Not asked: the add-on's circuit is open ([AddonRateGate]). */
    data object Paused : AddonFailure

    /** Not asked: the add-on's manifest doesn't list the resource. */
    data object NotOffered : AddonFailure

    /** A miss the host may remember (not_found, quality_unavailable, region_unavailable). Never a failure of the add-on. */
    val isMiss: Boolean get() = this is Refused && code.isMiss
}

/**
 * The host's HTTP client for server add-ons (Stash Add-ons v1, SPEC.md 3).
 *
 *  - **Per-route timeouts** from the contract ([TimeoutsMs]): 10 s for the manifest, search,
 *    lyrics, catalogs and quota; 15 s for resolve, stream, album and artist; 20 s for download.
 *    One deadline covers the whole call, redirects and body included.
 *  - **Reject, don't repair**: every answer goes through [AddonValidator] (ported from the web's
 *    `contract.ts`); one that breaks the contract is [AddonFailure.InvalidResponse].
 *  - **A `Retry-After` circuit per add-on** through [AddonRateGate] (the app's
 *    `AggregatorRateLimiter`), keyed [rateKey]: a 429 or a 503 with `Retry-After` pauses the
 *    add-on for that long, repeated failures trip its breaker, and a paused add-on is not asked.
 *  - **Install-signing headers for first-party add-ons only** ([AddonTrust], the signed config's
 *    rule that `RelaySigner.isOwnerRelay` used), on stream and download calls, as the Tidal relay
 *    gets them today.
 *  - At most 3 redirects, https only; a header token and the signing headers never go to another
 *    origin. Bodies over 2 MiB are rejected.
 *  - Nothing logs a URL: an add-on's base can carry its token (SPEC.md 6). The client is built
 *    from the app's shared one (its connection pool, DNS and TLS) WITHOUT the app's interceptors,
 *    whose debug logging prints request URLs.
 */
@Singleton
class HttpAddonClient @Inject constructor(
    sharedClient: OkHttpClient,
    private val rateGate: AddonRateGate,
    private val trust: AddonTrust,
    private val signer: AddonInstallSigner,
) {
    /** Tests: accept `http:` on localhost for a MockWebServer add-on (SPEC.md 1.1 allows it in development). */
    internal var options: AddonValidator.Options = AddonValidator.Options()

    /** Tests: the clock the deadline is measured on. */
    internal var nowMs: () -> Long = System::currentTimeMillis

    private val http: OkHttpClient = sharedClient.newBuilder()
        .apply {
            interceptors().clear()
            networkInterceptors().clear()
        }
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** The routes and their timeouts (SPEC.md 3.1). [resource]: the one the manifest must list. */
    enum class Route(val timeoutMs: Long, val resource: Resource?) {
        MANIFEST(TimeoutsMs.MANIFEST, null),
        SEARCH(TimeoutsMs.SEARCH, Resource.SEARCH),
        RESOLVE(TimeoutsMs.RESOLVE, Resource.RESOLVE),
        STREAM(TimeoutsMs.STREAM, Resource.STREAM),
        DOWNLOAD(TimeoutsMs.DOWNLOAD, Resource.DOWNLOAD),
        LYRICS(TimeoutsMs.LYRICS, Resource.LYRICS),
        ALBUM(TimeoutsMs.ALBUM, Resource.ALBUM),
        ARTIST(TimeoutsMs.ARTIST, Resource.ARTIST),
        CATALOG(TimeoutsMs.CATALOG, Resource.CATALOG),
        CATALOG_GENRES(TimeoutsMs.CATALOG, Resource.CATALOG),
        QUOTA(TimeoutsMs.QUOTA, Resource.QUOTA),
    }

    // ---------------------------------------------------------------------------------------------- routes

    /** `GET <base>manifest.json`: never with a header token (SPEC.md 6), never paused or signed. */
    suspend fun manifest(base: String): AddonResult<Manifest> =
        exchange(null, base, Route.MANIFEST, listOf("manifest.json"), emptyList(), signPayload = null) {
            AddonValidator.manifest(it, options)
        }

    /** `GET /search` (SPEC.md 4.2), with artists, albums and ids the manifest can't back dropped. */
    suspend fun search(addon: InstalledAddon, q: String, limit: Int = 20, paced: Boolean = false): AddonResult<SearchResponse> =
        call(addon, Route.SEARCH, emptyList(), listOf("q" to q.take(200), "limit" to limit.coerceIn(1, 200).toString()), paced = paced) {
            gateSearch(addon.manifest, AddonValidator.searchResponse(it, options))
        }

    /**
     * `GET /resolve` (SPEC.md 4.3): the add-on's own item for [d], or null for no match. An item
     * that breaks resolve rules 2 or 3 against [d] is rejected.
     */
    suspend fun resolve(addon: InstalledAddon, d: Descriptor, paced: Boolean = false): AddonResult<Track?> =
        call(addon, Route.RESOLVE, emptyList(), descriptorParams(d), paced = paced) {
            val item = AddonValidator.resolveResponse(it, options).item ?: return@call null
            AddonValidator.checkResolveStrict(item, d.isrc, d.durationMs)
            gateIds(addon.manifest, item)
        }

    /** `GET /stream/{id}` (SPEC.md 4.4): an answer outside [request] or marked encrypted is rejected. */
    suspend fun stream(addon: InstalledAddon, id: String, request: StreamRequest, paced: Boolean = false): AddonResult<StreamInfo> =
        streamLike(addon, Route.STREAM, "stream", id, request, paced)

    /** `GET /download/{id}` (SPEC.md 4.5): as [stream], for a file the user keeps. Paced by default. */
    suspend fun download(addon: InstalledAddon, id: String, request: StreamRequest, paced: Boolean = true): AddonResult<StreamInfo> =
        streamLike(addon, Route.DOWNLOAD, "download", id, request, paced)

    private suspend fun streamLike(
        addon: InstalledAddon, route: Route, segment: String, id: String, request: StreamRequest, paced: Boolean,
    ): AddonResult<StreamInfo> =
        call(addon, route, listOf(segment, id), request.queryParameters(), signPayload = id, paced = paced) {
            val info = AddonValidator.streamInfo(it, options)
            if (info.tier !in request.minTier..request.tier) throw AddonContractException("/tier", "outside the asked range")
            if (info.manifest !in request.manifest) throw AddonContractException("/manifest", "a delivery that wasn't asked for")
            if (info.encrypted == true) throw AddonContractException("/encrypted", "v1 defines no keys: not playable")
            info
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

    suspend fun quota(addon: InstalledAddon): AddonResult<Quota> =
        call(addon, Route.QUOTA, listOf("quota"), emptyList(), paced = false) { AddonValidator.quota(it) }

    // ---------------------------------------------------------------------------------------------- the call

    /** A route of [addon]: refused unasked when its manifest doesn't list the route's resource, or its circuit is open. */
    private suspend fun <T> call(
        addon: InstalledAddon,
        route: Route,
        segments: List<String>,
        params: List<Pair<String, String>>,
        signPayload: String? = null,
        paced: Boolean,
        parse: (JsonElement) -> T,
    ): AddonResult<T> {
        if (route.resource != null && !addon.offers(route.resource)) return AddonResult.Failed(AddonFailure.NotOffered)
        val key = rateKey(addon.id)
        if (!rateGate.admit(key, paced)) return AddonResult.Failed(AddonFailure.Paused)
        val path = if (segments.isEmpty()) listOf(route.name.lowercase().replace('_', '-')) else segments
        val withSettings = params + addon.settings.map { (k, v) -> k to v }
        val result = exchange(addon, addon.base, route, path, withSettings, signPayload, parse)
        report(key, result)
        return result
    }

    /** The circuit's view of [result] (SPEC.md 7 "Host does"). */
    private suspend fun report(key: String, result: AddonResult<*>) {
        when (val f = (result as? AddonResult.Failed)?.failure) {
            null -> rateGate.onAnswer(key)
            is AddonFailure.Refused -> when (f.code) {
                ErrorCode.RATE_LIMITED, ErrorCode.QUOTA_EXCEEDED -> rateGate.onRateLimited(key, f.retryAfterSec?.let { it * 1000L })
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
                val builder = Request.Builder().url(url).get().header("Accept", "application/json")
                val token = addon?.token?.takeIf { route != Route.MANIFEST }
                if (sameOrigin && addon != null && token != null && addon.manifest.auth?.token == TokenPlacement.HEADER) {
                    builder.header(Auth.HEADER_NAME, token)
                }
                val signed = sameOrigin && firstParty && signer.sign(builder, signPayload!!)
                val remaining = deadline - nowMs()
                if (remaining <= 0) return AddonResult.Failed(AddonFailure.Timeout)
                val reply = execute(builder.build(), remaining, adoptServerTime = signed)
                if (reply.code in 300..399 && reply.location != null) {
                    val next = url.resolve(reply.location)
                    if (next == null || ++redirects > MAX_REDIRECTS || !AddonValidator.isAllowedUrl(next.toString(), options)) {
                        Log.w(TAG, "$tag: redirect refused")
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
                    Log.d(TAG, "$tag: HTTP ${reply.code}")
                    return AddonResult.Failed(refusal(reply, element))
                }
                element ?: return AddonResult.Failed(AddonFailure.InvalidResponse("", "not JSON"))
                return try {
                    AddonResult.Ok(parse(element))
                } catch (e: AddonContractException) {
                    Log.w(TAG, "$tag: invalid answer at ${e.path.ifEmpty { "/" }}: ${e.reason}")
                    AddonResult.Failed(AddonFailure.InvalidResponse(e.path, e.reason))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedIOException) {
            Log.d(TAG, "$tag: timed out")
            return AddonResult.Failed(AddonFailure.Timeout)
        } catch (e: IOException) {
            Log.d(TAG, "$tag: ${e.javaClass.simpleName}")
            return AddonResult.Failed(AddonFailure.Network)
        }
    }

    /** SPEC.md 7: the body's code when it is the contract's shape and known, else by status; `retryAfter` else the header. */
    private fun refusal(reply: Reply, element: JsonElement?): AddonFailure.Refused {
        val info = element?.let { runCatching { AddonValidator.errorInfo(it) }.getOrNull() }
        val header = reply.retryAfterHeader?.trim()?.toIntOrNull()?.takeIf { it in 1..86_400 }
        return AddonFailure.Refused(
            code = ErrorCode.of(reply.code, info?.code),
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
        if (r.code in 300..399) return Reply(r.code, r.header("Location"), null, false, null, false)
        val source = r.body?.source() ?: return Reply(r.code, null, r.header("Retry-After"), adopted, null, false)
        if (source.request(Limits.BODY_BYTES + 1)) return Reply(r.code, null, r.header("Retry-After"), adopted, null, true)
        val text = source.buffer.readUtf8()
        return Reply(r.code, null, r.header("Retry-After"), adopted, text, false)
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private fun HttpUrl.sameOrigin(other: HttpUrl) = scheme == other.scheme && host == other.host && port == other.port

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

        /** How long an add-on that answered 401 is left alone (its token needs fixing). */
        const val UNAUTHORIZED_PAUSE_MS = 60 * 60_000L

        /** The [AddonRateGate] key of add-on [addonId]: its own bucket and breaker. */
        fun rateKey(addonId: String): String = "addon_$addonId"

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
