package com.stash.data.download.lossless.addon

import com.stash.addons.contract.Quota
import com.stash.addons.contract.Resource
import com.stash.core.addons.AddonEntryState
import com.stash.core.addons.AddonHold
import com.stash.core.addons.AddonSees
import com.stash.core.addons.BlockReason
import com.stash.core.addons.HttpAddonClient
import com.stash.core.addons.InTreeAddonRegistry
import com.stash.core.addons.NamedRungAddons
import com.stash.core.addons.OfficialEntry
import com.stash.core.addons.Trust
import com.stash.core.addons.UserAddonEntry
import com.stash.core.addons.UserAddonKind
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.LosslessAvailability
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.PlayOrder
import com.stash.data.download.lossless.PlayOrderSwitches
import com.stash.data.download.lossless.tidal.OwnTidalServer
import com.stash.data.download.lossless.tidal.TidalWires
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Settings › Add-ons as one value (add-ons plan phase 4 §2.1, §2.8, §2.9): the play order's rows,
 * the add-ons that have no place in it, and the official add-ons still to add. Built here, from
 * the same pieces the chains read (the registry, the play order, the switches, the signed index),
 * so the screen can't say anything the app doesn't do. The screens only pick the words.
 *
 * Nothing here holds a link or a token: a row names its add-on by [Row.key] and shows its host.
 */
@Singleton
class AddonOverview @Inject constructor(
    private val users: EncryptedUserAddons,
    private val registry: InTreeAddonRegistry,
    private val catalog: SignedIndexAddonCatalog,
    private val index: AddonIndexFetcher,
    private val prefs: LosslessSourcePreferences,
    private val playOrder: PlayOrder,
    private val switches: PlayOrderSwitches,
    availability: LosslessAvailability,
    private val wires: TidalWires,
    private val own: OwnTidalServer,
    private val client: HttpAddonClient,
    private val limiter: AggregatorRateLimiter,
    private val links: AddonLinks,
) {
    enum class Kind { QOBUZ, TIDAL, ADDON }

    /** The mark on a row (§1.1): every add-on says one or the other; the user's own accounts and servers say neither. */
    enum class Mark { NONE, OFFICIAL, NOT_MADE_BY_STASH }

    /** How a row is doing, in the order the page's words are checked (§2.9). */
    enum class Status {
        WORKING,

        /** Switched off here. */
        OFF,

        /** The signed index turned it off; [Row.blockReason] says why. */
        TURNED_OFF_BY_STASH,

        /** Its manifest now lists more than the user agreed it may see: it waits for them. */
        WANTS_MORE,

        /** A 401: the token in its link stopped working. */
        NEEDS_NEW_LINK,

        /** Its manifest now asks for a newer Stash than this one. */
        NEEDS_NEWER_STASH,

        /** Its link now serves another add-on than the one the user approved: not used. */
        CHANGED,

        /** One of Stash's own add-ons has its id, and is the one in use: this copy is not. */
        REPLACED,

        /** It now serves only the genuine BitChord, and Stash respects that: not used. */
        ONLY_BITCHORD,

        /** No manifest could be read, and none is kept. */
        NOT_REACHABLE,
    }

    data class Row(
        /** `qobuz`, `tidal`, `addon:<id>`: the play-order key. An add-on whose id isn't known yet: `entry:<handle>`. */
        val key: String,
        val kind: Kind,
        /** An add-on's name; null for the two named rows, which the screen names. */
        val name: String?,
        /** The host of an add-on Stash doesn't run, or of the user's own Tidal server. */
        val host: String?,
        val mark: Mark,
        val status: Status,
        val blockReason: BlockReason? = null,
        /** The switch. */
        val on: Boolean,
        val canSwitch: Boolean,
        /** Remove, for an add-on the user added; "Use Stash's Tidal instead" for their own Tidal server. */
        val canRemove: Boolean,
        /** It has a place in the play order. */
        val playsSongs: Boolean,
        val version: String? = null,
        val uses: List<AddonLinks.Use> = emptyList(),
        val sees: Set<AddonSees> = emptySet(),
        /** The one line the signed index says about it. */
        val summary: String? = null,
        /** The registry's handle of its entry ([InTreeAddonRegistry.keyOf]); null for the named rows. */
        val entryKey: String? = null,
        val userAdded: Boolean = false,
        /** The Tidal row: the server is the user's own. */
        val ownServer: Boolean = false,
        /** Its manifest lists `quota`: it has an allowance to show. */
        val metered: Boolean = false,
    )

    data class State(
        /** The play order, as asked: Your Qobuz (when connected), Tidal, and each add-on that plays songs. */
        val order: List<Row> = emptyList(),
        /** Add-ons with no place in the order (lyrics only, or not read yet). */
        val others: List<Row> = emptyList(),
        /** Official add-ons the user hasn't added. */
        val official: List<OfficialEntry> = emptyList(),
        /** Nothing was ever dragged. */
        val automatic: Boolean = true,
        /** The whole order's keys, the rows not on screen included: what a drag is applied to. */
        val keys: List<String> = emptyList(),
        /** False until the first value has been built. */
        val loaded: Boolean = false,
        /**
         * The user's add-ons could not be read this run (the phone's Keystore failed): the ones
         * they added are not shown, and that is not the same as having none.
         */
        val unreadable: Boolean = false,
    ) {
        fun row(key: String): Row? = order.firstOrNull { it.key == key } ?: others.firstOrNull { it.key == key }
    }

    /** What the two named rows need: is a Qobuz account connected, is a Tidal server known, the user's own server's host, and the rows switched off. */
    data class Named(val hasQobuz: Boolean, val tidalKnown: Boolean, val ownHost: String?, val off: Set<String>)

    private val named: Flow<Named> =
        combine(availability.qbdlxEnabled, wires.known, own.endpoint, switches.off) { qobuz, tidal, ownBase, off ->
            Named(qobuz, tidal, ownBase?.let(InTreeAddonRegistry::hostOf)?.takeIf { it.isNotEmpty() }, off)
        }

    val state: Flow<State> = combine(
        combine(users.entries, registry.entries, registry.reading, users.unreadable) { u, e, reading, unreadable ->
            Read(u, e, reading, unreadable)
        },
        named,
        prefs.playOrder,
        combine(index.index, catalog.official, client.unauthorized) { _, official, unauthorized -> official to unauthorized },
    ) { read, named, saved, (official, unauthorized) ->
        build(read.users, read.entries, read.reading, named, saved, official, unauthorized, catalog::described).copy(unreadable = read.unreadable)
    }.distinctUntilChanged()

    private data class Read(val users: List<UserAddonEntry>, val entries: List<AddonEntryState>, val reading: Boolean, val unreadable: Boolean)

    /**
     * The row [key] of the list on screen ([shown], the state the screen drew) was dropped at index
     * [to] of it: the first drag saves the whole order.
     */
    suspend fun move(shown: State, key: String, to: Int): Boolean {
        val next = PlayOrder.moved(shown.keys, shown.order.map { it.key }, key, to) ?: return true
        return saved { playOrder.set(next) }
    }

    /** One place up or down (TalkBack's Move up / Move down, and the add-on page's). */
    suspend fun nudge(shown: State, key: String, up: Boolean): Boolean = saved {
        playOrder.change { savedNow ->
            // The rows the screen drew, in the order as it is saved now. A second tap before the first
            // one's save has flowed back to the screen moves the row once more; from the order on
            // screen it repeated the first move (review-885 N3).
            val keys = PlayOrder.effective(savedNow, shown.keys.filter { PlayOrder.addonIdOf(it) != null }).keys
            val onScreen = shown.order.map { it.key }.sortedBy { keys.indexOf(it) }
            val at = onScreen.indexOf(key)
            if (at < 0) null else PlayOrder.moved(keys, onScreen, key, if (up) at - 1 else at + 1)
        }
    }

    /** The switch. False when it could not be saved: the screen says so, and nothing changed. */
    suspend fun setOn(row: Row, on: Boolean): Boolean = when {
        row.kind == Kind.TIDAL -> saved { switches.setOn(PlayOrder.TIDAL, on) }
        row.kind == Kind.QOBUZ -> true
        row.userAdded -> row.entryKey?.let { links.setEnabled(it, on) } ?: true
        PlayOrder.addonIdOf(row.key) != null -> saved { switches.setOn(row.key, on) }
        else -> true
    }

    /**
     * Remove: the user's add-on and everything it left, or their own Tidal server (the rung goes back
     * to Stash's). False when it could not be saved.
     */
    suspend fun remove(row: Row): Boolean = when {
        // It says itself whether it was saved (and never throws); off the main thread, as the seal is the Keystore's work.
        row.kind == Kind.TIDAL && row.ownServer -> withContext(Dispatchers.Default) { own.remove() }
        row.userAdded -> row.entryKey?.let { links.remove(it) } ?: true
        else -> true
    }

    /** "Wants to see more": the user agreed to what it lists now. False when it could not be saved. */
    suspend fun agree(row: Row): Boolean = row.entryKey?.let { links.agree(it, row.sees) } ?: true

    /** Saving the order after a drag or a nudge; false when it could not be saved. */
    private suspend fun saved(change: suspend () -> Unit): Boolean = withContext(Dispatchers.Default) {
        try {
            change()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "a change in Add-ons could not be saved: ${e.javaClass.simpleName}")
            false
        }
    }

    /** Whether the add-on [row] is paused right now (it failed, or asked to be left alone): "Not reachable right now". */
    suspend fun paused(row: Row): Boolean {
        val id = PlayOrder.addonIdOf(row.key) ?: return false
        // Asked from a screen: off its thread (review-885 M10).
        return withContext(Dispatchers.Default) { limiter.stateOf(HttpAddonClient.rateKey(id)).isCircuitBroken }
    }

    /** The allowance of a metered add-on, asked once; null when it doesn't say. */
    suspend fun quota(row: Row): Quota? {
        val id = PlayOrder.addonIdOf(row.key) ?: return null
        val addon = registry.addons.value.firstOrNull { it.id == id && !NamedRungAddons.owns(it) } ?: return null
        // Off the screen's thread: the answer is someone else's JSON, read on the thread that asks.
        // And outside the add-on's breaker: looking at its page is not a play, so a `/quota` that
        // fails must never pause its plays, however often the page is opened (review-885 M10).
        return withContext(Dispatchers.Default) { client.quota(addon, circuit = false).valueOrNull() }
    }

    companion object {
        private const val TAG = "AddonOverview"

        /** Rows whose switch shows off. */
        private val NOT_ON = setOf(Status.OFF, Status.TURNED_OFF_BY_STASH, Status.CHANGED, Status.REPLACED, Status.ONLY_BITCHORD)

        /** Rows with no switch: switching them on would do nothing. */
        private val NO_SWITCH = setOf(Status.TURNED_OFF_BY_STASH, Status.CHANGED, Status.REPLACED, Status.ONLY_BITCHORD)

        /**
         * The page's state from plain values, so every row's words can be checked without the app around it.
         * [described]: what the signed index says about the add-on with an id (its one line), if anything.
         */
        fun build(
            userEntries: List<UserAddonEntry>,
            entries: List<AddonEntryState>,
            reading: Boolean,
            named: Named,
            saved: List<String>?,
            official: List<OfficialEntry>,
            unauthorized: Set<String>,
            described: (String) -> OfficialEntry? = { null },
        ): State {
            val byKey = entries.associateBy { it.key }
            val userRows = userEntries.filter { it.kind == UserAddonKind.MANIFEST }.map { e ->
                val handle = InTreeAddonRegistry.keyOf(e.manifestUrl)
                addonRow(byKey[handle], e, handle, reading, unauthorized, described)
            }
            // The first-party Tidal add-on is the Tidal row's; one of Stash's own that hasn't been read has no name to show yet.
            // A default the user's own add-on by the same id stands in for has no row: the user's is the one in use, and has one.
            val defaultRows = entries
                .filter { !it.userAdded && it.hold != AddonHold.REPLACED }
                .filter { !(it.firstParty && (it.manifest == null || it.manifest?.manifest?.id == NamedRungAddons.TIDAL)) }
                .map { addonRow(it, null, it.key, reading, unauthorized, described) }
            val addonRows = (userRows + defaultRows).distinctBy { it.key }
            val inOrder = addonRows.filter { it.playsSongs }
            val order = PlayOrder.effective(saved, inOrder.map { it.key })
            val qobuz = Row(
                key = PlayOrder.QOBUZ, kind = Kind.QOBUZ, name = null, host = null, mark = Mark.NONE, status = Status.WORKING,
                on = true, canSwitch = false, canRemove = false, playsSongs = true,
            ).takeIf { named.hasQobuz }
            val tidalOff = PlayOrder.TIDAL in named.off
            val tidal = Row(
                key = PlayOrder.TIDAL, kind = Kind.TIDAL, name = null, host = named.ownHost,
                mark = if (named.ownHost != null) Mark.NONE else Mark.OFFICIAL,
                status = if (tidalOff) Status.OFF else Status.WORKING,
                on = !tidalOff, canSwitch = true, canRemove = named.ownHost != null, playsSongs = true,
                summary = described(NamedRungAddons.TIDAL)?.summary.takeIf { named.ownHost == null },
                ownServer = named.ownHost != null,
            ).takeIf { named.tidalKnown }
            // The official add-ons the user already has. Only a row that is Official counts: an add-on
            // Stash doesn't run that calls itself by an official one's id must not take that one out of
            // "Get add-ons" (review-885 N5).
            val taken = addonRows.filter { it.mark == Mark.OFFICIAL }.mapNotNull { PlayOrder.addonIdOf(it.key) }.toSet()
            return State(
                order = order.sorted(listOfNotNull(qobuz, tidal) + inOrder) { it.key },
                others = addonRows.filterNot { it.playsSongs },
                official = official.filterNot { it.id in taken },
                automatic = order.automatic,
                keys = order.keys,
                loaded = true,
            )
        }

        private fun addonRow(
            s: AddonEntryState?, e: UserAddonEntry?, handle: String, reading: Boolean, unauthorized: Set<String>, described: (String) -> OfficialEntry?,
        ): Row {
            // The add-on the user approved: a manifest under another id is not it, and none of its words are shown.
            val manifest = s?.manifest?.manifest?.takeUnless { e?.id != null && it.id != e.id }
            val id = manifest?.id ?: e?.id
            val trust = s?.trust ?: Trust.UNOFFICIAL
            val status = when {
                s?.hold == AddonHold.BLOCKED -> Status.TURNED_OFF_BY_STASH
                e?.enabled == false || s?.hold == AddonHold.OFF -> Status.OFF
                s?.hold == AddonHold.ONLY_BITCHORD -> Status.ONLY_BITCHORD
                s?.hold == AddonHold.CHANGED -> Status.CHANGED
                s?.hold == AddonHold.REPLACED -> Status.REPLACED
                s?.hold == AddonHold.WANTS_MORE -> Status.WANTS_MORE
                id != null && id in unauthorized -> Status.NEEDS_NEW_LINK
                s?.needsNewerStash == true -> Status.NEEDS_NEWER_STASH
                // Not read yet (just added, or the app just started): no verdict until it has been.
                s != null && s.hold == AddonHold.NOT_LOADED && !reading -> Status.NOT_REACHABLE
                else -> Status.WORKING
            }
            val official = trust == Trust.FIRST_PARTY || trust == Trust.OFFICIAL
            return Row(
                // A copy that one of Stash's own add-ons replaced is not that add-on: it keeps a row of its
                // own (to say so, and to be removed), and never stands in the other's row or place.
                key = id?.takeUnless { status == Status.REPLACED }?.let(PlayOrder::addonKey) ?: "entry:$handle",
                kind = Kind.ADDON,
                name = manifest?.name ?: e?.name ?: s?.host.orEmpty(),
                // Always, for an add-on Stash doesn't run: its name is its own word, its address is not.
                host = (s?.host ?: e?.manifestUrl?.let(HttpAddonClient::baseOf)?.let(InTreeAddonRegistry::hostOf))?.takeIf { !official && it.isNotEmpty() },
                mark = if (official) Mark.OFFICIAL else Mark.NOT_MADE_BY_STASH,
                status = status,
                blockReason = s?.blockReason,
                on = status !in NOT_ON,
                canSwitch = status !in NO_SWITCH,
                canRemove = e != null,
                playsSongs = manifest != null && id != null && PlayOrder.playsSongs(manifest) && status != Status.REPLACED,
                version = manifest?.version,
                uses = manifest?.let { AddonLinks.uses(it, allowsDownloads = s?.manifest?.eclipse?.allowDownloads != false) }.orEmpty(),
                sees = manifest?.let { AddonSees.of(it, allowsDownloads = s?.manifest?.eclipse?.allowDownloads != false) }.orEmpty(),
                summary = id?.let(described)?.summary?.takeIf { official },
                entryKey = handle,
                userAdded = e != null,
                metered = manifest?.offers(Resource.QUOTA) == true,
            )
        }
    }

    /** Reads the index and the manifests again when they are stale: called as Settings › Add-ons opens (§1.5). */
    suspend fun refreshIfStale() {
        index.refresh()
        registry.refresh(force = false)
    }
}
