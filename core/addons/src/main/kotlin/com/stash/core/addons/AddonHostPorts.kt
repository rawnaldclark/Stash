package com.stash.core.addons

import okhttp3.Request
import okhttp3.Response

/**
 * What [HttpAddonClient] needs from the rest of the app, as small interfaces so this module
 * depends on none of it. `:data:download` binds them to the app's existing pieces:
 * [AddonRateGate] to `AggregatorRateLimiter`, [AddonTrust] to the signed lossless config, and
 * [AddonInstallSigner] to `RelaySigner` (see `data/download/.../lossless/addon/AddonHostModule`).
 */

/**
 * The `Retry-After` circuit per add-on, keyed by [HttpAddonClient.rateKey]. A paused add-on is not
 * asked at all until its pause ends.
 */
interface AddonRateGate {
    /**
     * Whether the add-on may be asked now: false while its circuit is open (a `Retry-After`, or
     * repeated failures). [paced] (background work: downloads, sweeps) also waits for the add-on's
     * token bucket; a foreground call (a tap, playback) only checks the circuit.
     */
    suspend fun admit(key: String, paced: Boolean): Boolean

    /** An answer came back: an item, a miss or a refusal, not a failure. */
    suspend fun onAnswer(key: String)

    /** `rate_limited` or `quota_exceeded` (429), or `unavailable` with a `Retry-After`: pause for [retryAfterMs]. */
    suspend fun onRateLimited(key: String, retryAfterMs: Long?)

    /** Timeout, network failure, an invalid answer, or `unavailable` without a `Retry-After`. */
    suspend fun onFailure(key: String)
}

/**
 * Which add-ons are Stash's own. The rule `RelaySigner.isOwnerRelay` used before the Qobuz pool
 * went (#871): an add-on is first-party when its base URL is one the ECDSA-signed lossless config
 * names, never by anything the add-on says about itself.
 */
interface AddonTrust {
    fun isFirstParty(base: String): Boolean
}

/**
 * Stash's install-signing headers (`X-Stash-Install`, `X-Stash-Ts`, `X-Stash-Auth`,
 * `X-Stash-Entitlement`, `X-Stash-App-Build`), outside the add-on contract (SPEC.md 6). The client
 * calls this only for first-party add-ons ([AddonTrust]).
 */
interface AddonInstallSigner {
    /**
     * Adds the headers to [builder] for a request naming [payload] (a stream or download id; the
     * relay's own signing message). Returns true when it signed.
     */
    suspend fun sign(builder: Request.Builder, payload: String): Boolean

    /** A 401 that names the server's clock: learn the offset and return true so the call is signed again once. */
    fun adoptServerTime(response: Response): Boolean
}

/** No pausing at all: for hand-built clients in tests. */
object OpenAddonRateGate : AddonRateGate {
    override suspend fun admit(key: String, paced: Boolean) = true
    override suspend fun onAnswer(key: String) = Unit
    override suspend fun onRateLimited(key: String, retryAfterMs: Long?) = Unit
    override suspend fun onFailure(key: String) = Unit
}

/** Nothing is first-party: for hand-built clients in tests. */
object NoFirstPartyAddons : AddonTrust {
    override fun isFirstParty(base: String) = false
}

/** Never signs: for hand-built clients in tests. */
object UnsignedAddons : AddonInstallSigner {
    override suspend fun sign(builder: Request.Builder, payload: String) = false
    override fun adoptServerTime(response: Response) = false
}

/** Host-wide choices the client follows, bound by the app (`:data:download` `AddonHostModule`). */
interface AddonHostOptions {
    /**
     * SPEC.md 1.1's development exception: `http:` on 127.0.0.1, localhost and [::1]. Debug builds
     * only, for an add-on under `wrangler dev` reached over `adb reverse`; never in a release build.
     */
    val allowLocalHttp: Boolean

    /**
     * The `User-Agent` on every add-on request, in every dialect: `Stash/<versionName>` (add-ons plan
     * phase 4 §2.3). An add-on author can tell Stash apart and say no, just as Stash asks of other
     * apps for its own Tidal add-on. Without the app's own interceptors, OkHttp would send its own.
     */
    val userAgent: String get() = DEFAULT_USER_AGENT

    companion object {
        const val DEFAULT_USER_AGENT: String = "Stash"
    }
}

/**
 * Told when the add-on media client refused to fetch a stream an add-on answered: the fetch
 * pointed at the user's own network (a private address in a redirect or a segment, a name that
 * resolves there) or broke the URL rule (review-882 round 2, F7). A stream that cannot be
 * fetched for that reason will not fetch the next time either, so the host stops offering it for
 * the song and lets the next source have it.
 *
 * Called on the fetching thread, before the refusal reaches the caller of the fetch.
 */
fun interface AddonMediaRefusals {
    /**
     * [streamUrl]: the URL the add-on answered for the stream (the refused fetch's own first URL,
     * or the manifest a refused segment belongs to, [AddonStreamTag]). Never to be logged.
     * [why]: what was refused, which says how long the refusal holds.
     */
    fun refused(streamUrl: String, why: MediaRefusal)
}

/** What the add-on media client's guard refused ([AddonMediaRefusals]). */
enum class MediaRefusal {
    /**
     * The URL itself, or a redirect's: an address on the user's own network written into it, or
     * not an https URL on a public host. Also a private peer behind a public answer (the last
     * check). True on any network.
     */
    URL,

    /**
     * The DNS filter ([NoPublicAddressException]): every answer for the host was private. That is
     * what the resolver the phone asked said (a blocker answering `0.0.0.0`, a work network's
     * split DNS), so it can be otherwise on another network (review-882 round 3, R3).
     */
    NO_PUBLIC_ADDRESS,
}

/** Nobody is told: for hand-built clients in tests. */
object NoMediaRefusals : AddonMediaRefusals {
    override fun refused(streamUrl: String, why: MediaRefusal) = Unit
}

/**
 * A request tag (`Request.tag(AddonStreamTag::class.java)`): the stream a media request belongs
 * to, for a request that is not the stream's own URL (a DASH segment, tagged with its manifest's
 * URL). The media client's guard reports a refusal under it.
 */
class AddonStreamTag(val streamUrl: String)

/** The release rule: https only. */
object StrictAddonHost : AddonHostOptions {
    override val allowLocalHttp: Boolean = false
}

/**
 * Where the registry keeps each add-on's last good manifest across restarts, so an offline cold
 * start keeps the add-ons it had (plan §5.3: with Tidal as the main lossless source, losing its
 * manifest would lose FLAC). [key] is a hash of the manifest URL, never the URL (it can carry a
 * token), and the JSON is the add-on's own manifest, which holds no secret of ours.
 */
interface AddonManifestStore {
    suspend fun read(key: String): String?
    suspend fun write(key: String, json: String)

    /** Forgets every manifest whose key is not in [keys]. */
    suspend fun retain(keys: Set<String>)
}

/** Keeps nothing: for hand-built registries in tests. */
object NoManifestStore : AddonManifestStore {
    override suspend fun read(key: String): String? = null
    override suspend fun write(key: String, json: String) = Unit
    override suspend fun retain(keys: Set<String>) = Unit
}
