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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
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
 *   signed config. User-added add-ons are asked ahead of Qobuz on every play (`StreamSourceRegistry`);
 *   unlike the user's own Tidal server, whatever the quality and whether or not the user has
 *   their own Qobuz. Phase 4 may give them the same conditions.
 * @property firstParty A default the signed config marks `first_party`: Stash's own, which gets
 *   the install signature ([AddonTrust] decides that on its own, from the same list).
 * @property settings The add-on's setting values, sent as query parameters (SPEC.md 2.2).
 * @property dialect The contract its manifest was written to: the client asks and reads each its own way.
 * @property eclipse What an Eclipse-family manifest said that v1 has no field for; null otherwise.
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
            "${if (userAdded) ", user" else ""}${if (firstParty) ", first-party" else ""})"

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
    /** The add-ons that are on, user-added ones first, then the defaults, each in its list's order. */
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
 */
data class UserAddonEntry(val manifestUrl: String, val token: String? = null, val enabled: Boolean = true) {
    override fun toString(): String = "UserAddonEntry(enabled=$enabled, token=${if (token != null) "set" else "none"})"
}

/** The user's own add-ons, in their order (`:data:download` `EncryptedUserAddons`). */
interface UserAddons {
    val entries: StateFlow<List<UserAddonEntry>>
}

/** No user add-ons: for hand-built registries in tests. */
object NoUserAddons : UserAddons {
    override val entries: StateFlow<List<UserAddonEntry>> = MutableStateFlow(emptyList<UserAddonEntry>()).asStateFlow()
}

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
 * **When it reads** ([start]): whenever either list changes, and on every applied config, at most
 * every [MIN_REFRESH_MS] then (SPEC.md 3: hosts re-read a manifest at most every 10 minutes).
 *
 * **Ids.** An id is on at most once. A first-party default beats a user add-on with the same id
 * (the Tidal rung's switch must not be turned off by adding a link); otherwise the user's own wins.
 */
@Singleton
class InTreeAddonRegistry @Inject constructor(
    private val defaults: DefaultAddons,
    private val users: UserAddons,
    private val client: HttpAddonClient,
    private val store: AddonManifestStore,
) : AddonRegistry {

    private val state = MutableStateFlow<List<InstalledAddon>>(emptyList())
    override val addons: StateFlow<List<InstalledAddon>> = state.asStateFlow()

    private val refreshLock = Mutex()

    /** Tests: the clock [MIN_REFRESH_MS] is measured on. */
    internal var nowMs: () -> Long = System::currentTimeMillis

    @Volatile private var lastRefreshMs = 0L
    @Volatile private var lastWanted: List<Wanted>? = null

    /** One entry to load: a user add-on or a default, with the base its routes live under. */
    private data class Wanted(val url: String, val base: String, val userAdded: Boolean, val token: String?, val firstParty: Boolean) {
        override fun toString(): String = "Wanted(user=$userAdded, firstParty=$firstParty)"
    }

    private fun wanted(): List<Wanted> {
        val user = users.entries.value.filter { it.enabled }.mapNotNull { e ->
            HttpAddonClient.baseOf(e.manifestUrl)?.let { Wanted(e.manifestUrl, it, userAdded = true, token = e.token, firstParty = false) }
        }
        val defaults = defaults.entries.value.filter { it.enabled }.mapNotNull { e ->
            HttpAddonClient.baseOf(e.manifestUrl)?.let { Wanted(e.manifestUrl, it, userAdded = false, token = null, firstParty = e.firstParty) }
        }
        return user + defaults
    }

    /** Reads and refreshes in the background: see the class doc. [configApplied] ticks on every applied config. */
    fun start(scope: CoroutineScope, configApplied: Flow<Any?>) {
        scope.launch(Dispatchers.IO) {
            loadPersisted()
            combine(defaults.entries, users.entries) { d, u -> d to u }
                .distinctUntilChanged()
                .collectLatest { guarded { refresh(force = true) } }
        }
        scope.launch(Dispatchers.IO) { configApplied.collect { guarded { refresh(force = false) } } }
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
     * keeps the add-ons the last run had. Entries already loaded this run are left alone.
     */
    suspend fun loadPersisted() = refreshLock.withLock {
        if (state.value.isNotEmpty()) return@withLock
        val loaded = wanted().mapNotNull { w -> persisted(w)?.let { install(w, it) } }
        if (loaded.isNotEmpty()) {
            state.value = order(loaded)
            Log.i(TAG, "kept add-ons loaded: ${loaded.joinToString(",") { it.id }}")
        }
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
        if (wanted.isEmpty()) {
            if (state.value.isNotEmpty()) state.value = emptyList()
            return@withLock
        }
        val before = state.value.associateBy { it.base }
        val loaded = wanted.mapNotNull { w ->
            val fresh = try {
                (client.manifestWithJson(w.base, publicOnly = w.userAdded) as? AddonResult.Ok)?.value
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "add-on manifest failed: ${e.javaClass.simpleName}")
                null
            }
            if (fresh != null) {
                val (read, json) = fresh
                if (read.manifest.kind == AddonKind.SERVER) runCatchingStore { store.write(keyOf(w.url), json) }
                install(w, read)
            } else {
                before[w.base]?.let { install(w, AddonManifest(it.manifest, it.dialect, it.eclipse)) } ?: persisted(w)?.let { install(w, it) }
            }
        }
        state.value = order(loaded)
        runCatchingStore { store.retain(wanted.map { keyOf(it.url) }.toSet()) }
    }

    private fun install(w: Wanted, read: AddonManifest): InstalledAddon? {
        if (read.manifest.kind != AddonKind.SERVER) return null
        return try {
            InstalledAddon(
                base = w.base, manifest = read.manifest, userAdded = w.userAdded, token = w.token, firstParty = w.firstParty,
                dialect = read.dialect, eclipse = read.eclipse,
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

    /** One add-on per id (a first-party default first, then the user's own), user-added first in play order. */
    private fun order(loaded: List<InstalledAddon?>): List<InstalledAddon> {
        val present = loaded.filterNotNull()
        val keep = present.sortedByDescending { it.firstParty }.distinctBy { it.id }.toSet()
        val kept = present.filter { it in keep }
        return kept.filter { it.userAdded } + kept.filterNot { it.userAdded }
    }

    companion object {
        private const val TAG = "AddonRegistry"

        /** SPEC.md 3: a manifest is re-read at most every 10 minutes when nothing else changed. */
        const val MIN_REFRESH_MS = 10 * 60_000L

        /** The [AddonManifestStore] key of a manifest URL: its SHA-256, so the URL (and any token in it) is never stored there. */
        fun keyOf(manifestUrl: String): String =
            MessageDigest.getInstance("SHA-256").digest(manifestUrl.trim().toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
