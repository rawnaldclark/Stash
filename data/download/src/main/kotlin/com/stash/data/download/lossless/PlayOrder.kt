package com.stash.data.download.lossless

import com.stash.addons.contract.Manifest
import com.stash.addons.contract.Resource
import com.stash.core.addons.AddonRegistry
import com.stash.core.addons.AddonSwitches
import com.stash.core.addons.InstalledAddon
import com.stash.core.addons.NamedRungAddons
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The play order (add-ons plan phase 4 §2.8): ONE order for both chains, streaming
 * (`StreamSourceRegistry`) and downloads ([LosslessSourceRegistry]), and for the list Settings ›
 * Add-ons shows. Its keys are [QOBUZ] (the user's own Qobuz account), [TIDAL] (the Tidal rung:
 * Stash's, or the user's own server) and `addon:<id>` for each add-on that plays songs.
 *
 * **Automatic until the first drag**, like Home's order: Your Qobuz, Tidal, the user's add-ons in
 * the order they were added, then the add-ons Stash turns on by default. A new add-on therefore
 * joins after Stash's own lossless sources (owner decision Q3), so a server Stash doesn't run sees
 * only the songs those miss. While the order is automatic the chains still apply the own-server
 * rule ([LosslessAvailability.ownTidalFirst]). The first drag saves the whole order, and from
 * then on the user's order is the order.
 *
 * A saved order keeps keys it has no add-on for (one switched off keeps its place) and the chains
 * skip them; an add-on that isn't in it yet goes last. Removing an add-on forgets its key.
 */
@Singleton
class PlayOrder internal constructor(
    private val saved: Flow<List<String>?>,
    private val save: suspend (List<String>?) -> Unit,
    private val registry: AddonRegistry,
) {
    @Inject constructor(prefs: LosslessSourcePreferences, registry: AddonRegistry) :
        this(prefs.playOrder, prefs::setPlayOrder, registry)

    /**
     * The order as it stands. [keys] always holds [QOBUZ] and [TIDAL]. [automatic]: nothing was
     * ever dragged, so the chains may still put the user's own Tidal server ahead of Qobuz.
     */
    data class Order(val keys: List<String>, val automatic: Boolean) {
        /** Where [key] is asked; a key the order doesn't hold comes after every one it does. */
        fun position(key: String?): Int {
            val at = if (key == null) -1 else keys.indexOf(key)
            return if (at < 0) Int.MAX_VALUE else at
        }

        /** The user put Tidal ahead of their Qobuz account. */
        val tidalBeforeQobuz: Boolean get() = position(TIDAL) < position(QOBUZ)

        /** [items] in this order ([key] names each one's key), the unknown ones last in the order given. */
        fun <T> sorted(items: List<T>, key: (T) -> String?): List<T> = items.sortedBy { position(key(it)) }

        companion object {
            /** Nothing saved and no add-on on: Your Qobuz, then Tidal. */
            val AUTOMATIC = Order(listOf(QOBUZ, TIDAL), automatic = true)
        }
    }

    /** The order over the add-ons that are on; live. */
    val order: Flow<Order> =
        combine(saved, registry.addons) { saved, addons -> effective(saved, keysOf(addons)) }.distinctUntilChanged()

    /** The order right now, read from the store: what a chain walks. */
    suspend fun now(): Order = effective(saved.first(), keysOf(registry.addons.value))

    /** The saved keys, or null while the order is automatic. */
    suspend fun savedNow(): List<String>? = saved.first()

    /** Saves [keys] as the whole order: the first drag ends the automatic order. */
    suspend fun set(keys: List<String>) = save(keys.distinct())

    /** An add-on was removed: its place goes with it. Nothing to do while the order is automatic. */
    suspend fun forget(key: String) {
        val now = saved.first() ?: return
        if (key in now) save(now - key)
    }

    companion object {
        const val QOBUZ = "qobuz"
        const val TIDAL = "tidal"
        const val ADDON_PREFIX = "addon:"

        fun addonKey(id: String): String = ADDON_PREFIX + id

        /** The add-on id of an `addon:<id>` key, or null for the two named rows. */
        fun addonIdOf(key: String): String? = key.takeIf { it.startsWith(ADDON_PREFIX) }?.removePrefix(ADDON_PREFIX)

        /**
         * The order over [addonKeys] (the user's add-ons in the order added, then the defaults):
         * the [saved] one with the keys it lacks put last, or the automatic one with nothing saved.
         * [QOBUZ] and [TIDAL] are always there: a saved order that lost one gets it back at the top.
         */
        fun effective(saved: List<String>?, addonKeys: List<String>): Order {
            if (saved == null) return Order((listOf(QOBUZ, TIDAL) + addonKeys).distinct(), automatic = true)
            val kept = saved.distinct()
            val named = listOf(QOBUZ, TIDAL).filterNot { it in kept }
            return Order((named + kept + addonKeys.filterNot { it in kept }).distinct(), automatic = false)
        }

        /**
         * [order] after the row [key] of the list on screen ([shown], a part of [order] in its
         * order) is dropped at index [to] of that list. Keys that aren't on screen (Your Qobuz with
         * no account connected, an add-on that can't be shown) keep their places. Null when nothing
         * moves.
         */
        fun moved(order: List<String>, shown: List<String>, key: String, to: Int): List<String>? {
            val from = shown.indexOf(key)
            if (from < 0 || to !in shown.indices || to == from) return null
            val next = shown.toMutableList().apply { add(to, removeAt(from)) }
            val slots = next.iterator()
            val out = order.map { if (it in shown) slots.next() else it }.toMutableList()
            // A row the order didn't hold yet: it takes its place after the rest.
            while (slots.hasNext()) out += slots.next()
            return out.takeIf { it != order }
        }

        /**
         * An add-on that has a place in the play order: it finds songs (`resolve`, or `search`
         * matched on the phone) and plays or hands them over. A lyrics-only add-on has none.
         */
        fun playsSongs(m: Manifest): Boolean =
            (m.offers(Resource.SEARCH) || m.offers(Resource.RESOLVE)) && (m.offers(Resource.STREAM) || m.offers(Resource.DOWNLOAD))

        /** The play-order key of a download source id; null for one the order doesn't name. */
        fun keyOfSource(sourceId: String): String? = when {
            sourceId == QBDLX_SOURCE_ID -> QOBUZ
            sourceId == TIDAL_SOURCE_ID -> TIDAL
            sourceId.startsWith(ADDON_PREFIX) -> sourceId
            else -> null
        }

        /** The keys of the add-ons that are on and play songs, in the registry's order (the user's, then the defaults). */
        fun keysOf(addons: List<InstalledAddon>): List<String> =
            addons.filter { !NamedRungAddons.owns(it) && playsSongs(it.manifest) }.map { addonKey(it.id) }

        /** [com.stash.data.download.lossless.qbdlx.QbdlxQobuzSource.SOURCE_ID]. */
        const val QBDLX_SOURCE_ID = "qbdlx_qobuz"

        /** [com.stash.data.download.lossless.tidal.TidalLosslessSource.SOURCE_ID]. */
        const val TIDAL_SOURCE_ID = "tidal_bitchord"
    }
}

/**
 * The switches of Settings › Add-ons that are not a user add-on's own (those live in its sealed
 * entry): the Tidal rung ([PlayOrder.TIDAL]) and each add-on Stash turns on by default
 * (`addon:<id>`). Stored beside the play order. Off means never asked, in either chain.
 *
 * Every row that can be switched off here is shown in Settings › Add-ons for as long as it could
 * be asked, so a switch never outlives the screen that flips it back.
 */
@Singleton
class PlayOrderSwitches internal constructor(
    /** The keys switched off; live. */
    val off: Flow<Set<String>>,
    private val write: suspend (key: String, off: Boolean) -> Unit,
) : AddonSwitches {
    @Inject constructor(prefs: LosslessSourcePreferences) : this(prefs.playOrderOff, prefs::setPlayOrderOff)

    suspend fun isOff(key: String): Boolean = key in off.first()

    suspend fun setOn(key: String, on: Boolean) = write(key, !on)

    override val defaultsOff: Flow<Set<String>> =
        off.map { keys -> keys.mapNotNull(PlayOrder::addonIdOf).toSet() }.distinctUntilChanged()

    companion object {
        /** Everything on, nothing stored: for hand-built wiring in tests. */
        fun allOn(): PlayOrderSwitches = PlayOrderSwitches(flowOf(emptySet())) { _, _ -> }
    }
}
