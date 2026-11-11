package com.stash.core.addons

import android.util.Log
import com.stash.addons.contract.AddonKind
import com.stash.addons.contract.Manifest
import com.stash.addons.contract.Resource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest
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

    /** No manifest could be read, and none is kept (or another add-on already has its id). */
    NOT_LOADED,
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
 * and on every applied config, at most every [MIN_REFRESH_MS] then (SPEC.md 3: hosts re-read a
 * manifest at most every 10 minutes).
 *
 * **Ids.** An id is on at most once. A first-party default beats a user add-on with the same id
 * (the Tidal rung's switch must not be turned off by adding a link); otherwise the user's own wins.
 *
 * **What is never on** (add-ons plan phase 4), each listed in [entries] with its [AddonHold]:
 *  - an add-on the signed index blocks ([AddonCatalog], §1.6): not even its manifest is asked for;
 *  - one that is switched off: the user's own by its entry, a default by [AddonSwitches];
 *  - an add-on Stash doesn't run whose manifest now lists more than the user agreed it may see
 *    (§2.7): it waits until they agree again.
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

    private val refreshLock = Mutex()

    /** Tests: the clock [MIN_REFRESH_MS] is measured on. */
    internal var nowMs: () -> Long = System::currentTimeMillis

    @Volatile private var lastRefreshMs = 0L
    @Volatile private var lastWanted: List<Wanted>? = null

    /** What the last pass came to, entry by entry: the next one starts from it. */
    @Volatile private var slots: List<Slot> = emptyList()

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
        override fun toString(): String = "Wanted(user=$userAdded, firstParty=$firstParty, enabled=$enabled)"
    }

    /** An entry and where it stands: its manifest when one is known, and why it is held when it is. */
    private class Slot(val w: Wanted, val read: AddonManifest?, val trust: Trust, val hold: AddonHold?, val needsNewer: Boolean) {
        val id: String? get() = read?.manifest?.id ?: w.id
    }

    private fun wanted(): List<Wanted> {
        val user = users.entries.value.filter { it.kind == UserAddonKind.MANIFEST }.mapNotNull { e ->
            HttpAddonClient.baseOf(e.manifestUrl)?.let {
                Wanted(
                    e.manifestUrl, it, HttpAddonClient.manifestFileOf(e.manifestUrl), userAdded = true, token = e.token,
                    firstParty = false, enabled = e.enabled, id = e.id, consent = e.consent,
                )
            }
        }
        val defaults = defaults.entries.value.filter { it.enabled }.mapNotNull { e ->
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
        scope.launch(Dispatchers.IO) {
            guarded { loadPersisted() }
            combine(defaults.entries, users.entries) { d, u -> d to u }
                .distinctUntilChanged()
                .collectLatest { guarded { refresh(force = true) } }
        }
        scope.launch(Dispatchers.IO) { configApplied.collect { guarded { refresh(force = false) } } }
        // A new index: a block applies at once, with nothing asked; a lifted one needs its manifest read.
        scope.launch(Dispatchers.IO) { catalog.changes.collect { guarded { recheck() } } }
        // The first value is the one every pass already read.
        scope.launch(Dispatchers.IO) { switches.defaultsOff.drop(1).collect { guarded { republish() } } }
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "add-on refresh failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Publishes the kept manifests of the current entries, asking nothing: an offline cold start
     * keeps the add-ons the last run had. Entries already loaded this run are left alone. Waits
     * for the kept index first, so an add-on Stash turned off is never published from its kept copy.
     */
    suspend fun loadPersisted() = refreshLock.withLock {
        if (state.value.isNotEmpty()) return@withLock
        catalog.awaitLoaded()
        publish(wanted().map { w -> settle(w, persisted(w), needsNewer = false) }, defaultsOff())
        val loaded = state.value
        if (loaded.isNotEmpty()) Log.i(TAG, "kept add-ons loaded: ${loaded.joinToString(",") { it.id }}")
    }

    /**
     * Reads the enabled entries' manifests and publishes the add-ons that answered a valid one, or
     * kept one. Not [force]d (a config was applied, nothing changed): skipped within
     * [MIN_REFRESH_MS] of the last read.
     */
    suspend fun refresh(force: Boolean = true) = refreshLock.withLock {
        val wanted = wanted()
        val now = nowMs()
        if (!force && wanted == lastWanted && now - lastRefreshMs < MIN_REFRESH_MS) return@withLock
        lastWanted = wanted
        lastRefreshMs = now
        val off = defaultsOff()
        val before = slots.associateBy { it.w.base }
        // First, with nothing asked: an add-on that was removed, switched off or turned off by
        // Stash stops being asked now, not once every manifest has been read again.
        if (state.value.isNotEmpty()) {
            publish(wanted.map { w -> settle(w, before[w.base]?.read, before[w.base]?.needsNewer == true) }, off)
        }
        val loaded = wanted.map { w ->
            val known = before[w.base]?.read ?: persisted(w)
            val first = settle(w, known, needsNewer = false)
            if (first.hold == AddonHold.BLOCKED) {
                Log.i(TAG, "blocked add-on not loaded: ${first.id ?: "(no id known)"}")
                return@map first
            }
            if (!w.enabled) return@map first
            val (fresh, needsNewer) = fetch(w, first.trust)
            val settled = if (fresh != null) settle(w, fresh, needsNewer = false) else settle(w, known, needsNewer)
            if (settled.hold == AddonHold.BLOCKED) Log.i(TAG, "blocked add-on not loaded: ${settled.id ?: "(no id known)"}")
            settled
        }
        publish(loaded, off)
        // Every entry's kept manifest stays, a switched-off or blocked one's too: it is how their
        // row keeps its name, and how an offline switch back on works. A removed entry's goes.
        runCatchingStore { store.retain(wanted.map { keyOf(it.url) }.toSet()) }
    }

    /** The index changed: blocks apply now, from what is already known; a lifted block reads the manifest again. */
    private suspend fun recheck() {
        val lifted = refreshLock.withLock {
            val before = slots
            val settled = before.map { settle(it.w, it.read, it.needsNewer) }
            before.zip(settled).forEach { (a, b) ->
                if (a.hold != AddonHold.BLOCKED && b.hold == AddonHold.BLOCKED) Log.i(TAG, "blocked add-on not loaded: ${b.id ?: "(no id known)"}")
            }
            publish(settled, defaultsOff())
            before.zip(settled).any { (a, b) -> a.hold == AddonHold.BLOCKED && b.hold != AddonHold.BLOCKED }
        }
        if (lifted) refresh(force = true)
    }

    /** A default was switched on or off: publish again from what is known, asking nothing. */
    private suspend fun republish() = refreshLock.withLock {
        publish(slots.map { settle(it.w, it.read, it.needsNewer) }, defaultsOff())
    }

    private suspend fun defaultsOff(): Set<String> = runCatchingStore { switches.defaultsOff.first() }.orEmpty()

    /**
     * Where [w] stands with the manifest [read] (null: none known), asking nothing: its trust, and
     * whether it is held. A block is checked against the id the manifest gives and the id the
     * entry was added with, so changing one never slips past a block of the other.
     */
    private fun settle(w: Wanted, read: AddonManifest?, needsNewer: Boolean): Slot {
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
            read == null || read.manifest.kind != AddonKind.SERVER -> AddonHold.NOT_LOADED
            trust == Trust.UNOFFICIAL && w.consent != null && !w.consent.containsAll(AddonSees.namesOf(read.manifest)) -> AddonHold.WANTS_MORE
            else -> null
        }
        return Slot(w, read, trust, hold, needsNewer)
    }

    /** [w]'s manifest, asked for now: null when it didn't read, with whether that was "needs a newer Stash". */
    private suspend fun fetch(w: Wanted, trust: Trust): Pair<AddonManifest?, Boolean> {
        val result = try {
            client.manifestWithJson(w.base, publicOnly = trust == Trust.UNOFFICIAL, file = w.file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "add-on manifest failed: ${e.javaClass.simpleName}")
            return null to false
        }
        return when (result) {
            is AddonResult.Ok -> {
                val (read, json) = result.value
                if (read.manifest.kind == AddonKind.SERVER) runCatchingStore { store.write(keyOf(w.url), json) }
                read to false
            }
            is AddonResult.Failed ->
                null to ((result.failure as? AddonFailure.InvalidResponse)?.reason?.startsWith(AddonValidator.NEEDS_NEWER_STASH) == true)
        }
    }

    /** Publishes [all]: the add-ons that are on to [addons], every entry to [entries]. */
    private fun publish(all: List<Slot>, off: Set<String>) {
        slots = all
        val built = all.map { s -> if (s.hold == null && s.read != null) install(s.w, s.read, s.trust) else null }
        // One add-on per id: a first-party default first, then the user's own.
        val keep = built.filterNotNull().sortedByDescending { it.firstParty }.distinctBy { it.id }.toSet()
        fun switchedOff(a: InstalledAddon) = !a.userAdded && a.id in off && !NamedRungAddons.owns(a)
        val on = built.filterNotNull().filter { it in keep && !switchedOff(it) }
        // User-added first, then the defaults: the order the play order starts from.
        state.value = on.filter { it.userAdded } + on.filterNot { it.userAdded }
        entryState.value = all.mapIndexed { i, s ->
            val a = built[i]
            AddonEntryState(
                key = keyOf(s.w.url),
                userAdded = s.w.userAdded,
                firstParty = s.w.firstParty,
                host = hostOf(s.w.base),
                trust = s.trust,
                blockReason = if (s.trust == Trust.BLOCKED) blockReason(s) else null,
                manifest = s.read?.takeIf { it.manifest.kind == AddonKind.SERVER },
                hold = s.hold ?: when {
                    a == null || a !in keep -> AddonHold.NOT_LOADED
                    switchedOff(a) -> AddonHold.OFF
                    else -> null
                },
                needsNewerStash = s.needsNewer,
            )
        }
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
        val json = runCatchingStore { store.read(keyOf(w.url)) } ?: return null
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
