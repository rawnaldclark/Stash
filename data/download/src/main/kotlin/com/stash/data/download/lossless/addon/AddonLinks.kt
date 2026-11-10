package com.stash.data.download.lossless.addon

import android.util.Log
import com.stash.addons.contract.AddonKind
import com.stash.addons.contract.Resource
import com.stash.addons.contract.TokenPlacement
import com.stash.core.addons.AddonFailure
import com.stash.core.addons.AddonHostOptions
import com.stash.core.addons.AddonRegistry
import com.stash.core.addons.AddonResult
import com.stash.core.addons.AddonValidator
import com.stash.core.addons.HttpAddonClient
import com.stash.core.addons.InstalledAddon
import com.stash.core.addons.UserAddonEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Add from a link", minimal (add-ons plan phase 2 §5.5): paste a manifest URL, see the add-on's
 * name and what it offers, with "its server sees what you search and the songs you play", then
 * Add. v1 and v0 manifests read ([AddonValidator.anyManifest]); Eclipse-family ones are phase 4.
 *
 * Nothing here logs or shows the link: a path token is part of it (SPEC.md 6).
 */
@Singleton
class AddonLinks @Inject constructor(
    private val client: HttpAddonClient,
    private val users: EncryptedUserAddons,
    private val registry: AddonRegistry,
    private val host: AddonHostOptions,
) {
    /** What the Add sheet shows before Add. [manifestUrl] is the user's own link, never displayed. */
    data class Preview(
        val manifestUrl: String,
        val id: String,
        val name: String,
        val version: String,
        val description: String?,
        /** Plain-language names of what it offers, in manifest order. */
        val offers: List<String>,
        /** The manifest asks for an `X-Access-Token` header the user must paste. */
        val needsToken: Boolean,
    ) {
        override fun toString(): String = "Preview($id $version)"
    }

    sealed interface Outcome {
        data class Ready(val preview: Preview) : Outcome
        data class Refused(val message: String) : Outcome
        data object Added : Outcome
    }

    /** One of the user's add-ons, for the list under the Add field. */
    data class Added(val manifestUrl: String, val name: String, val id: String, val loaded: Boolean, val enabled: Boolean) {
        override fun toString(): String = "Added($id, loaded=$loaded)"
    }

    /** The user's add-ons, named after their loaded manifests where there is one; live. */
    val added: Flow<List<Added>> = combine(users.entries, registry.addons) { entries, addons ->
        val loaded = addons.filter { it.userAdded }
        entries.map { e ->
            val base = HttpAddonClient.baseOf(e.manifestUrl)
            val a = loaded.firstOrNull { it.base == base }
            Added(e.manifestUrl, a?.manifest?.name ?: hostOf(e.manifestUrl), a?.id.orEmpty(), a != null, e.enabled)
        }
    }

    /** Reads the manifest behind [link] and says what Add would add, or why it can't. */
    suspend fun preview(link: String): Outcome {
        val url = normalise(link) ?: return Outcome.Refused(
            if (link.trim().startsWith("http://") && !host.allowLocalHttp) "The link must start with https://."
            else "Paste the add-on's manifest link. It ends in /manifest.json.",
        )
        if (users.entries.value.any { it.manifestUrl == url }) return Outcome.Refused("This add-on is already added.")
        val base = HttpAddonClient.baseOf(url) ?: return Outcome.Refused("Paste the add-on's manifest link. It ends in /manifest.json.")
        val manifest = when (val r = client.manifest(base)) {
            is AddonResult.Ok -> r.value
            is AddonResult.Failed -> {
                Log.i(TAG, "add from a link: the manifest did not read (${r.failure.javaClass.simpleName})")
                return Outcome.Refused(refusedText(r.failure))
            }
        }
        if (manifest.kind != AddonKind.SERVER) return Outcome.Refused("This add-on runs on the phone. Stash can't add those yet.")
        if (manifest.knownResources.none { it in PLAYABLE }) {
            return Outcome.Refused("This add-on offers nothing Stash can use yet.")
        }
        registry.addons.value.firstOrNull { it.id == manifest.id }?.let { other ->
            return Outcome.Refused(
                if (other.userAdded) "You already added an add-on with this id (${manifest.id})."
                else "Stash already has an add-on with this id (${manifest.id}).",
            )
        }
        return Outcome.Ready(
            Preview(
                manifestUrl = url,
                id = manifest.id,
                name = manifest.name,
                version = manifest.version,
                description = manifest.description,
                offers = manifest.knownResources.mapNotNull(::offerName).distinct(),
                needsToken = manifest.auth?.token == TokenPlacement.HEADER,
            ),
        )
    }

    /** Adds [preview], with [token] when the manifest asks for one. */
    suspend fun add(preview: Preview, token: String?): Outcome {
        val t = token?.trim()?.takeIf { it.isNotEmpty() }
        if (preview.needsToken && t == null) return Outcome.Refused("This add-on needs its access token.")
        if (t != null && !InstalledAddon.isHeaderSafe(t)) return Outcome.Refused("That token has characters a request can't carry.")
        if (registry.addons.value.any { it.id == preview.id }) return Outcome.Refused("Stash already has an add-on with this id (${preview.id}).")
        users.put(UserAddonEntry(preview.manifestUrl, token = t.takeIf { preview.needsToken }, enabled = true))
        Log.i(TAG, "add-on added from a link: ${preview.id}")
        return Outcome.Added
    }

    suspend fun remove(manifestUrl: String) {
        users.remove(manifestUrl)
        Log.i(TAG, "a user add-on was removed")
    }

    suspend fun setEnabled(manifestUrl: String, enabled: Boolean) = users.setEnabled(manifestUrl, enabled)

    /** The link as kept: a manifest URL the client may call, else null. A base (ending in `/`) gets `manifest.json`. */
    internal fun normalise(link: String): String? {
        val u = link.trim()
        val url = when {
            u.endsWith("/manifest.json") -> u
            u.endsWith("/") -> u + "manifest.json"
            else -> return null
        }
        if ('?' in url || '#' in url) return null
        return url.takeIf { AddonValidator.isAllowedUrl(it, AddonValidator.Options(allowLocalHttp = host.allowLocalHttp)) }
    }

    private fun refusedText(f: AddonFailure): String = when (f) {
        is AddonFailure.InvalidResponse -> when {
            f.reason.startsWith("This add-on needs a newer Stash") -> "This add-on needs a newer Stash."
            f.reason.startsWith("not a Stash add-on") -> "That link isn't a Stash add-on."
            else -> "That add-on's manifest isn't valid."
        }
        AddonFailure.Network, AddonFailure.Timeout -> "Couldn't reach that add-on. Check the link and your connection."
        else -> "That add-on didn't answer with a manifest."
    }

    private fun hostOf(url: String) = url.removePrefix("https://").removePrefix("http://").substringBefore('/')

    companion object {
        private const val TAG = "AddonLinks"

        /** What makes an add-on worth adding today: something the generic rungs or search use. */
        private val PLAYABLE = setOf(Resource.SEARCH, Resource.RESOLVE, Resource.STREAM, Resource.DOWNLOAD)

        /** SPEC.md 1.3's resources, as the Add sheet names them. */
        fun offerName(r: Resource): String? = when (r) {
            Resource.SEARCH -> "Search"
            Resource.RESOLVE -> "Finds your songs"
            Resource.STREAM -> "Streaming"
            Resource.DOWNLOAD -> "Downloads"
            Resource.ALBUM -> "Albums"
            Resource.ARTIST -> "Artists"
            Resource.CATALOG -> "Catalogs"
            Resource.LYRICS -> "Lyrics"
            Resource.QUOTA -> "A daily allowance"
            else -> null
        }

        /** The privacy line the sheet shows above Add (plan §5.5). */
        const val PRIVACY_NOTE = "Its server sees what you search and the songs you play."
    }
}
