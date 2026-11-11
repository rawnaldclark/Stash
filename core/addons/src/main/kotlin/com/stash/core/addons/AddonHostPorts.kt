package com.stash.core.addons

import com.stash.addons.contract.Manifest
import com.stash.addons.contract.Resource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
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

    /** Ends any pause now: the user replaced the add-on's link (add-ons plan phase 4 §2.9). */
    suspend fun reset(key: String) = Unit
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

/**
 * Where an add-on stands with Stash (add-ons plan phase 4 §1.6). Decided only by the signed config
 * and the signed index ([AddonCatalog]), never by anything an add-on says about itself.
 */
enum class Trust {
    /** Stash's own: it gets the install signature ([AddonTrust]). Marked Official. */
    FIRST_PARTY,

    /** Run by Stash: a default of the signed config, or listed in the signed index. No sheet. */
    OFFICIAL,

    /** Everything else: "Not made by Stash". The sheet once, the base rules always. */
    UNOFFICIAL,

    /** The signed index turned it off: never loaded or asked. */
    BLOCKED,
}

/** Why the signed index turned an add-on off. The app shows its own sentence for each, never text from the file. */
enum class BlockReason(val wire: String) {
    HARMFUL("harmful"),
    BROKEN("broken"),
    IMPERSONATION("impersonation"),
}

/**
 * One add-on of the official list (the signed index's `addons`, phase 4 §1.3). [manifestUrl] null:
 * an add-on Stash turns on by default, whose manifest comes from the signed config by [id].
 */
data class OfficialEntry(
    val id: String,
    val name: String,
    val summary: String,
    val icon: String? = null,
    val manifestUrl: String? = null,
)

/**
 * The official list and the blocklist (phase 4 §1.6), bound in `:data:download` to the signed index
 * (`SignedIndexAddonCatalog`).
 */
interface AddonCatalog {
    /**
     * Where the add-on at [base] stands. [id]: its manifest id when known; a block can name one id
     * on an origin, and with no id known only a block of the whole origin applies.
     */
    fun trustOf(base: String, id: String?): Trust

    /** Why [trustOf] says [Trust.BLOCKED]; null otherwise. */
    fun blockReason(base: String, id: String?): BlockReason?

    /** The official add-ons a user can add (those with a manifest of their own); live. */
    val official: StateFlow<List<OfficialEntry>>

    /** Emits whenever what [trustOf] answers may have changed (a new index or config was applied). */
    val changes: Flow<Any?>

    /** Returns once the copy kept from the last run has been read, so a cold start never loads a blocked add-on. */
    suspend fun awaitLoaded() = Unit
}

/** No index: nothing is official, nothing is blocked. For hand-built registries in tests. */
object NoAddonCatalog : AddonCatalog {
    override fun trustOf(base: String, id: String?): Trust = Trust.UNOFFICIAL
    override fun blockReason(base: String, id: String?): BlockReason? = null
    override val official: StateFlow<List<OfficialEntry>> = MutableStateFlow(emptyList<OfficialEntry>()).asStateFlow()
    override val changes: Flow<Any?> = emptyFlow()
}

/**
 * The add-ons Stash turns on by default that the user switched off in Settings › Add-ons, by
 * manifest id (phase 4 §2.9: a default has only the switch). An add-on the user added carries its
 * switch in its own entry ([UserAddonEntry.enabled]). Bound in `:data:download` (`PlayOrderSwitches`).
 */
interface AddonSwitches {
    val defaultsOff: Flow<Set<String>>
}

/** Every default on: for hand-built registries in tests. */
object AllDefaultsOn : AddonSwitches {
    override val defaultsOff: Flow<Set<String>> = flowOf(emptySet())
}

/**
 * What an add-on's server gets to see (phase 4 §2.7), read from its manifest's resources. The
 * "Not made by Stash" sheet names these, and the user's agreement is kept as their names
 * ([UserAddonEntry.consent]): an add-on that later wants more waits until the user agrees again.
 * Every server add-on also sees the phone's internet address.
 */
enum class AddonSees {
    /** "what you search for": it lists `search`. */
    SEARCHES,

    /** "the songs you play": it lists `stream` or `resolve`. */
    PLAYS,

    /** "and save": it lists `download`. */
    SAVES;

    companion object {
        fun of(m: Manifest): Set<AddonSees> = buildSet {
            if (m.offers(Resource.SEARCH)) add(SEARCHES)
            if (m.offers(Resource.STREAM) || m.offers(Resource.RESOLVE)) add(PLAYS)
            if (m.offers(Resource.DOWNLOAD)) add(SAVES)
        }

        /** The names kept in a sealed entry. */
        fun namesOf(m: Manifest): Set<String> = of(m).map { it.name }.toSet()
    }
}
