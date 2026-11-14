package com.stash.core.addons

import com.stash.addons.contract.Delivery
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

    /** Forgets the manifests kept under [keys]. A store that can't delete one writes nothing in its place. */
    suspend fun forget(keys: Set<String>) {
        keys.forEach { write(it, "") }
    }
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

/**
 * Why the signed index turned an add-on off. The app shows its own sentence for each, never text
 * from the file. A reason this version doesn't know is [OTHER]: the add-on is blocked all the same,
 * with a sentence that gives no reason. A newer list must never make an older app drop its blocks.
 */
enum class BlockReason(val wire: String?) {
    HARMFUL("harmful"),
    BROKEN("broken"),
    IMPERSONATION("impersonation"),

    /** A reason from a later list. */
    OTHER(null);

    companion object {
        /** The reason an index wrote as [wire]; any word this version doesn't know is [OTHER]. */
        fun of(wire: String): BlockReason = entries.firstOrNull { it.wire == wire } ?: OTHER
    }
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
    /**
     * "each song you play": Stash asks it about the song that is playing. It is a stream source
     * ([streams]), or it gives lyrics.
     */
    PLAYS,

    /**
     * "the next song in your queue": a stream source is also asked about the song that is up next,
     * whether or not it ends up played (the look-ahead). Lyrics are asked for the playing song only.
     */
    NEXT_UP,

    /**
     * "each song you save": Stash would take a song to keep from it ([keeps]), which is exactly when
     * its preview and its page show "Downloads"; or it gives lyrics, which are fetched for a song
     * once it is downloaded. A download from an add-on with no `/download` route goes through its
     * `/resolve` and `/stream`, so its server learns of songs the user saves and never played
     * (review-882 J3). Listing `download` is not what decides.
     */
    SAVES;

    companion object {
        /**
         * What Stash will ask the add-on with manifest [m] about. Nothing here is "what you search
         * for": nothing the user types in Search is ever sent to an add-on. An add-on's `/search`
         * is asked for a song Stash is looking for, by its artist and title, as its `/resolve` is.
         * [allowsDownloads]: false for an Eclipse-family manifest that says `allowDownloads: 0`.
         */
        fun of(m: Manifest, allowsDownloads: Boolean = true): Set<AddonSees> = buildSet {
            val lyrics = m.offers(Resource.LYRICS)
            if (streams(m) || lyrics) add(PLAYS)
            if (streams(m)) add(NEXT_UP)
            if (keeps(m, allowsDownloads) || lyrics) add(SAVES)
        }

        /**
         * Stash would ask this add-on for a song to play: it lists `stream`, has a way to find a
         * song (`resolve`, or `search` matched on the phone) and plays in a delivery the player
         * takes. The stream chain's own rule (`:core:media` `AddonStreamRungs.canStream`).
         */
        fun streams(m: Manifest): Boolean {
            if (!m.offers(Resource.STREAM)) return false
            if (!m.offers(Resource.RESOLVE) && !m.offers(Resource.SEARCH)) return false
            val caps = m.stream ?: return false
            return caps.knownManifests.any { it == Delivery.NONE || it == Delivery.DASH }
        }

        /** [of] for a manifest as it was read, with what an Eclipse-family one says about downloads. */
        fun of(read: AddonManifest): Set<AddonSees> = of(read.manifest, read.eclipse?.allowDownloads != false)

        /** The names kept in a sealed entry. */
        fun namesOf(m: Manifest, allowsDownloads: Boolean = true): Set<String> = of(m, allowsDownloads).map { it.name }.toSet()

        fun namesOf(read: AddonManifest): Set<String> = of(read).map { it.name }.toSet()

        /**
         * Stash would ask this add-on for a song to keep: it finds songs, has a stream or a download
         * to give, allows downloads, and can answer a lossless tier (or doesn't say which it has)
         * in a delivery the downloader takes. The download seam's own rule
         * (`:data:download` `AddonDownloadSources.canDownloadLossless`), kept here so what the
         * user is told an add-on sees is what Stash will ask it. One condition for three things:
         * "and save" on the sheet, the same words on the add-on's page, and the "Downloads" use.
         */
        fun keeps(m: Manifest, allowsDownloads: Boolean = true): Boolean {
            if (!allowsDownloads) return false
            if (!m.offers(Resource.RESOLVE) && !m.offers(Resource.SEARCH)) return false
            if (!m.offers(Resource.DOWNLOAD) && !m.offers(Resource.STREAM)) return false
            val caps = m.stream ?: return false
            val tiers = caps.knownTiers
            if (tiers.isNotEmpty() && tiers.none { it.isLossless }) return false
            return caps.knownManifests.any { it == Delivery.NONE || it == Delivery.DASH }
        }
    }
}
