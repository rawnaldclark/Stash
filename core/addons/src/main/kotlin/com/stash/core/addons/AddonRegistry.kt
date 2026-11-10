package com.stash.core.addons

import android.util.Log
import com.stash.addons.contract.AddonKind
import com.stash.addons.contract.Manifest
import com.stash.addons.contract.Resource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * An add-on that is on, with the manifest it answered.
 *
 * @property base Where its routes live: the manifest URL without `manifest.json`, ending in `/`
 *   (SPEC.md 3). Holds a path token when the add-on uses one, so it is a secret: never logged.
 * @property token The `X-Access-Token` for an add-on whose manifest says `auth.token: "header"`.
 * @property userAdded Added by the user ("Add from a link", phase 4) rather than a default from the
 *   signed config. User-added add-ons go first in the play order, as the user's own Tidal server
 *   does today (`LosslessAvailability.ownTidalFirst`).
 * @property settings The add-on's setting values, sent as query parameters (SPEC.md 2.2).
 */
data class InstalledAddon(
    val base: String,
    val manifest: Manifest,
    val userAdded: Boolean = false,
    val token: String? = null,
    val settings: Map<String, String> = emptyMap(),
) {
    val id: String get() = manifest.id

    fun offers(resource: Resource): Boolean = manifest.offers(resource)

    /** Never names [base] or [token]: logs and crash reports carry only the id. */
    override fun toString(): String = "InstalledAddon(${manifest.id} ${manifest.version}${if (userAdded) ", user" else ""})"
}

/**
 * The add-ons that are on, in play order. Every add-on seam reads it: the stream rungs
 * (`core:media` `AddonStreamRungs`), the download sources (`data:download` `AddonDownloadSources`,
 * reached through `LosslessSourceRegistry.orderedSources()`) and the lyrics chain
 * (`data:lyrics` `AddonLyricsSource`). Hilt multibindings are compile-time, so add-ons that come
 * and go at runtime come in through here, never through `@IntoSet`.
 *
 * Phase 1: always empty, so every seam does exactly what the app did before add-ons existed.
 */
interface AddonRegistry {
    /** The add-ons that are on, user-added ones first, then the defaults, each in its list's order. */
    val addons: StateFlow<List<InstalledAddon>>

    /** Those of [addons] that list [resource], in order. Empty, and no work, when none are on. */
    fun offering(resource: Resource): List<InstalledAddon> {
        val now = addons.value
        return if (now.isEmpty()) emptyList() else now.filter { it.offers(resource) }
    }
}

/**
 * One default add-on: the web player's `plugins.json` entry (`{ manifest, enabled }`), which the
 * signed lossless config will carry as `default_addons` in phase 2.
 */
data class DefaultAddonEntry(val manifestUrl: String, val enabled: Boolean = true) {
    override fun toString(): String = "DefaultAddonEntry(enabled=$enabled)"
}

/**
 * The hook for the default add-ons (add-ons plan §4.3: `LosslessConfigFetcher` adds
 * `default_addons`). Phase 1 binds [NoDefaultAddons]; phase 2 binds one that reads the signed
 * config's list, so the Tidal add-on is on for everyone with nothing to set up.
 */
interface DefaultAddons {
    val entries: StateFlow<List<DefaultAddonEntry>>
}

/** Phase 1: no default add-ons. The signed config has no `default_addons` key yet. */
@Singleton
class NoDefaultAddons @Inject constructor() : DefaultAddons {
    override val entries: StateFlow<List<DefaultAddonEntry>> = MutableStateFlow(emptyList<DefaultAddonEntry>()).asStateFlow()
}

/**
 * The in-tree [AddonRegistry]: the defaults' manifests, read with [HttpAddonClient] by [refresh].
 * With no entries [refresh] asks nothing, and nothing in phase 1 calls it.
 *
 * Kept from one refresh to the next: an add-on whose manifest can't be read this time keeps its
 * last good manifest (a default list must never cost everyone FLAC over one failed fetch, plan
 * §4.6). Only server add-ons are loaded here; phone add-ons are phase 3.
 */
@Singleton
class InTreeAddonRegistry @Inject constructor(
    private val defaults: DefaultAddons,
    private val client: HttpAddonClient,
) : AddonRegistry {

    private val state = MutableStateFlow<List<InstalledAddon>>(emptyList())
    override val addons: StateFlow<List<InstalledAddon>> = state.asStateFlow()

    private val refreshLock = Mutex()

    /** Reads the enabled defaults' manifests and publishes the add-ons that answered a valid one. */
    suspend fun refresh() = refreshLock.withLock {
        val wanted = defaults.entries.value.filter { it.enabled }
        if (wanted.isEmpty()) {
            if (state.value.isNotEmpty()) state.value = emptyList()
            return@withLock
        }
        val before = state.value.associateBy { it.base }
        val loaded = wanted.mapNotNull { entry ->
            val base = HttpAddonClient.baseOf(entry.manifestUrl) ?: return@mapNotNull null
            val manifest = try {
                (client.manifest(base) as? AddonResult.Ok)?.value
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "default add-on manifest failed: ${e.javaClass.simpleName}")
                null
            }
            when {
                manifest == null -> before[base]
                manifest.kind != AddonKind.SERVER -> null
                else -> InstalledAddon(base = base, manifest = manifest)
            }
        }.distinctBy { it.id }
        state.value = loaded
    }

    private companion object {
        const val TAG = "AddonRegistry"
    }
}
