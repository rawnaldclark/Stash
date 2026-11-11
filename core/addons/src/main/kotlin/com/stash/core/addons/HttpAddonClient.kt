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
import com.stash.addons.contract.ResolveQuery
import com.stash.addons.contract.Resource
import com.stash.addons.contract.SearchResponse
import com.stash.addons.contract.SearchTop
import com.stash.addons.contract.StreamInfo
import com.stash.addons.contract.StreamRequest
import com.stash.addons.contract.Tier
import com.stash.addons.contract.TimeoutsMs
import com.stash.addons.contract.TokenPlacement
import com.stash.addons.contract.Track
import com.stash.addons.contract.Auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionSpec
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
 *    manifest and streams as v1 (SPEC.md 10.1). **Eclipse-family add-ons** (no `stash`) through
 *    [AddonEclipse] (SPEC.md 10.2, add-ons plan phase 4 §2.3), asked with their own parameters.
 *  - **The base rules** for an add-on the user added (phase 4 §2.6): its URLs, every redirect hop
 *    and every URL it hands back must name a public host, and its DNS answers must be public
 *    ([PublicOnlyDns]). Every request carries `User-Agent: Stash/<versionName>`.
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
     * ([AddonHostOptions]) and for a MockWebServer add-on in tests. The base every call's rules
     * start from: [optionsFor] adds the base rules for an add-on Stash doesn't vouch for.
     */
    internal var options: AddonValidator.Options = AddonValidator.Options(allowLocalHttp = hostOptions.allowLocalHttp)

    /** Tests: the clock the deadline is measured on. */
    internal var nowMs: () -> Long = System::currentTimeMillis

    /** Tests: sees every line this client logs, to prove none carries a secret. */
    internal var logHook: ((String) -> Unit)? = null

    /** `Stash/<versionName>` on every add-on request (add-ons plan phase 4 §2.3). */
    private val userAgent: String = hostOptions.userAgent

    private val http: OkHttpClient = sharedClient.newBuilder()
        .apply {
            interceptors().clear()
            networkInterceptors().clear()
        }
        .followRedirects(false)
        .followSslRedirects(false)
        // The app's shared client speaks TLS only (MODERN_TLS). The development exception needs
        // cleartext too; the URL rule still limits it to 127.0.0.1, localhost and [::1].
        .apply { if (hostOptions.allowLocalHttp) connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT)) }
        .build()

    /**
     * [http] for add-ons Stash doesn't vouch for: its DNS drops private answers ([PublicOnlyDns]).
     * OkHttp pools connections per address, and an address includes its DNS, so a connection one
     * client made is never handed to the other.
     */
    private val publicHttp: OkHttpClient = http.newBuilder()
        .dns(PublicOnlyDns(sharedClient.dns, allowLoopback = hostOptions.allowLocalHttp))
        .build()

    /**
     * What the player fetches add-on streams with (`addon:` origins, add-ons plan phase 4 §2.6): the
     * app's shared client without its interceptors (their debug logging prints URLs, and a stream
     * URL can carry a signature), following redirects as a player must, with [PublicOnlyDns] and
     * a connection guard ([PublicOnlyDns.connectionGuard]) for an IP literal in a redirect, so no
     * add-on can make the player fetch from the user's own network.
     */
    val mediaClient: OkHttpClient by lazy {
        sharedClient.newBuilder()
            .apply {
                interceptors().clear()
                networkInterceptors().clear()
            }
            .dns(PublicOnlyDns(sharedClient.dns, allowLoopback = hostOptions.allowLocalHttp))
            .addNetworkInterceptor(PublicOnlyDns.connectionGuard(allowLoopback = hostOptions.allowLocalHttp))
            .apply { if (hostOptions.allowLocalHttp) connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT)) }
            .build()
    }

    /** The User-Agent the player sends with add-on media: the same as the API calls'. */
    val mediaUserAgent: String get() = userAgent

    /**
     * The rules a call follows: [options], plus the base rules ([AddonValidator.Options.publicOnly])
     * for an add-on Stash doesn't run ([Trust.UNOFFICIAL]). The signed config's defaults and the
     * signed index's add-ons are Stash's own (add-ons plan phase 4 §2.6).
     */
    fun optionsFor(publicOnly: Boolean): AddonValidator.Options = options.copy(publicOnly = publicOnly)

    private fun optionsFor(addon: InstalledAddon): AddonValidator.Options = optionsFor(publicOnly = addon.trust == Trust.UNOFFICIAL)

    private val unauthorizedIds = MutableStateFlow<Set<String>>(emptySet())

    /**
     * The add-ons whose last answer was a plain 401: the token in their link stopped working, and
     * they are left alone for [UNAUTHORIZED_PAUSE_MS]. Their page says "Needs a new link" (add-ons
     * plan phase 4 §2.9). An id leaves on its next good answer, or through [forgive]. Kept in
     * memory only: after a restart the first refusal says it again.
     */
    val unauthorized: StateFlow<Set<String>> = unauthorizedIds.asStateFlow()

    /**
     * A play stopped waiting for [addonId] (the host's own budget for an add-on Stash doesn't run):
     * a failure for its breaker, as a timeout is, so an add-on that is slow every time stops being
     * asked instead of holding every play for that long.
     */
    suspend fun reportTooSlow(addonId: String) = rateGate.onFailure(rateKey(addonId))

    private val onlyBitChordIds = MutableStateFlow<Set<String>>(emptySet())

    /**
     * The add-ons that answered a call with BitChord's own 401 (`WWW-Authenticate: BitChord`): they
     * serve only the genuine BitChord, and Stash respects that by not asking them again (the
     * registry holds them). An id leaves through [forgive].
     */
    val onlyBitChord: StateFlow<Set<String>> = onlyBitChordIds.asStateFlow()

    /** The user replaced [addonId]'s link (or removed it): its pause ends now and the 401 is forgotten. */
    suspend fun forgive(addonId: String) {
        unauthorizedIds.update { it - addonId }
        onlyBitChordIds.update { it - addonId }
        rateGate.reset(rateKey(addonId))
        rateGate.reset(meteredKey(addonId))
    }

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
     * manifest, a v0 one read as v1 ([AddonV0]), or an Eclipse-family one ([AddonEclipse]); anything
     * else is an invalid response. [publicOnly]: the base rules ([optionsFor]); true for a link the
     * user added, false for the signed config's defaults.
     */
    suspend fun manifest(base: String, publicOnly: Boolean = true): AddonResult<Manifest> =
        when (val r = readManifest(base, publicOnly)) {
            is AddonResult.Ok -> AddonResult.Ok(r.value.manifest)
            is AddonResult.Failed -> r
        }

    /** [manifest], with the dialect it came in and an Eclipse add-on's traits. */
    suspend fun readManifest(base: String, publicOnly: Boolean = true, file: String = MANIFEST_FILE): AddonResult<AddonManifest> =
        when (val r = manifestWithJson(base, publicOnly, file)) {
            is AddonResult.Ok -> AddonResult.Ok(r.value.first)
            is AddonResult.Failed -> r
        }

    /**
     * [readManifest], with the checked answer as JSON text too, for a host that keeps the last good
     * manifest across restarts ([AddonManifestStore]). The text holds no URL or token of ours.
     * [file]: the manifest's file name under [base] ([manifestFileOf]); BitChord-style links may
     * name it anything ending in `.json`.
     */
    suspend fun manifestWithJson(
        base: String, publicOnly: Boolean = true, file: String = MANIFEST_FILE,
    ): AddonResult<Pair<AddonManifest, String>> {
        val opts = optionsFor(publicOnly)
        return exchange(null, base, Route.MANIFEST, listOf(file), emptyList(), signPayload = null, opts) {
            AddonValidator.readManifest(it, opts) to it.toString()
        }
    }

    /**
     * `GET /search` (SPEC.md 4.2), with artists, albums and ids the manifest can't back dropped.
     * [circuit] false: a caller that keeps its own busy circuit (the named Tidal rung) asks
     * without this client's `Retry-After` gate, which then neither holds the call nor hears of it.
     * An Eclipse-family add-on is sent `q` alone, and its rows read through [AddonEclipse].
     */
    suspend fun search(
        addon: InstalledAddon, q: String, limit: Int = 20, paced: Boolean = false, circuit: Boolean = true,
    ): AddonResult<SearchResponse> {
        val opts = optionsFor(addon)
        val eclipse = addon.dialect == AddonDialect.ECLIPSE
        val params = if (eclipse) listOf("q" to q.take(200)) else listOf("q" to q.take(200), "limit" to limit.coerceIn(1, 200).toString())
        return call(addon, Route.SEARCH, emptyList(), params, paced = paced, circuit = circuit) {
            gateSearch(addon.manifest, if (eclipse) AddonEclipse.searchResponse(it, opts) else AddonValidator.searchResponse(it, opts))
        }
    }

    /**
     * `GET /resolve` (SPEC.md 4.3, draft 2): the add-on's own item for the same song as [query], or
     * null for no match. An item that breaks rule 3 (length) or rule 7 (`maxTier` below the asked
     * `minTier`) is rejected; its ISRC alone never is.
     *
     * Eclipse-family add-ons get `title`, `artist`, `isrc` and `durationMs` only; one that lists
     * `isrc` without `resolve` is asked `/resolve-isrc?isrc=`, and only for a song with an ISRC.
     */
    suspend fun resolve(addon: InstalledAddon, query: ResolveQuery, paced: Boolean = false): AddonResult<Track?> {
        val opts = optionsFor(addon)
        if (addon.dialect == AddonDialect.ECLIPSE) {
            val song = query.song
            if (addon.eclipse?.resolveViaIsrc == true) {
                val isrc = song.isrc?.takeIf { it.isNotBlank() } ?: return AddonResult.Ok(null)
                return call(addon, Route.RESOLVE, listOf("resolve-isrc"), listOf("isrc" to isrc), paced = paced) {
                    AddonEclipse.resolveIsrcResponse(it, song)
                }
            }
            val params = buildList {
                add("title" to song.title)
                add("artist" to song.artist)
                song.isrc?.takeIf { it.isNotBlank() }?.let { add("isrc" to it) }
                song.durationMs?.takeIf { it > 0 }?.let { add("durationMs" to it.toString()) }
            }
            return call(addon, Route.RESOLVE, emptyList(), params, paced = paced) {
                val item = AddonEclipse.resolveResponse(it, opts) ?: return@call null
                AddonValidator.checkResolve(item, query)
                gateIds(addon.manifest, item)
            }
        }
        return call(addon, Route.RESOLVE, emptyList(), resolveParams(query), paced = paced) {
            val item = AddonValidator.resolveResponse(it, opts).item ?: return@call null
            AddonValidator.checkResolve(item, query)
            gateIds(addon.manifest, item)
        }
    }

    /**
     * `GET /stream/{id}` (SPEC.md 4.4). An answer outside [request] is `quality_unavailable` for that
     * range, in every dialect, holding the answer ([AddonFailure.Refused.held]) for a lower range of
     * the same play; one marked encrypted is not playable. [circuit]: as [search].
     */
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
        val opts = optionsFor(addon)
        val dialect = addon.dialect
        val params = if (dialect == AddonDialect.ECLIPSE) listOf("quality" to qualityParam(addon, request)) else request.queryParameters()
        val ask = suspend {
            call(addon, route, listOf(segment, id), params, signPayload = id, paced = paced, circuit = circuit) {
                val info = when (dialect) {
                    AddonDialect.V0 -> AddonV0.streamInfo(it, opts)
                    AddonDialect.ECLIPSE -> AddonEclipse.streamInfo(it, opts)
                    AddonDialect.V1 -> AddonValidator.streamInfo(it, opts).also { info ->
                        if (info.encrypted == true) throw AddonContractException("/encrypted", "v1 defines no keys: not playable")
                    }
                }
                // SPEC.md 10.1 and phase 4 §2.3: an answer out of range is a quality miss, never a broken answer.
                if (info.tier !in request.minTier..request.tier || info.manifest !in request.manifest) {
                    throw AddonRefusalException(ErrorCode.QUALITY_UNAVAILABLE, held = info)
                }
                info
            }
        }
        val first = ask()
        // Eclipse's per-song "still preparing": a foreground play waits once, briefly, then moves on.
        val preparing = ((first as? AddonResult.Failed)?.failure as? AddonFailure.Refused)?.takeIf { it.preparing }
        if (preparing != null && !paced) {
            val waitMs = ((preparing.retryAfterSec ?: 1) * 1000L).coerceIn(0L, PREPARING_WAIT_MS)
            debug("${addon.id} ${route.name.lowercase()}: still preparing; asking once more in ${waitMs} ms")
            delay(waitMs)
            return ask()
        }
        return first
    }

    /**
     * The `quality` an Eclipse-family add-on is sent (phase 4 §2.3): the first option of its own
     * `quality` select, else `LOSSLESS` for a lossless range, `HIGH` for a high one, `LOW` below.
     */
    private fun qualityParam(addon: InstalledAddon, request: StreamRequest): String =
        addon.eclipse?.qualityOption ?: when {
            request.minTier.isLossless -> "LOSSLESS"
            request.tier >= Tier.HIGH -> "HIGH"
            else -> "LOW"
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

    suspend fun album(addon: InstalledAddon, id: String): AddonResult<Album> {
        val opts = optionsFor(addon)
        return call(addon, Route.ALBUM, listOf("album", id), emptyList(), paced = false) {
            AddonValidator.album(it, opts).let { a -> a.copy(tracks = a.tracks.map { t -> gateIds(addon.manifest, t) }) }
        }
    }

    suspend fun artist(addon: InstalledAddon, id: String): AddonResult<Artist> {
        val opts = optionsFor(addon)
        return call(addon, Route.ARTIST, listOf("artist", id), emptyList(), paced = false) {
            AddonValidator.artist(it, opts).let { a -> a.copy(topTracks = a.topTracks.map { t -> gateIds(addon.manifest, t) }) }
        }
    }

    suspend fun catalog(addon: InstalledAddon, id: String, genre: String? = null, limit: Int = 20, offset: Int = 0): AddonResult<CatalogPage> {
        val opts = optionsFor(addon)
        val params = buildList {
            if (genre != null) add("genre" to genre)
            add("limit" to limit.coerceIn(1, 100).toString())
            add("offset" to offset.coerceAtLeast(0).toString())
        }
        return call(addon, Route.CATALOG, listOf("catalog", id), params, paced = false) { AddonValidator.catalogPage(it, opts) }
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
        val result = exchange(addon, addon.base, route, path, withSettings, signPayload, optionsFor(addon), parse)
        if (circuit) report(addon.id, result)
        return result
    }

    /** The circuit's view of [result] (SPEC.md 7 "Host does"). */
    private suspend fun report(addonId: String, result: AddonResult<*>) {
        val key = rateKey(addonId)
        when (val f = (result as? AddonResult.Failed)?.failure) {
            null -> {
                rateGate.onAnswer(key)
                if (addonId in unauthorizedIds.value) unauthorizedIds.update { it - addonId }
            }
            is AddonFailure.Refused -> when (f.code) {
                ErrorCode.RATE_LIMITED -> rateGate.onRateLimited(key, f.retryAfterSec?.let { it * 1000L })
                // "Pauses metered calls until then": the add-on answered, and its other routes go on.
                ErrorCode.QUOTA_EXCEEDED -> {
                    rateGate.onAnswer(key)
                    rateGate.onRateLimited(meteredKey(addonId), f.retryAfterSec?.let { it * 1000L })
                }
                // An Eclipse-family song still being prepared is about that song, not the add-on.
                ErrorCode.UNAVAILABLE -> when {
                    f.preparing -> rateGate.onAnswer(key)
                    f.retryAfterSec != null -> rateGate.onRateLimited(key, f.retryAfterSec * 1000L)
                    else -> rateGate.onFailure(key)
                }
                // "Stops calling; asks the user": a long pause, and its page says "Needs a new link"
                // ([unauthorized]). An add-on that serves only BitChord is not waiting for a new link.
                ErrorCode.UNAUTHORIZED -> {
                    rateGate.onRateLimited(key, UNAUTHORIZED_PAUSE_MS)
                    if (f.onlyBitChord) onlyBitChordIds.update { it + addonId } else unauthorizedIds.update { it + addonId }
                }
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
        opts: AddonValidator.Options,
        parse: (JsonElement) -> T,
    ): AddonResult<T> {
        val tag = "${addon?.id ?: "manifest"} ${route.name.lowercase()}"
        val baseUrl = base.takeIf { AddonValidator.isAllowedUrl(it, opts) }?.toHttpUrlOrNull()
            ?: run {
                if (AddonValidator.isLocalAddressUrl(base, opts)) warn("$tag: ${AddonEclipse.PRIVATE_ADDRESS}")
                return AddonResult.Failed(AddonFailure.Network)
            }
        var url = baseUrl.newBuilder().apply {
            segments.forEach { addPathSegment(it) }
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val deadline = nowMs() + route.timeoutMs
        val firstParty = signPayload != null && trust.isFirstParty(base)
        val client = if (opts.publicOnly) publicHttp else http
        val eclipse = addon?.dialect == AddonDialect.ECLIPSE
        var redirects = 0
        var mayResign = true
        try {
            while (true) {
                val sameOrigin = url.sameOrigin(baseUrl)
                // Building the request throws IllegalArgumentException, with the value in its
                // message, for a token or header value a request can't carry: caught below.
                val builder = Request.Builder().url(url).get()
                    .header("Accept", "application/json")
                    .header("User-Agent", userAgent)
                val token = addon?.token?.takeIf { route != Route.MANIFEST }
                if (sameOrigin && addon != null && token != null && addon.manifest.auth?.token == TokenPlacement.HEADER) {
                    builder.header(Auth.HEADER_NAME, token)
                }
                val signed = sameOrigin && firstParty && signer.sign(builder, signPayload!!)
                val remaining = deadline - nowMs()
                if (remaining <= 0) return AddonResult.Failed(AddonFailure.Timeout)
                // The call timeout only starts once OkHttp's dispatcher runs the call: this also
                // bounds the wait for a connection slot.
                val reply = withTimeoutOrNull(remaining) { execute(client, builder.build(), remaining, adoptServerTime = signed) }
                    ?: run {
                        debug("$tag: timed out")
                        return AddonResult.Failed(AddonFailure.Timeout)
                    }
                if (reply.code in 300..399 && reply.location != null) {
                    val next = url.resolve(reply.location)
                    if (next == null || ++redirects > MAX_REDIRECTS || !AddonValidator.isAllowedUrl(next.toString(), opts)) {
                        warn("$tag: redirect refused${if (next != null && AddonValidator.isLocalAddressUrl(next.toString(), opts)) " (private address)" else ""}")
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
                    val refused = refusal(reply, element, eclipse)
                    if (refused.onlyBitChord) warn("$tag: ${AddonEclipse.ONLY_BITCHORD}")
                    return AddonResult.Failed(
                        if (eclipse && (route == Route.STREAM || route == Route.DOWNLOAD) && refused.isPreparing()) refused.copy(preparing = true) else refused,
                    )
                }
                element ?: return AddonResult.Failed(AddonFailure.InvalidResponse("", "not JSON"))
                return try {
                    AddonResult.Ok(parse(element))
                } catch (e: AddonRefusalException) {
                    debug("$tag: ${e.code.wire}")
                    AddonResult.Failed(AddonFailure.Refused(e.code, reply.code, null, null, held = e.held?.let(::HeldStream)))
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

    /** An Eclipse-family "still preparing": a 503 with a `Retry-After` of 30 s or less (phase 4 §2.3). */
    private fun AddonFailure.Refused.isPreparing(): Boolean =
        status == 503 && retryAfterSec != null && retryAfterSec <= PREPARING_MAX_RETRY_AFTER_S

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
    private fun refusal(reply: Reply, element: JsonElement?, eclipse: Boolean = false): AddonFailure.Refused {
        val info = element?.let { runCatching { AddonValidator.errorInfo(it) }.getOrNull() }
        val header = reply.retryAfterHeader?.trim()?.toIntOrNull()?.takeIf { it in 1..86_400 }
        // BitChord's own error body carries `retryAfter` at the top (phase 4 §2.3).
        val topLevel = if (eclipse) {
            ((element as? kotlinx.serialization.json.JsonObject)?.get("retryAfter") as? kotlinx.serialization.json.JsonPrimitive)
                ?.content?.toDoubleOrNull()?.toInt()?.takeIf { it in 1..86_400 }
        } else {
            null
        }
        return AddonFailure.Refused(
            code = if (reply.code in 500..599 && reply.code != 503) ErrorCode.UNAVAILABLE else ErrorCode.of(reply.code, info?.code),
            status = reply.code,
            retryAfterSec = info?.retryAfter ?: header ?: topLevel,
            quota = info?.quota,
            onlyBitChord = reply.code == 401 && reply.wwwAuthenticate?.trim()?.startsWith("BitChord", ignoreCase = true) == true,
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
        /** A 401's `WWW-Authenticate` (BitChord's attestation names itself there). */
        val wwwAuthenticate: String? = null,
    )

    /** Runs [request] with a call timeout of [timeoutMs]; cancelling the coroutine cancels the call. */
    private suspend fun execute(client: OkHttpClient, request: Request, timeoutMs: Long, adoptServerTime: Boolean): Reply = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
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
        val www = if (r.code == 401) r.header("WWW-Authenticate") else null
        if (r.code in 300..399) return Reply(r.code, r.header("Location"), null, false, null, false)
        val source = r.body?.source() ?: return Reply(r.code, null, r.header("Retry-After"), adopted, null, false, named, www)
        if (source.request(Limits.BODY_BYTES + 1)) return Reply(r.code, null, r.header("Retry-After"), adopted, null, true, named, www)
        val text = source.buffer.readUtf8()
        return Reply(r.code, null, r.header("Retry-After"), adopted, text, false, named, www)
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

        /** An Eclipse-family 503 with a `Retry-After` up to this is "still preparing" this song (phase 4 §2.3). */
        const val PREPARING_MAX_RETRY_AFTER_S = 30

        /** How long a foreground play waits for a song still being prepared, once: min(Retry-After, this). */
        const val PREPARING_WAIT_MS = 3_000L

        /** The [AddonRateGate] key of add-on [addonId]: its own bucket and breaker. */
        fun rateKey(addonId: String): String = "addon_$addonId"

        /** The [AddonRateGate] key of add-on [addonId]'s allowance: `quota_exceeded` pauses only this. */
        fun meteredKey(addonId: String): String = "addon_${addonId}_metered"

        /** Add-on text (JSON keys, header names in error paths) as a log can carry it: short, no control characters. */
        internal fun loggable(s: String): String =
            s.take(120).map { if (it < ' ' || it in '\u007f'..'\u009f') ' ' else it }.joinToString("")

        /** The manifest's usual file name under an add-on's base (SPEC.md 3). */
        const val MANIFEST_FILE = "manifest.json"

        /**
         * The base of a manifest URL (SPEC.md 3): without its final file name, ending in `/`. The
         * file is `manifest.json`, or any other `*.json` (BitChord-style links, add-ons plan phase
         * 4 §2.2). Null when not a manifest URL.
         */
        fun baseOf(manifestUrl: String): String? {
            val u = manifestUrl.trim()
            val base = when {
                u.endsWith("/") -> u
                isJsonFile(u.substringAfterLast('/')) -> u.substringBeforeLast('/') + "/"
                else -> return null
            }
            return base.takeIf { it.toHttpUrlOrNull() != null }
        }

        /** The manifest's file name in [manifestUrl]: its last path part when that is a `*.json`, else [MANIFEST_FILE]. */
        fun manifestFileOf(manifestUrl: String): String =
            manifestUrl.trim().substringAfterLast('/').takeIf(::isJsonFile) ?: MANIFEST_FILE

        private fun isJsonFile(name: String): Boolean =
            name.length > ".json".length && name.endsWith(".json") && name.none { it == '?' || it == '#' }

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
