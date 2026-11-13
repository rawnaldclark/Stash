package com.stash.core.addons

import android.util.Log
import com.stash.addons.contract.AddonKind
import com.stash.addons.contract.Manifest
import com.stash.addons.contract.Resource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * An add-on that is on, with the manifest it answered.
 *
 * @property base Where its routes live: the manifest URL without `manifest.json`, ending in `/`
 *   (SPEC.md 3). Holds a path token when the add-on uses one, so it is a secret: never logged.
 * @property token The `X-Access-Token` for an add-on whose manifest says `auth.token: "header"`.
 *   Only printable ASCII (a request header can't carry anything else): any other value is refused
 *   here, with a message that never repeats it.
 * @property userAdded Added by the user ("Add from a link") rather than a default from the
 *   signed config. Where it is asked is the play order's call (`PlayOrder`, add-ons plan phase 4
 *   §2.8): after Stash's own lossless sources until the user drags it.
 * @property firstParty A default the signed config marks `first_party`: Stash's own, which gets
 *   the install signature ([AddonTrust] decides that on its own, from the same list).
 * @property settings The add-on's setting values, sent as query parameters (SPEC.md 2.2).
 * @property dialect The contract its manifest was written to: the client asks and reads each its own way.
 * @property eclipse What an Eclipse-family manifest said that v1 has no field for; null otherwise.
 * @property trust Where it stands with Stash (phase 4 §1.6), from the signed config and index.
 *   Only [Trust.UNOFFICIAL] add-ons follow the base rules (no address on the user's own network);
 *   one that is [Trust.BLOCKED] is never installed at all.
 */
data class InstalledAddon(
    val base: String,
    val manifest: Manifest,
    val userAdded: Boolean = false,
    val token: String? = null,
    val settings: Map<String, String> = emptyMap(),
    val firstParty: Boolean = false,
    val dialect: AddonDialect = if (manifest.stash == 0) AddonDialect.V0 else AddonDialect.V1,
    val eclipse: EclipseTraits? = null,
    val trust: Trust = if (firstParty) Trust.FIRST_PARTY else if (userAdded) Trust.UNOFFICIAL else Trust.OFFICIAL,
) {
    init {
        require(token == null || isHeaderSafe(token)) { "an add-on token must be printable ASCII" }
    }

    val id: String get() = manifest.id

    fun offers(resource: Resource): Boolean = manifest.offers(resource)

    /** A download source only when the add-on allows it (BitChord's `allowDownloads: 0` says no). */
    val allowsDownloads: Boolean get() = eclipse?.allowDownloads != false

    /** Never names [base] or [token]: logs and crash reports carry only the id. */
    override fun toString(): String =
        "InstalledAddon(${manifest.id} ${manifest.version}${if (dialect != AddonDialect.V1) ", ${dialect.name.lowercase()}" else ""}" +
            "${if (userAdded) ", user" else ""}${if (firstParty) ", first-party" else ""}${if (trust == Trust.UNOFFICIAL) ", unofficial" else ""})"

    companion object {
        /**
         * True when [value] can go in a request header: not empty, tab or printable ASCII only. The
         * check "Add from a link" runs before keeping a token, so a bad one is refused with a
         * message of our own; OkHttp's would repeat the value.
         */
        fun isHeaderSafe(value: String): Boolean = value.isNotEmpty() && value.all { it == '\t' || it in ' '..'~' }
    }
}

/**
 * The first-party add-ons a named rung of the app serves itself (add-ons plan phase 2 §5.1). The
 * Tidal add-on (`tidal`, first-party) is played and downloaded by the Tidal rung, which carries
 * about ten policies the generic add-on rung doesn't have yet, so the generic seams never list it
 * too. A third-party add-on that happens to use the id is not owned: it goes through the seams.
 */
object NamedRungAddons {
    const val TIDAL: String = "tidal"

    fun owns(addon: InstalledAddon): Boolean = addon.firstParty && addon.id == TIDAL
}

/**
 * The add-ons that are on, in play order. Every add-on seam reads it: the stream rungs
 * (`core:media` `AddonStreamRungs`), the download sources (`data:download` `AddonDownloadSources`,
 * reached through `LosslessSourceRegistry.orderedSources()`) and the lyrics chain
 * (`data:lyrics` `AddonLyricsSource`). Hilt multibindings are compile-time, so add-ons that come
 * and go at runtime come in through here, never through `@IntoSet`.
 */
interface AddonRegistry {
    /**
     * The add-ons that are on, user-added ones first, then the defaults, each in its list's order.
     * Never one that is switched off, turned off by Stash, or waiting for the user to agree to more.
     */
    val addons: StateFlow<List<InstalledAddon>>

    /**
     * [addons] without the ones a named rung serves itself ([NamedRungAddons]): what the generic
     * seams walk. Empty, and no work, when none are on.
     */
    fun generic(): List<InstalledAddon> {
        val now = addons.value
        return if (now.isEmpty()) emptyList() else now.filterNot(NamedRungAddons::owns)
    }

    /** Those of [generic] that list [resource], in order. Empty, and no work, when none are on. */
    fun offering(resource: Resource): List<InstalledAddon> {
        val now = generic()
        return if (now.isEmpty()) emptyList() else now.filter { it.offers(resource) }
    }

    /** The first-party add-on with manifest id [id] whose manifest is loaded, or null. */
    fun firstParty(id: String): InstalledAddon? = addons.value.firstOrNull { it.firstParty && it.id == id }
}

/**
 * One default add-on: the web player's `plugins.json` entry (`{ manifest, enabled }`), from the
 * signed lossless config's `default_addons`. [firstParty]: the config marks it Stash's own.
 */
data class DefaultAddonEntry(val manifestUrl: String, val enabled: Boolean = true, val firstParty: Boolean = false) {
    override fun toString(): String = "DefaultAddonEntry(enabled=$enabled, firstParty=$firstParty)"
}

/** The default add-ons: the signed config's list (`:data:download` `SignedConfigDefaultAddons`). */
interface DefaultAddons {
    val entries: StateFlow<List<DefaultAddonEntry>>
}

/** No default add-ons: for hand-built registries in tests. */
object NoDefaultAddons : DefaultAddons {
    override val entries: StateFlow<List<DefaultAddonEntry>> = MutableStateFlow(emptyList<DefaultAddonEntry>()).asStateFlow()
}

/**
 * One add-on the user added from a link. [manifestUrl] and [token] are secrets: kept encrypted
 * (SPEC.md 6), never logged, never in diagnostics or backups.
 *
 * @property id The add-on's manifest id as it was when added (or its link last replaced), so its
 *   row, its place in the play order and a block of that one id are known without asking it.
 *   Null for an entry sealed before phase 4.
 * @property name Its name then, for its row while it is off or can't be reached.
 * @property consent What the user agreed its server may see ([AddonSees] names), from the "Not
 *   made by Stash" sheet (phase 4 §2.7). Null: never asked (sealed before the sheet existed, or
 *   Official when added), and nothing is held back.
 * @property addedAt When it was added, epoch milliseconds.
 */
data class UserAddonEntry(
    val manifestUrl: String,
    val token: String? = null,
    val enabled: Boolean = true,
    val kind: UserAddonKind = UserAddonKind.MANIFEST,
    val id: String? = null,
    val name: String? = null,
    val consent: Set<String>? = null,
    val addedAt: Long? = null,
) {
    override fun toString(): String =
        "UserAddonEntry(kind=$kind, id=$id, enabled=$enabled, token=${if (token != null) "set" else "none"})"
}

/** What a [UserAddonEntry] is. */
enum class UserAddonKind {
    /** An add-on added from a link: [UserAddonEntry.manifestUrl] is its manifest. */
    MANIFEST,

    /**
     * The user's own Tidal server, moved out of the old Custom Tidal server field (add-ons plan
     * phase 4 §2.10): [UserAddonEntry.manifestUrl] is the server's base and the token its
     * `X-Access-Token`. It serves no manifest; the Tidal rung plays it over the legacy wire, and the
     * registry never loads it. At most one.
     */
    OWN_TIDAL_SERVER,
}

/** The user's own add-ons, in their order (`:data:download` `EncryptedUserAddons`). */
interface UserAddons {
    val entries: StateFlow<List<UserAddonEntry>>

    /** [entries] once the store has been read: [entries] holds an empty list until then. */
    suspend fun loadedEntries(): List<UserAddonEntry> = entries.value

    /**
     * True when the stored list is there but could not be opened this run (the Keystore failed):
     * the user's add-ons are not known right now, which is not the same as having none. Settings
     * says so instead of showing an empty list.
     */
    val unreadable: StateFlow<Boolean> get() = READABLE

    companion object {
        private val READABLE: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    }
}

/** No user add-ons: for hand-built registries in tests. */
object NoUserAddons : UserAddons {
    override val entries: StateFlow<List<UserAddonEntry>> = MutableStateFlow(emptyList<UserAddonEntry>()).asStateFlow()
}

/** Why an entry the registry knows is not among [AddonRegistry.addons]. */
enum class AddonHold {
    /** Switched off in Settings › Add-ons. */
    OFF,

    /** The signed index turned it off ([Trust.BLOCKED]): never loaded or asked. */
    BLOCKED,

    /** Its manifest now lists more than the user agreed its server may see: it waits until they agree. */
    WANTS_MORE,

    /** No manifest could be read, and none is kept. */
    NOT_LOADED,

    /**
     * Its link now serves a manifest with another id than the one the user approved: it is no
     * longer the add-on they added, and is not used until they remove it or add it again.
     */
    CHANGED,

    /** Another add-on that is on has its id (one of Stash's own beats a user's): this copy is not used. */
    REPLACED,

    /**
     * It now says it serves only the genuine BitChord (`bitchordAuth` in its manifest, or BitChord's
     * own 401 on a call). Stash respects that: it is not asked again, kept manifest or not.
     */
    ONLY_BITCHORD,
}

/**
 * One entry the registry knows, on or held, for Settings › Add-ons. Names no URL and no token:
 * [key] is a hash of the manifest URL and [host] only its host.
 */
data class AddonEntryState(
    /** [InTreeAddonRegistry.keyOf] of the entry's manifest URL. */
    val key: String,
    val userAdded: Boolean,
    val firstParty: Boolean,
    val host: String,
    val trust: Trust,
    /** Why Stash turned it off, when [trust] is [Trust.BLOCKED]. */
    val blockReason: BlockReason?,
    /** Its manifest as last read, kept copies included; null when none is known. */
    val manifest: AddonManifest?,
    /** Null: it is on, among [AddonRegistry.addons]. */
    val hold: AddonHold?,
    /** Its last manifest read was refused because it asks for a newer Stash. */
    val needsNewerStash: Boolean = false,
)

/**
 * The in-tree [AddonRegistry]: the user's add-ons and the defaults, their manifests read with
 * [HttpAddonClient] by [refresh].
 *
 * **Kept across failures and restarts.** An add-on whose manifest can't be read this time keeps
 * its last good one: the one it had this run, else the one [AddonManifestStore] kept from an
 * earlier run ([loadPersisted] publishes those at start, with no network). A default list must
 * never cost everyone FLAC over one failed fetch or an offline cold start (plan §5.3). Only server
 * add-ons are loaded here; phone add-ons are phase 3.
 *
 * **When it reads** ([start]): whenever either list changes, when the signed index lifts a block,
 * and on every applied config; each time only the add-ons that haven't answered in the last
 * [MIN_REFRESH_MS] (SPEC.md 3: hosts re-read a manifest at most every 10 minutes), so a cold
 * start, where all three happen within a second, reads each add-on once. Every pass publishes the
 * kept manifests first, reads the add-ons side by side, and one add-on's failure never costs
 * another ([refresh]).
 *
 * **Ids.** An id is on at most once. A first-party default beats a user add-on with the same id
 * (the Tidal rung's switch must not be turned off by adding a link); otherwise the user's own wins.
 *
 * **What is never on** (add-ons plan phase 4), each listed in [entries] with its [AddonHold]:
 *  - an add-on the signed index blocks ([AddonCatalog], §1.6): not even its manifest is asked for;
 *  - one that is switched off: the user's own by its entry, a default by [AddonSwitches];
 *  - an add-on Stash doesn't run whose manifest now lists more than the user agreed it may see
 *    (§2.7): it waits until they agree again;
 *  - a user's add-on whose link now serves a manifest with another id than the one they approved.
 */
@Singleton
class InTreeAddonRegistry @Inject constructor(
    private val defaults: DefaultAddons,
    private val users: UserAddons,
    private val client: HttpAddonClient,
    private val store: AddonManifestStore,
    private val catalog: AddonCatalog = NoAddonCatalog,
    private val switches: AddonSwitches = AllDefaultsOn,
) : AddonRegistry {

    private val state = MutableStateFlow<List<InstalledAddon>>(emptyList())
    override val addons: StateFlow<List<InstalledAddon>> = state.asStateFlow()

    private val entryState = MutableStateFlow<List<AddonEntryState>>(emptyList())

    /** Every entry: the user's add-ons (on or off), then the defaults; live. For Settings › Add-ons. */
    val entries: StateFlow<List<AddonEntryState>> = entryState.asStateFlow()

    private val readingState = MutableStateFlow(false)

    /** True while manifests are being read: an entry that isn't loaded yet has no verdict until this is false. */
    val reading: StateFlow<Boolean> = readingState.asStateFlow()

    /** One pass at a time. Held for a whole read, which can take as long as the slowest manifest. */
    private val refreshLock = Mutex()

    /** Guards [slots] and every publish. Held for a moment only, so a switch or a new index never waits on a read. */
    private val applyLock = Mutex()

    /** Tests: the clock [MIN_REFRESH_MS] is measured on. */
    internal var nowMs: () -> Long = System::currentTimeMillis

    /** Tests: the waits between retries of an entry that didn't answer ([RETRY_DELAYS_MS] in the app). */
    internal var retryDelaysMs: List<Long> = RETRY_DELAYS_MS

    /** When each entry's manifest last read, by [keyOf] of its URL. An entry that didn't answer has no stamp, so it is asked again. */
    private val answeredAt = ConcurrentHashMap<String, Long>()

    /** What the last pass came to, entry by entry: the next one starts from it. */
    @Volatile private var slots: List<Slot> = emptyList()

    /** The keys of the entries wanted at the last pass of this run: one that is gone at the next was removed. */
    private val seen = HashSet<String>()

    /** [start]'s scope: where an entry that didn't answer is retried from. Null in tests that never start it. */
    @Volatile private var scope: CoroutineScope? = null
    private val retryRound = AtomicInteger(0)
    @Volatile private var retries = 0

    /** One entry: a user add-on (on or off) or an enabled default, with the base its routes live under. */
    private data class Wanted(
        val url: String,
        val base: String,
        val file: String,
        val userAdded: Boolean,
        val token: String?,
        val firstParty: Boolean,
        val enabled: Boolean,
        val id: String?,
        val consent: Set<String>?,
    ) {
        val key: String get() = keyOf(url)

        override fun toString(): String = "Wanted(user=$userAdded, firstParty=$firstParty, enabled=$enabled)"
    }

    /**
     * An entry and where it stands: its manifest when one is known, why it is held when it is, and
     * what its last read was refused for ([refused]), when it was one of the refusals a host acts on.
     */
    private class Slot(
        val w: Wanted,
        val read: AddonManifest?,
        val trust: Trust,
        val hold: AddonHold?,
        val refused: AddonFailure.InvalidResponse.Code?,
        /** Its link's last answer was another add-on's manifest: held as [AddonHold.CHANGED] however often it is settled again. */
        val changed: Boolean = false,
    ) {
        val id: String? get() = read?.manifest?.id ?: w.id
    }

    /** The two lists as one read of them found them: what [wanted] is built from. */
    private data class Lists(val users: List<UserAddonEntry>, val defaults: List<DefaultAddonEntry>)

    /** The lists now. The user's list is waited for: a pass that ran before it was read would take "not read yet" for "none". */
    private suspend fun lists(): Lists = Lists(users.loadedEntries(), defaults.entries.value)

    /** The entries now ([lists]). */
    private suspend fun wanted(): List<Wanted> = wanted(lists())

    /** The entries of [lists]: the user's add-ons, then the enabled defaults. */
    private fun wanted(lists: Lists): List<Wanted> {
        val user = lists.users.filter { it.kind == UserAddonKind.MANIFEST }.mapNotNull { e ->
            HttpAddonClient.baseOf(e.manifestUrl)?.let {
                Wanted(
                    e.manifestUrl, it, HttpAddonClient.manifestFileOf(e.manifestUrl), userAdded = true, token = e.token,
                    firstParty = false, enabled = e.enabled, id = e.id, consent = e.consent,
                )
            }
        }
        val defaults = lists.defaults.filter { it.enabled }.mapNotNull { e ->
            HttpAddonClient.baseOf(e.manifestUrl)?.let {
                Wanted(
                    e.manifestUrl, it, HttpAddonClient.manifestFileOf(e.manifestUrl), userAdded = false, token = null,
                    firstParty = e.firstParty, enabled = true, id = null, consent = null,
                )
            }
        }
        return user + defaults
    }

    /** Reads and refreshes in the background: see the class doc. [configApplied] ticks on every applied config. */
    fun start(scope: CoroutineScope, configApplied: Flow<Any?>) {
        this.scope = scope
        scope.launch(Dispatchers.IO) {
            guarded { loadPersisted() }
            // The signed config's defaults and the user's sealed list both arrive after this starts:
            // every arrival is a pass, and every pass publishes the kept manifests before it asks anything.
            combine(defaults.entries, users.entries) { d, u -> d to u }
                .distinctUntilChanged()
                .collectLatest { guarded { listChanged() } }
        }
        scope.launch(Dispatchers.IO) {
            configApplied.collect { tick ->
                // A fresh, verified config was applied in this run (the app's flow starts at 0 and
                // holds the time of the last one): its list of defaults is the whole truth now,
                // an empty one too.
                if (tick != null && tick != 0L) defaultsKnown = true
                guarded { refresh(force = false) }
            }
        }
        // A new index: a block applies at once, with nothing asked; a lifted one needs its manifest read.
        scope.launch(Dispatchers.IO) { catalog.changes.collect { guarded { recheck() } } }
        // The first value is the one every pass already read.
        scope.launch(Dispatchers.IO) { switches.defaultsOff.drop(1).collect { guarded { republish() } } }
        // An add-on that answered a call "BitChord only" stops being asked now.
        scope.launch(Dispatchers.IO) { client.onlyBitChord.drop(1).collect { guarded { republish() } } }
    }

    /**
     * Nothing a pass throws, bar cancellation, may end a collector: an `Error` too (a hostile
     * manifest once overflowed the stack), or the registry would stay frozen for the whole run.
     */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "add-on refresh failed: ${t.javaClass.simpleName}")
        }
    }

    /**
     * Publishes the kept manifests of the current entries, asking nothing: an offline cold start
     * keeps the add-ons the last run had. Entries already loaded this run are left alone. Waits
     * for the kept index first, so an add-on Stash turned off is never published from its kept copy.
     */
    suspend fun loadPersisted() = refreshLock.withLock {
        // Any pass at all, not only one that left an add-on on: a pass that found the user's only
        // add-on held (its link now serves another add-on) publishes none, and its kept copy, the
        // manifest they approved, must not turn it back on (review-885 round 2, N3).
        if (slots.isNotEmpty()) return@withLock
        catalog.awaitLoaded()
        val kept = wanted().map { w -> kept(w, null) }
        applyLock.withLock { publish(kept, defaultsOff()) }
        val loaded = state.value
        if (loaded.isNotEmpty()) Log.i(TAG, "kept add-ons loaded: ${loaded.joinToString(",") { it.id }}")
    }

    /**
     * Publishes the entries' kept manifests, then reads the manifests that are due, all at once, and
     * publishes each add-on as it answers. [force]: every enabled entry is read; otherwise only the
     * ones whose last answer is older than [MIN_REFRESH_MS] (a config was applied, nothing changed)
     * or that never answered.
     *
     *  - **Kept first.** Before anything is asked, every entry is published from the manifest it
     *    had this run, else the one kept from an earlier run. So the Tidal add-on is there from the
     *    moment the signed config names it, not once its manifest has been read again, and an
     *    add-on that was removed, switched off or turned off by Stash stops being asked at once.
     *  - **Side by side.** One add-on that hangs never delays another: each is read on its own.
     *  - **Each on its own.** Whatever one entry's read throws, bar cancellation, costs that entry
     *    only: it keeps its last good manifest.
     *  - **Asked again.** An entry that didn't answer has no stamp: the next pass asks it again, and
     *    [start]'s scope retries it by itself after [RETRY_DELAYS_MS].
     *  - **Off the caller's thread.** Settings › Add-ons asks for a pass from the main thread as it
     *    opens; a manifest's JSON (up to 64 KiB of someone else's) is read on an IO thread whoever asks.
     */
    suspend fun refresh(force: Boolean = true) = withContext(Dispatchers.IO) { pass(force) }

    /**
     * A list changed: the user added, removed or switched an add-on, their sealed list was read, or
     * the signed config's defaults arrived. A pass that reads what is due, never one that reads
     * everything: at a cold start those three and the first applied config each start a pass within
     * the same second, and an add-on that answered a moment ago is not asked again for each of them
     * (one read per add-on, as SPEC.md 3 has it). A new or re-linked entry has never answered, so it is
     * read at once. The retries of entries that didn't answer start over, as the list is a new one.
     */
    private suspend fun listChanged() = withContext(Dispatchers.IO) {
        publishKnown()
        pass(force = false, restartRetries = true)
    }

    /** Bumped, under [applyLock], each time [publishKnown] publishes: a pass that started before it must not publish its older list over it. */
    @Volatile private var listsVersion = 0

    /**
     * The lists [publishKnown] last published, under [applyLock]; null before it first has (tests
     * that never [start], a pass at a cold start before the list collector's first). A pass publishes
     * only these lists (review-885 round 2, N2): the collector misses a list that changes and changes
     * back between two of its looks (a `StateFlow` never hands it a value equal to the last one), so a
     * pass that read the list in between, in either direction, published one nobody would correct.
     */
    @Volatile private var knownLists: Lists? = null

    /** A fresh config was applied in this run: the defaults' list, empty or not, is all there is ([forgetRemoved]). */
    @Volatile private var defaultsKnown = false

    /**
     * The lists as they are now, published from what is already known, asking nothing and waiting for
     * no read (review-885 M5). A pass holds [refreshLock] for as long as its slowest manifest, so a
     * user's switch or Remove that waited for a pass kept an add-on that hangs in the chains for as
     * long as it hung. This takes [applyLock] only: off, removed or re-linked applies at once, and the
     * pass that follows reads what is new.
     */
    private suspend fun publishKnown() {
        catalog.awaitLoaded()
        val read = lists()
        val wanted = wanted(read)
        val known = slots.associateBy { it.w.key }
        // Entries not seen before start from their kept manifest, read before the lock is taken.
        val fresh = wanted.filter { it.key !in known }.associate { it.key to kept(it, null) }
        // An entry that was here a moment ago and is gone was removed: its kept manifest goes now,
        // not at the end of a pass that may wait for one that is reading (N7). The pass forgets it
        // again, in case a read under way wrote it back.
        val gone = known.keys - wanted.map { it.key }.toSet()
        if (gone.isNotEmpty() && !users.unreadable.value) runCatchingStore { store.forget(gone) }
        applyLock.withLock {
            val now = slots.associateBy { it.w.key }
            listsVersion++
            knownLists = read
            publish(
                wanted.map { w ->
                    now[w.key]?.let { Slot(w, it.read, it.trust, it.hold, it.refused, it.changed) } ?: fresh[w.key] ?: Slot(w, null, Trust.UNOFFICIAL, AddonHold.NOT_LOADED, null)
                },
                defaultsOff(),
            )
        }
    }

    private suspend fun pass(force: Boolean, restartRetries: Boolean = force) = refreshLock.withLock {
        // The kept index first, as in [loadPersisted]: whichever pass runs first at a cold start, an
        // add-on Stash turned off is never published from its kept copy, and never asked.
        catalog.awaitLoaded()
        // Read before the lists are: a list that changes after this line, and is published by
        // [publishKnown], makes this pass's older list one it must not publish over the newer one.
        val version = listsVersion
        val read = lists()
        val wanted = wanted(read)
        val now = nowMs()
        answeredAt.keys.retainAll(wanted.map { it.key }.toSet())
        // By entry, never by base: two manifests in one folder (`a.json`, `b.json`) share a base.
        val before = slots.associateBy { it.w.key }
        val kept = wanted.map { w -> kept(w, before[w.key]) }
        // What stands after the publish, settled again under the lock ([publish]): a block that landed
        // since `kept` was built is in it. If the lists changed meanwhile ([publishKnown]), theirs stands,
        // and so do they when this pass read lists the list collector never published ([knownLists]).
        val standing = applyLock.withLock {
            if (listsVersion == version && knownLists.let { it == null || it == read }) publish(kept, defaultsOff())
            slots
        }
        val mine = wanted.toSet()
        standing.filter { it.w in mine && it.hold == AddonHold.BLOCKED }.forEach { Log.i(TAG, "blocked add-on not loaded: ${it.id ?: "(no id known)"}") }
        val due = standing.filter { s ->
            s.w in mine && s.w.enabled && s.hold != AddonHold.BLOCKED &&
                (force || answeredAt[s.w.key].let { it == null || now - it !in 0 until MIN_REFRESH_MS })
        }
        if (restartRetries) {
            // The list changed (or the caller wants everything read): its own retries start over.
            retryRound.incrementAndGet()
            retries = 0
        }
        if (due.isNotEmpty()) {
            readingState.value = true
            try {
                coroutineScope {
                    due.forEach { s -> launch { readOne(s) } }
                }
            } finally {
                readingState.value = false
            }
        }
        // The entries that stand, which are this pass's own when it published them: an entry a list
        // this pass did not publish lacks may still be wanted, and its kept manifest stays.
        forgetRemoved(standing.map { it.w })
        retryUnanswered(due)
    }

    /**
     * Forgets the kept manifests of entries that are gone. Every entry that is still there keeps
     * its own, a switched-off or blocked one too: it is how its row keeps its name, and how an
     * offline switch back on works.
     *
     * What counts as gone is decided carefully, because at a cold start the signed config's
     * defaults arrive a moment after the first pass: "not wanted yet" must never delete the kept
     * manifest that pass is about to need (it is what keeps Tidal's add-on on, offline). So:
     *  - an entry that was wanted earlier in this run and no longer is (the user removed it, the
     *    last one too) is forgotten at once;
     *  - once the config's defaults are here (the list is not empty, or a fresh config was applied
     *    in this run and names none), everything not wanted is stale. That is what clears a manifest
     *    nobody owns any more (an add-on removed just before the app was closed, review-885 N7) on a
     *    phone whose config has no default add-on;
     *  - and nothing is forgotten while the user's own list could not be read ([UserAddons.unreadable]).
     */
    private suspend fun forgetRemoved(wanted: List<Wanted>) {
        // The user's list didn't open this run: their add-ons are not known, which is not "removed".
        if (users.unreadable.value) return
        val keys = wanted.map { it.key }.toSet()
        val removed = seen - keys
        seen += keys
        runCatchingStore {
            if (wanted.any { !it.userAdded } || defaultsKnown) store.retain(keys) else if (removed.isNotEmpty()) store.forget(removed)
        }
        seen.retainAll(keys)
    }

    /** [s]'s manifest, read now and published when it answers. Never throws, bar cancellation. */
    private suspend fun readOne(s: Slot) {
        val w = s.w
        val settled = try {
            val fetched = fetch(w, s.trust)
            when {
                // "BitChord only" now: the manifest it had before must not outlive that. Its kept
                // copy goes, so the next start doesn't bring it back before this is read again.
                fetched.refused == AddonFailure.InvalidResponse.Code.ONLY_BITCHORD -> {
                    answeredAt[w.key] = nowMs()
                    Log.i(TAG, "an add-on now serves only BitChord: not used (${s.id ?: "no id known"})")
                    runCatchingStore { store.write(w.key, NO_MANIFEST) }
                    Slot(w, s.read, s.trust, AddonHold.ONLY_BITCHORD, fetched.refused)
                }
                fetched.read == null -> settle(w, s.read, fetched.refused, s.changed)
                // The add-on the user approved is the one with the id they saw (add-ons plan phase 4
                // §2.7): a link that starts serving another add-on's manifest is not used, and its
                // manifest is not kept in place of the approved one's.
                idChanged(w, fetched.read) -> {
                    answeredAt[w.key] = nowMs()
                    Log.w(TAG, "an add-on's link now serves a different add-on: not used (${w.id})")
                    Slot(w, s.read?.takeUnless { idChanged(w, it) }, s.trust, AddonHold.CHANGED, refused = null, changed = true)
                }
                else -> {
                    answeredAt[w.key] = nowMs()
                    if (fetched.read.manifest.kind == AddonKind.SERVER && fetched.json != null) runCatchingStore { store.write(w.key, fetched.json) }
                    settle(w, fetched.read, refused = null)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "add-on manifest read failed: ${t.javaClass.simpleName}")
            return
        }
        if (settled.hold == AddonHold.BLOCKED && s.hold != AddonHold.BLOCKED) Log.i(TAG, "blocked add-on not loaded: ${settled.id ?: "(no id known)"}")
        applyLock.withLock {
            // Into the list as it is now: a switch or a new index may have published since this read started.
            val at = slots.indexOfFirst { it.w == w }
            if (at >= 0) publish(slots.toMutableList().also { it[at] = settled }, defaultsOff())
        }
    }

    /** [w] from what is already known, asking nothing: the manifest it had this run ([before]), else the kept one. */
    private suspend fun kept(w: Wanted, before: Slot?): Slot = try {
        settle(w, before?.read ?: persisted(w), before?.refused, before?.changed == true)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Log.w(TAG, "a kept add-on manifest could not be used: ${t.javaClass.simpleName}")
        Slot(w, null, if (w.firstParty) Trust.FIRST_PARTY else if (w.userAdded) Trust.UNOFFICIAL else Trust.OFFICIAL, AddonHold.NOT_LOADED, refused = null)
    }

    /**
     * The entries of [due] that didn't answer are asked again from [start]'s scope: after 15 s, 30 s,
     * 1, 2, 5 and 10 minutes, then not until something else starts a pass (an applied config, a
     * changed list, Settings › Add-ons opening). Each retry is a pass that reads only what is due.
     */
    private fun retryUnanswered(due: List<Slot>) {
        val unanswered = due.any { answeredAt[it.w.key] == null }
        if (!unanswered) {
            retries = 0
            return
        }
        val scope = scope ?: return
        val delayMs = retryDelaysMs.getOrNull(retries) ?: return
        retries++
        val round = retryRound.get()
        scope.launch(Dispatchers.IO) {
            delay(delayMs)
            // A forced pass since (the list changed) starts its own retries.
            if (retryRound.get() == round) guarded { refresh(force = false) }
        }
    }

    /** The index changed: blocks apply now, from what is already known; a lifted block reads the manifest again. */
    private suspend fun recheck() {
        val lifted = applyLock.withLock {
            val before = slots
            val settled = before.map { settle(it.w, it.read, it.refused, it.changed) }
            before.zip(settled).forEach { (a, b) ->
                if (a.hold != AddonHold.BLOCKED && b.hold == AddonHold.BLOCKED) Log.i(TAG, "blocked add-on not loaded: ${b.id ?: "(no id known)"}")
            }
            publish(settled, defaultsOff())
            before.zip(settled).any { (a, b) -> a.hold == AddonHold.BLOCKED && b.hold != AddonHold.BLOCKED }
        }
        // A lifted block reads what it kept from being read: an add-on that never answered is due.
        if (lifted) refresh(force = false)
    }

    /** A default was switched on or off: publish again from what is known, asking nothing. */
    private suspend fun republish() = applyLock.withLock {
        publish(slots, defaultsOff())
    }

    private suspend fun defaultsOff(): Set<String> = runCatchingStore { switches.defaultsOff.first() }.orEmpty()

    /** The manifest [read] is another add-on's than the one the user approved under [w]. */
    private fun idChanged(w: Wanted, read: AddonManifest): Boolean = w.userAdded && w.id != null && read.manifest.id != w.id

    /**
     * Where [w] stands with the manifest [read] (null: none known), asking nothing: its trust, and
     * whether it is held. A block is checked against the id the manifest gives and the id the
     * entry was added with, so changing one never slips past a block of the other.
     */
    private fun settle(w: Wanted, read: AddonManifest?, refused: AddonFailure.InvalidResponse.Code?, changed: Boolean = false): Slot {
        val trust = when {
            w.firstParty -> Trust.FIRST_PARTY
            !w.userAdded -> Trust.OFFICIAL
            else -> {
                val ids = listOfNotNull(read?.manifest?.id, w.id).distinct().ifEmpty { listOf(null) }
                val each = ids.map { catalog.trustOf(w.base, it) }
                each.firstOrNull { it == Trust.BLOCKED } ?: each.first()
            }
        }
        val hold = when {
            trust == Trust.BLOCKED -> AddonHold.BLOCKED
            !w.enabled -> AddonHold.OFF
            // BitChord only: by its manifest at the last read, or by its own answer to a call.
            refused == AddonFailure.InvalidResponse.Code.ONLY_BITCHORD || (w.userAdded && (read?.manifest?.id ?: w.id) in client.onlyBitChord.value) ->
                AddonHold.ONLY_BITCHORD
            changed -> AddonHold.CHANGED
            read == null || read.manifest.kind != AddonKind.SERVER -> AddonHold.NOT_LOADED
            idChanged(w, read) -> AddonHold.CHANGED
            // No agreement on record counts as none given (review-885 M3): an add-on that was Official
            // when it was added, and no longer is, waits for the sheet like one that wants more.
            trust == Trust.UNOFFICIAL && (w.consent == null || !w.consent.containsAll(AddonSees.namesOf(read))) -> AddonHold.WANTS_MORE
            else -> null
        }
        return Slot(w, read, trust, hold, refused, changed)
    }

    /** What asking for [Wanted]'s manifest came to. [read] null: it didn't read; [refused]: for one of the reasons a host acts on. */
    private class Fetched(val read: AddonManifest?, val json: String?, val refused: AddonFailure.InvalidResponse.Code?)

    /** [w]'s manifest, asked for now. Throws nothing but cancellation and what [readOne] catches. */
    private suspend fun fetch(w: Wanted, trust: Trust): Fetched =
        when (val result = client.manifestWithJson(w.base, publicOnly = trust == Trust.UNOFFICIAL, file = w.file)) {
            is AddonResult.Ok -> Fetched(result.value.first, result.value.second, refused = null)
            is AddonResult.Failed -> Fetched(null, null, refused = (result.failure as? AddonFailure.InvalidResponse)?.code)
        }

    /** Publishes [all]: the add-ons that are on to [addons], every entry to [entries]. Call with [applyLock] held. */
    private fun publish(given: List<Slot>, off: Set<String>) {
        // Settled again here, under the lock, whatever the caller settled outside it (review-885 M7): a
        // block the index brought between a settle and this publish must never be published away.
        val all = given.map { s ->
            try {
                settle(s.w, s.read, s.refused, s.changed)
            } catch (t: Throwable) {
                s // one entry that can't be judged again keeps what it was given; it never costs the others
            }
        }
        // Said once, when it starts to wait (and once at each start while it still does): the id only.
        val waiting = slots.filter { it.hold == AddonHold.WANTS_MORE }.mapTo(HashSet()) { it.w.key }
        all.filter { it.hold == AddonHold.WANTS_MORE && it.w.key !in waiting }.forEach {
            Log.i(TAG, "add-on not used until the user reviews it (it would see more than they agreed to): ${it.id ?: "(no id known)"}")
        }
        slots = all
        val built = all.map { s -> if (s.hold == null && s.read != null) install(s.w, s.read, s.trust) else null }
        // One add-on per id: a first-party default first, then the user's own.
        val keep = built.filterNotNull().sortedByDescending { it.firstParty }.distinctBy { it.id }.toSet()
        fun switchedOff(a: InstalledAddon) = !a.userAdded && a.id in off && !NamedRungAddons.owns(a)
        val on = built.filterNotNull().filter { it in keep && !switchedOff(it) }
        // The entries first: by the time an add-on is seen to have gone off, why is already there to read.
        entryState.value = all.mapIndexed { i, s ->
            val a = built[i]
            AddonEntryState(
                key = s.w.key,
                userAdded = s.w.userAdded,
                firstParty = s.w.firstParty,
                host = hostOf(s.w.base),
                trust = s.trust,
                blockReason = if (s.trust == Trust.BLOCKED) blockReason(s) else null,
                manifest = s.read?.takeIf { it.manifest.kind == AddonKind.SERVER },
                hold = s.hold ?: when {
                    a == null -> AddonHold.NOT_LOADED
                    a !in keep -> AddonHold.REPLACED
                    switchedOff(a) -> AddonHold.OFF
                    else -> null
                },
                needsNewerStash = s.refused == AddonFailure.InvalidResponse.Code.NEEDS_NEWER_STASH,
            )
        }
        // User-added first, then the defaults: the order the play order starts from.
        state.value = on.filter { it.userAdded } + on.filterNot { it.userAdded }
    }

    private fun blockReason(s: Slot): BlockReason? =
        listOfNotNull(s.read?.manifest?.id, s.w.id).distinct().ifEmpty { listOf(null) }
            .firstNotNullOfOrNull { catalog.blockReason(s.w.base, it) }

    private fun install(w: Wanted, read: AddonManifest, trust: Trust): InstalledAddon? {
        if (read.manifest.kind != AddonKind.SERVER) return null
        return try {
            InstalledAddon(
                base = w.base, manifest = read.manifest, userAdded = w.userAdded, token = w.token, firstParty = w.firstParty,
                dialect = read.dialect, eclipse = read.eclipse, trust = trust,
            )
        } catch (e: IllegalArgumentException) {
            null // a token a header can't carry; the store refuses those, so this is only belt and braces
        }
    }

    /** The kept manifest of [w], checked again as if it had just arrived; null when none or no longer valid. */
    private suspend fun persisted(w: Wanted): AddonManifest? {
        val json = runCatchingStore { store.read(w.key) } ?: return null
        return runCatching { AddonValidator.readManifest(Json.parseToJsonElement(json), client.optionsFor(publicOnly = w.userAdded)) }.getOrNull()
    }

    private suspend fun <T> runCatchingStore(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "add-on manifest store failed: ${e.javaClass.simpleName}")
        null
    }

    companion object {
        private const val TAG = "AddonRegistry"

        /** SPEC.md 3: a manifest is re-read at most every 10 minutes when nothing else changed. */
        const val MIN_REFRESH_MS = 10 * 60_000L

        /** Written over a kept manifest that must not be used again: it reads as none kept. */
        private const val NO_MANIFEST = ""

        /** When an entry that didn't answer is asked again, one wait after another; after the last, not until something else starts a pass. */
        val RETRY_DELAYS_MS: List<Long> = listOf(15_000L, 30_000L, 60_000L, 120_000L, 300_000L, 600_000L)

        /** The [AddonManifestStore] key of a manifest URL: its SHA-256, so the URL (and any token in it) is never stored there. */
        fun keyOf(manifestUrl: String): String =
            MessageDigest.getInstance("SHA-256").digest(manifestUrl.trim().toByteArray()).joinToString("") { "%02x".format(it) }

        /** The host of a base, with its port when it isn't the scheme's own: all of an add-on's address that is ever shown. */
        fun hostOf(base: String): String {
            val u = base.toHttpUrlOrNull() ?: return ""
            return if (u.port == HttpUrl.defaultPort(u.scheme)) u.host else "${u.host}:${u.port}"
        }
    }
}
