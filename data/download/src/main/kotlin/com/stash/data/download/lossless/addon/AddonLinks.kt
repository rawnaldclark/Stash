package com.stash.data.download.lossless.addon

import android.util.Log
import com.stash.addons.contract.AddonKind
import com.stash.addons.contract.Manifest
import com.stash.addons.contract.Resource
import com.stash.addons.contract.TokenPlacement
import com.stash.core.addons.AddonFailure
import com.stash.core.addons.AddonHostOptions
import com.stash.core.addons.AddonRegistry
import com.stash.core.addons.AddonEclipse
import com.stash.core.addons.AddonResult
import com.stash.core.addons.AddonValidator
import com.stash.core.addons.HttpAddonClient
import com.stash.core.addons.InstalledAddon
import com.stash.core.addons.UserAddonEntry
import com.stash.core.addons.UserAddonKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Add from a link", minimal (add-ons plan phase 2 §5.5): paste a manifest URL, see the add-on's
 * name and what it offers, with [PRIVACY_NOTE], then
 * Add. v1, v0 and Eclipse-family manifests read ([AddonValidator.readManifest], add-ons plan phase 4 §2.3),
 * under the base rules (phase 4 §2.6): no address on the user's own network.
 *
 * Nothing here logs or shows the link: a path token is part of it (SPEC.md 6).
 */
@Singleton
class AddonLinks @Inject constructor(
    private val client: HttpAddonClient,
    private val users: EncryptedUserAddons,
    private val registry: AddonRegistry,
    private val host: AddonHostOptions,
    private val refs: AddonRefs,
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
        // The user's own Tidal server is not a link add-on: Settings shows it on its own row.
        entries.filter { it.kind == UserAddonKind.MANIFEST }.map { e ->
            val base = HttpAddonClient.baseOf(e.manifestUrl)
            val a = loaded.firstOrNull { it.base == base }
            Added(e.manifestUrl, a?.manifest?.name ?: hostOf(e.manifestUrl), a?.id.orEmpty(), a != null, e.enabled)
        }
    }

    /** Reads the manifest behind [link] and says what Add would add, or why it can't. */
    suspend fun preview(link: String): Outcome {
        val url = normalise(link) ?: return Outcome.Refused(
            when {
                isLocalAddress(link) -> LOCAL_ADDRESS
                link.trim().startsWith("http://") && !host.allowLocalHttp -> "The link must start with https://."
                else -> "Paste the add-on's manifest link. It ends in /manifest.json."
            },
        )
        if (users.entries.value.any { it.kind == UserAddonKind.MANIFEST && it.manifestUrl == url }) return Outcome.Refused("This add-on is already added.")
        val base = HttpAddonClient.baseOf(url) ?: return Outcome.Refused("Paste the add-on's manifest link. It ends in /manifest.json.")
        val read = when (val r = client.readManifest(base, publicOnly = true)) {
            is AddonResult.Ok -> r.value
            is AddonResult.Failed -> {
                Log.i(TAG, "add from a link: the manifest did not read (${r.failure.javaClass.simpleName})")
                return Outcome.Refused(refusedText(r.failure))
            }
        }
        val manifest = read.manifest
        Log.i(TAG, "preview ${read.dialect.name.lowercase()} ${manifest.id}")
        if (manifest.kind != AddonKind.SERVER) return Outcome.Refused("This add-on runs on the phone. Stash can't add those yet.")
        if (!worthAdding(manifest)) {
            return Outcome.Refused("This add-on offers nothing Stash can play yet.")
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
                offers = uses(manifest, allowsDownloads = read.eclipse?.allowDownloads != false),
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
        // Nothing an earlier add-on with this id said about a song carries over (phase 4 §2.4).
        refs.forget(preview.id)
        if (!saved { users.put(UserAddonEntry(preview.manifestUrl, token = t.takeIf { preview.needsToken }, enabled = true)) }) {
            return Outcome.Refused(NOT_SAVED)
        }
        Log.i(TAG, "add-on added from a link: ${preview.id}")
        return Outcome.Added
    }

    /** Removes the add-on added from [manifestUrl]. False when the list could not be saved ([NOT_SAVED]): nothing changed. */
    suspend fun remove(manifestUrl: String): Boolean {
        val base = HttpAddonClient.baseOf(manifestUrl)
        val id = registry.addons.value.firstOrNull { it.userAdded && it.base == base }?.id
        if (!saved { users.remove(manifestUrl) }) return false
        // Its refs go with it, so a later add-on with the same id never inherits them (phase 4 §2.4).
        id?.let { refs.forget(it) }
        Log.i(TAG, "a user add-on was removed")
        return true
    }

    /** Switches the add-on added from [manifestUrl] on or off. False when the list could not be saved ([NOT_SAVED]): nothing changed. */
    suspend fun setEnabled(manifestUrl: String, enabled: Boolean): Boolean = saved { users.setEnabled(manifestUrl, enabled) }

    /**
     * Runs a write to the sealed list. The Keystore fails for a moment now and then: the seal
     * throws, or the stored list has not opened and the store refuses to write over it
     * ([EncryptedUserAddons.UnreadableException]). Neither may reach a `viewModelScope.launch`
     * with no handler (a crash on Add or Remove, review-882 J2): false, and nothing was changed.
     */
    private suspend fun saved(write: suspend () -> Unit): Boolean = try {
        write()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Class only: a message could quote the link or the token.
        Log.w(TAG, "the add-on list could not be saved: ${e.javaClass.simpleName}")
        false
    }

    /** The link as kept: a manifest URL the client may call, else null. A base (ending in `/`) gets `manifest.json`. */
    internal fun normalise(link: String): String? {
        val u = link.trim()
        val url = when {
            u.endsWith("/manifest.json") -> u
            u.endsWith("/") -> u + "manifest.json"
            else -> return null
        }
        if ('?' in url || '#' in url) return null
        return url.takeIf { AddonValidator.isAllowedUrl(it, linkOptions()) }
    }

    /** The rules a link the user adds follows: https, and the base rules (phase 4 §2.6). */
    private fun linkOptions() = AddonValidator.Options(allowLocalHttp = host.allowLocalHttp, publicOnly = true)

    /** [link] names an address on the user's own network, which the base rules refuse. */
    private fun isLocalAddress(link: String): Boolean = AddonValidator.isLocalAddressUrl(link.trim(), linkOptions())

    private fun refusedText(f: AddonFailure): String = when (f) {
        is AddonFailure.InvalidResponse -> when {
            f.reason.startsWith("This add-on needs a newer Stash") -> "This add-on needs a newer Stash."
            f.reason.startsWith(AddonEclipse.ONLY_BITCHORD) -> ONLY_BITCHORD_TEXT
            f.reason.startsWith("not a Stash add-on") -> "That link isn't an add-on."
            f.reason.startsWith(AddonEclipse.PRIVATE_ADDRESS) -> LOCAL_ADDRESS
            else -> "That add-on's manifest isn't valid."
        }
        is AddonFailure.Refused -> if (f.onlyBitChord) ONLY_BITCHORD_TEXT else "That add-on didn't answer with a manifest."
        AddonFailure.Network, AddonFailure.Timeout -> "Couldn't reach that add-on. Check the link and your connection."
        else -> "That add-on didn't answer with a manifest."
    }

    private fun hostOf(url: String) = url.removePrefix("https://").removePrefix("http://").substringBefore('/')

    companion object {
        private const val TAG = "AddonLinks"

        /**
         * What Add from a link accepts (phase 4 §2.4): songs to play or keep (`stream` or
         * `download`, found by `search` or `resolve`), or lyrics. One that offers only pages
         * (`search`, `album`, `artist`, `catalog`) gives Stash nothing to play yet.
         */
        fun worthAdding(m: Manifest): Boolean {
            val finds = m.offers(Resource.SEARCH) || m.offers(Resource.RESOLVE)
            val plays = m.offers(Resource.STREAM) || m.offers(Resource.DOWNLOAD)
            return (finds && plays) || m.offers(Resource.LYRICS)
        }

        /** What the preview names (phase 4 §2.4): only what Stash uses. */
        fun uses(m: Manifest, allowsDownloads: Boolean = true): List<String> {
            val finds = m.offers(Resource.SEARCH) || m.offers(Resource.RESOLVE)
            val plays = finds && m.offers(Resource.STREAM)
            val tiers = m.stream?.knownTiers.orEmpty()
            return listOfNotNull(
                "Plays songs".takeIf { plays },
                "Lossless".takeIf { (plays || m.offers(Resource.DOWNLOAD)) && tiers.any { it.isLossless } },
                "Downloads".takeIf { finds && allowsDownloads && (m.offers(Resource.DOWNLOAD) || m.offers(Resource.STREAM)) },
                "Lyrics".takeIf { m.offers(Resource.LYRICS) },
            )
        }

        /** The base rules' refusal (phase 4 §2.6). */
        const val LOCAL_ADDRESS = "Add-ons can't use addresses on your own network."

        /** A BitChord-only add-on (`bitchordAuth`, or a 401 naming BitChord): Stash respects that. */
        const val ONLY_BITCHORD_TEXT = "This add-on only works in BitChord."
        /** What to show when Add, Remove or the switch could not be saved; nothing was changed. */
        const val NOT_SAVED = "Couldn't save. Try again."

        /**
         * The privacy line the sheet shows above Add (plan §5.5); the screen's own copy is the
         * string `settings_addons_privacy`. What is behind each part (review-882 J3):
         *  - **"what you search"**: the artist and title of a song Stash looks for there
         *    (`/search`), which it does for a song you play, never for one it downloads;
         *  - **"the songs you play"**: its `/resolve` and `/stream` for a song you play;
         *  - **"and download"**: a download asks an add-on you added only through its `/resolve`
         *    (an add-on that has one learns of songs you save and never played), or for a song a
         *    play already matched there ([AddonRefs.matchesBySearch]).
         */
        const val PRIVACY_NOTE = "Its server sees what you search, and the songs you play and download."
    }
}
