package com.stash.data.download.lossless.addon

import android.util.Log
import com.stash.addons.contract.AddonKind
import com.stash.addons.contract.Manifest
import com.stash.addons.contract.Resource
import com.stash.addons.contract.TokenPlacement
import com.stash.core.addons.AddonCatalog
import com.stash.core.addons.AddonEclipse
import com.stash.core.addons.AddonFailure
import com.stash.core.addons.AddonHostOptions
import com.stash.core.addons.AddonResult
import com.stash.core.addons.AddonSees
import com.stash.core.addons.AddonValidator
import com.stash.core.addons.BlockReason
import com.stash.core.addons.HttpAddonClient
import com.stash.core.addons.InTreeAddonRegistry
import com.stash.core.addons.InstalledAddon
import com.stash.core.addons.Trust
import com.stash.core.addons.UserAddonEntry
import com.stash.core.addons.UserAddonKind
import com.stash.data.download.lossless.PlayOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Add from a link" (add-ons plan phase 4 §2.1, §2.2): a link comes in by paste, Share → Stash or a
 * scanned code; [preview] reads the add-on behind it and says what Add would add, or why it can't;
 * [add] adds it. v1, v0 and Eclipse-family manifests read ([AddonValidator.readManifest], §2.3),
 * under the base rules (§2.6): no address on the user's own network.
 *
 * Nothing is ever added without a tap, and an add-on Stash doesn't run is added only once the user
 * has agreed to what its server will see (the "Not made by Stash" sheet, §2.7): [add] refuses
 * without it.
 *
 * Refusals are reasons ([Refusal]), never text: the screen picks the words. Nothing here logs or
 * shows the link: a path token is part of it (SPEC.md 6). Only its host is ever shown.
 */
@Singleton
class AddonLinks @Inject constructor(
    private val client: HttpAddonClient,
    private val users: EncryptedUserAddons,
    private val registry: InTreeAddonRegistry,
    private val host: AddonHostOptions,
    private val refs: AddonRefs,
    private val catalog: AddonCatalog,
    private val playOrder: PlayOrder,
) {
    /** What Stash will use from an add-on (§2.4): the preview and the add-on's page name only these. */
    enum class Use { PLAYS_SONGS, LOSSLESS, DOWNLOADS, LYRICS }

    /** Why a link can't be added. */
    enum class Refusal {
        /** Not a link to an add-on: unreadable, a query string, no manifest behind it, or one that isn't valid. */
        NOT_AN_ADDON,

        /** `http://`: add-on links must start with `https://`. */
        NOT_HTTPS,

        /** An address on the user's own network (the base rules, §2.6). */
        LOCAL_ADDRESS,

        /** `bitchordAuth`, or a 401 naming BitChord: it serves only the genuine BitChord, and Stash respects that. */
        ONLY_BITCHORD,
        NEEDS_NEWER_STASH,

        /** The signed index turned it off (§1.6); one per reason, each with its own fixed sentence. */
        BLOCKED_HARMFUL,
        BLOCKED_BROKEN,
        BLOCKED_IMPERSONATION,

        /** No answer: no connection, a timeout, or the server refused. */
        UNREACHABLE,

        /** Only pages (search, album, artist, catalog): nothing Stash can play, keep or show lyrics from. */
        NOTHING_TO_PLAY,

        /** A phone add-on: not something a link can add yet. */
        RUNS_ON_PHONE,

        /** The user already has it, or Stash already has an add-on by that id. */
        ALREADY_ADDED,

        /** Replace link: the new link is another add-on's (another origin or id). */
        DIFFERENT_ADDON,

        /** Its manifest asks for a password (a header token), and none was typed. */
        NEEDS_PASSWORD,

        /** The password has characters a request can't carry. */
        BAD_PASSWORD,

        /** [add] was called for an add-on Stash doesn't run without the user's agreement. */
        NOT_AGREED,
    }

    /** What the Add screen shows before Add. [manifestUrl] is the user's own link, never displayed. */
    data class Preview(
        val manifestUrl: String,
        val id: String,
        val name: String,
        val version: String,
        val description: String?,
        /** The origin's host only: never the path, which can hold a token. */
        val host: String,
        /** What Stash will use from it, in the order shown. */
        val uses: List<Use>,
        /** The manifest asks for an `X-Access-Token` header the user must type. */
        val needsToken: Boolean,
        val trust: Trust,
        /** What its server will see: the sheet's "will see" line. */
        val sees: Set<AddonSees>,
        /** The "Not made by Stash" sheet comes before Add: an add-on Stash doesn't run, new or wanting more than was agreed. */
        val needsConsent: Boolean,
        /** It has a place in the play order (it plays or hands over songs), where it goes last. */
        val playsSongs: Boolean,
        /** The handle ([InTreeAddonRegistry.keyOf]) of the add-on whose link this replaces; null for a new one. */
        val replacesKey: String? = null,
    ) {
        override fun toString(): String = "Preview($id $version)"
    }

    sealed interface Outcome {
        data class Ready(val preview: Preview) : Outcome
        data class Refused(val reason: Refusal) : Outcome

        /** Added (or its link replaced). [key]: its handle, for the list to show it. */
        data class Added(val key: String) : Outcome
    }

    private val justAdded = MutableStateFlow<String?>(null)

    /** The add-on added a moment ago ([InTreeAddonRegistry.keyOf]): the list highlights its row once. */
    val lastAdded: StateFlow<String?> = justAdded.asStateFlow()

    fun highlightShown() {
        justAdded.value = null
    }

    /**
     * Reads the add-on behind [link] and says what Add would add, or why it can't. [replacingKey]:
     * the handle of an add-on whose link is being replaced (its page, §2.9); the new link must then
     * be the same add-on (origin and id), and keeps its place and what was agreed.
     */
    suspend fun preview(link: String, replacingKey: String? = null): Outcome {
        val url = when (val n = normalise(link)) {
            is Normalised.Url -> n.url
            is Normalised.No -> return Outcome.Refused(n.reason)
        }
        val base = HttpAddonClient.baseOf(url) ?: return Outcome.Refused(Refusal.NOT_AN_ADDON)
        val mine = users.loadedEntries().filter { it.kind == UserAddonKind.MANIFEST }
        val target = replacingKey?.let { k -> mine.firstOrNull { InTreeAddonRegistry.keyOf(it.manifestUrl) == k } }
        if (replacingKey != null && target == null) return Outcome.Refused(Refusal.DIFFERENT_ADDON)
        if (target == null && mine.any { it.manifestUrl == url }) return Outcome.Refused(Refusal.ALREADY_ADDED)
        // Turned off by Stash, origin and all: refused before anything is asked of it.
        catalog.blockReason(base, null)?.let { return Outcome.Refused(blocked(it)) }
        val unvouched = catalog.trustOf(base, null).let { it != Trust.FIRST_PARTY && it != Trust.OFFICIAL }
        val read = when (val r = client.readManifest(base, publicOnly = unvouched, file = HttpAddonClient.manifestFileOf(url))) {
            is AddonResult.Ok -> r.value
            is AddonResult.Failed -> {
                Log.i(TAG, "add from a link: the manifest did not read (${r.failure.javaClass.simpleName})")
                return Outcome.Refused(refusalOf(r.failure))
            }
        }
        val manifest = read.manifest
        Log.i(TAG, "preview ${read.dialect.name.lowercase()} ${manifest.id}")
        if (manifest.kind != AddonKind.SERVER) return Outcome.Refused(Refusal.RUNS_ON_PHONE)
        catalog.blockReason(base, manifest.id)?.let { return Outcome.Refused(blocked(it)) }
        if (!worthAdding(manifest)) return Outcome.Refused(Refusal.NOTHING_TO_PLAY)
        if (target != null) {
            val was = target.id ?: registry.entries.value.firstOrNull { it.key == replacingKey }?.manifest?.manifest?.id
            val sameOrigin = AddonIndex.originOf(target.manifestUrl) == AddonIndex.originOf(url)
            if (!sameOrigin || (was != null && was != manifest.id)) return Outcome.Refused(Refusal.DIFFERENT_ADDON)
        } else if (idTaken(manifest.id, mine)) {
            return Outcome.Refused(Refusal.ALREADY_ADDED)
        }
        val trust = catalog.trustOf(base, manifest.id)
        val agreed = target?.consent
        return Outcome.Ready(
            Preview(
                manifestUrl = url,
                id = manifest.id,
                name = manifest.name,
                version = manifest.version,
                description = manifest.description,
                host = InTreeAddonRegistry.hostOf(base),
                uses = uses(manifest, allowsDownloads = read.eclipse?.allowDownloads != false),
                needsToken = manifest.auth?.token == TokenPlacement.HEADER,
                trust = trust,
                sees = AddonSees.of(manifest),
                needsConsent = trust == Trust.UNOFFICIAL && (agreed == null || !agreed.containsAll(AddonSees.namesOf(manifest))),
                playsSongs = PlayOrder.playsSongs(manifest),
                replacesKey = replacingKey,
            ),
        )
    }

    /**
     * Adds [preview] (or replaces the link it [replaces][Preview.replacesKey]), with [token] when
     * the manifest asks for one. [agreed]: the user tapped "Add anyway" on the "Not made by Stash"
     * sheet; an add-on that needs it is never added without.
     */
    suspend fun add(preview: Preview, token: String?, agreed: Boolean = false): Outcome {
        val t = token?.trim()?.takeIf { it.isNotEmpty() }
        if (preview.needsToken && t == null) return Outcome.Refused(Refusal.NEEDS_PASSWORD)
        if (t != null && !InstalledAddon.isHeaderSafe(t)) return Outcome.Refused(Refusal.BAD_PASSWORD)
        if (preview.needsConsent && !agreed) return Outcome.Refused(Refusal.NOT_AGREED)
        val base = HttpAddonClient.baseOf(preview.manifestUrl) ?: return Outcome.Refused(Refusal.NOT_AN_ADDON)
        // The index may have changed since the preview.
        catalog.blockReason(base, preview.id)?.let { return Outcome.Refused(blocked(it)) }
        val mine = users.loadedEntries().filter { it.kind == UserAddonKind.MANIFEST }
        val target = preview.replacesKey?.let { k -> mine.firstOrNull { InTreeAddonRegistry.keyOf(it.manifestUrl) == k } }
        if (preview.replacesKey != null && target == null) return Outcome.Refused(Refusal.DIFFERENT_ADDON)
        if (target == null && idTaken(preview.id, mine)) return Outcome.Refused(Refusal.ALREADY_ADDED)
        // What the user agreed its server may see. An add-on Stash runs is never asked about.
        val consent = if (preview.trust == Trust.UNOFFICIAL) preview.sees.map { it.name }.toSet() else target?.consent
        val entry = UserAddonEntry(
            manifestUrl = preview.manifestUrl,
            token = t.takeIf { preview.needsToken },
            enabled = target?.enabled ?: true,
            id = preview.id,
            name = preview.name,
            consent = consent,
            addedAt = target?.addedAt ?: System.currentTimeMillis(),
        )
        if (target != null) {
            users.replace(target.manifestUrl, entry)
            // "Needs a new link" is over: the pause its 401 started ends now (§2.9).
            client.forgive(preview.id)
            Log.i(TAG, "an add-on's link was replaced: ${preview.id}")
        } else {
            // Nothing an earlier add-on with this id said about a song carries over (§2.4).
            refs.forget(preview.id)
            client.forgive(preview.id)
            users.put(entry)
            Log.i(TAG, "add-on added from a link: ${preview.id}")
        }
        val key = InTreeAddonRegistry.keyOf(preview.manifestUrl)
        justAdded.value = key
        return Outcome.Added(key)
    }

    /** Removes the user's add-on [key] ([InTreeAddonRegistry.keyOf]): its refs, its place in the play order and any pause go with it. */
    suspend fun remove(key: String) {
        val entry = entry(key) ?: return
        val id = entry.id ?: registry.entries.value.firstOrNull { it.key == key }?.manifest?.manifest?.id
        users.remove(entry.manifestUrl)
        if (id != null) {
            // Its refs go with it, so a later add-on with the same id never inherits them (§2.4).
            refs.forget(id)
            playOrder.forget(PlayOrder.addonKey(id))
            client.forgive(id)
        }
        Log.i(TAG, "a user add-on was removed")
    }

    /** Switches the user's add-on [key] on or off. Off, it is never asked; it keeps its row and its place. */
    suspend fun setEnabled(key: String, enabled: Boolean) {
        val entry = entry(key) ?: return
        users.setEnabled(entry.manifestUrl, enabled)
    }

    /**
     * The user agreed to what the add-on [key] now wants to see ("Wants to see more", §2.7): what
     * its manifest lists today is recorded, and it is asked again.
     */
    suspend fun agree(key: String) {
        val entry = entry(key) ?: return
        val manifest = registry.entries.value.firstOrNull { it.key == key }?.manifest?.manifest ?: return
        users.setConsent(entry.manifestUrl, AddonSees.namesOf(manifest))
        Log.i(TAG, "the user agreed to what an add-on sees now: ${manifest.id}")
    }

    private suspend fun entry(key: String): UserAddonEntry? =
        users.loadedEntries().firstOrNull { it.kind == UserAddonKind.MANIFEST && InTreeAddonRegistry.keyOf(it.manifestUrl) == key }

    /** An add-on by this id is on, or the user has one by it (switched off counts). */
    private fun idTaken(id: String, mine: List<UserAddonEntry>): Boolean =
        registry.addons.value.any { it.id == id } || mine.any { it.id == id } ||
            registry.entries.value.any { it.manifest?.manifest?.id == id }

    /** A link as kept, or why it can't be one. */
    sealed interface Normalised {
        data class Url(val url: String) : Normalised {
            override fun toString(): String = "Url"
        }

        data class No(val reason: Refusal) : Normalised
    }

    /**
     * The manifest URL behind what the user pasted, shared or scanned (§2.2), or why there is none:
     *  - the add-on's root, with or without a final `/`, means `<root>/manifest.json`;
     *  - a `/manifest.json`, or any other `*.json` (BitChord-style links), is taken as it is;
     *  - a final `/manifest` is dropped (LastWave writes its bases that way);
     *  - the path is kept, so a token in it survives;
     *  - `https://` is added when no scheme was typed;
     *  - a query string or a fragment is refused: a token belongs in the path (SPEC.md 6);
     *  - then the URL rule and the base rules (§2.6).
     */
    fun normalise(link: String): Normalised {
        var u = link.trim()
        if (u.isEmpty() || u.any { it.isWhitespace() }) return Normalised.No(Refusal.NOT_AN_ADDON)
        if ("://" !in u) u = "https://$u"
        if ('?' in u || '#' in u) return Normalised.No(Refusal.NOT_AN_ADDON)
        val scheme = u.substringBefore("://").lowercase()
        val rest = u.substringAfter("://")
        val file = rest.substringAfterLast('/', missingDelimiterValue = "")
        val url = if (file.length > ".json".length && file.endsWith(".json")) {
            "$scheme://$rest"
        } else {
            // Only a path's last part is ever dropped: the host is never touched.
            val root = rest.trimEnd('/').let { if ('/' in it) it.removeSuffix("/manifest") else it }
            "$scheme://$root/${HttpAddonClient.MANIFEST_FILE}"
        }
        val options = linkOptions()
        return when {
            AddonValidator.isAllowedUrl(url, options) -> Normalised.Url(url)
            AddonValidator.isLocalAddressUrl(url, options) -> Normalised.No(Refusal.LOCAL_ADDRESS)
            url.startsWith("http://") -> Normalised.No(Refusal.NOT_HTTPS)
            else -> Normalised.No(Refusal.NOT_AN_ADDON)
        }
    }

    /** The rules a link the user adds follows: https, and the base rules (phase 4 §2.6). */
    private fun linkOptions() = AddonValidator.Options(allowLocalHttp = host.allowLocalHttp, publicOnly = true)

    private fun refusalOf(f: AddonFailure): Refusal = when (f) {
        is AddonFailure.InvalidResponse -> when {
            f.reason.startsWith(AddonValidator.NEEDS_NEWER_STASH) -> Refusal.NEEDS_NEWER_STASH
            f.reason.startsWith(AddonEclipse.ONLY_BITCHORD) -> Refusal.ONLY_BITCHORD
            f.reason.startsWith(AddonEclipse.PRIVATE_ADDRESS) -> Refusal.LOCAL_ADDRESS
            else -> Refusal.NOT_AN_ADDON
        }
        is AddonFailure.Refused -> when {
            f.onlyBitChord -> Refusal.ONLY_BITCHORD
            // 404 and its like: there is no manifest at that address.
            f.status in 400..499 && f.status != 408 && f.status != 429 -> Refusal.NOT_AN_ADDON
            else -> Refusal.UNREACHABLE
        }
        AddonFailure.Network, AddonFailure.Timeout -> Refusal.UNREACHABLE
        else -> Refusal.NOT_AN_ADDON
    }

    companion object {
        private const val TAG = "AddonLinks"

        fun blocked(reason: BlockReason): Refusal = when (reason) {
            BlockReason.HARMFUL -> Refusal.BLOCKED_HARMFUL
            BlockReason.BROKEN -> Refusal.BLOCKED_BROKEN
            BlockReason.IMPERSONATION -> Refusal.BLOCKED_IMPERSONATION
        }

        /**
         * What Add from a link accepts (phase 4 §2.4): songs to play or keep (`stream` or
         * `download`, found by `search` or `resolve`), or lyrics. One that offers only pages
         * (`search`, `album`, `artist`, `catalog`) gives Stash nothing to play yet.
         */
        fun worthAdding(m: Manifest): Boolean = PlayOrder.playsSongs(m) || m.offers(Resource.LYRICS)

        /**
         * What the preview and the add-on's page name (phase 4 §2.4): only what Stash uses. Lossless
         * when it declares a lossless tier; Downloads when it can hand over a lossless file (a
         * download is lossless only: Stash has its own lossy path).
         */
        fun uses(m: Manifest, allowsDownloads: Boolean = true): List<Use> {
            val finds = m.offers(Resource.SEARCH) || m.offers(Resource.RESOLVE)
            val plays = finds && m.offers(Resource.STREAM)
            val gives = finds && (m.offers(Resource.DOWNLOAD) || m.offers(Resource.STREAM))
            val tiers = m.stream?.knownTiers.orEmpty()
            val lossless = tiers.any { it.isLossless }
            return listOfNotNull(
                Use.PLAYS_SONGS.takeIf { plays },
                Use.LOSSLESS.takeIf { gives && lossless },
                Use.DOWNLOADS.takeIf { gives && allowsDownloads && (lossless || tiers.isEmpty()) },
                Use.LYRICS.takeIf { m.offers(Resource.LYRICS) },
            )
        }
    }
}

/**
 * Share → Stash for an add-on (add-ons plan phase 4 §2.2): the first https link in a shared text
 * that names a manifest file (`…/manifest.json`, or another `*.json` as BitChord allows). Anything
 * else is not an add-on link, so a share never quietly turns into one. Finding a link adds
 * nothing: it only opens the Add screen at its preview.
 */
object AddonLinkDetector {
    private val LINK = Regex("https://[^\\s<>\"']+", RegexOption.IGNORE_CASE)

    fun find(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return LINK.findAll(text).map { it.value.trimEnd('.', ',', ';', ')', ']', '!') }.firstOrNull { link ->
            val file = link.substringAfter("://").substringAfterLast('/', missingDelimiterValue = "")
            link.length <= 2048 && '?' !in link && '#' !in link && file.length > ".json".length && file.endsWith(".json")
        }
    }
}
