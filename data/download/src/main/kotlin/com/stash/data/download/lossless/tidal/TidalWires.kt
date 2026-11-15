package com.stash.data.download.lossless.tidal

import com.stash.addons.contract.Resource
import android.util.Log
import com.stash.core.addons.AddonRegistry
import com.stash.core.addons.InstalledAddon
import com.stash.core.addons.NamedRungAddons
import com.stash.data.download.lossless.PlayOrder
import com.stash.data.download.lossless.PlayOrderSwitches
import com.stash.data.download.lossless.bitchord.LegacyRelayWire
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import com.stash.data.download.lossless.relay.TidalRelay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which server, over which wire, the Tidal rung asks (plan §5.4). In order:
 *  1. **The user's own server** ([OwnTidalServer], sealed with the add-ons since phase 4 §2.10):
 *     the legacy wire, as before. An explicit choice beats an implicit one, and its token goes
 *     with it.
 *  2. **The first-party Tidal add-on**: the v1 wire, when an enabled `first_party` default entry
 *     whose manifest id is `tidal` has a loaded manifest (a kept one counts: the registry keeps the
 *     last good manifest across restarts). The `default_addons` entry IS the switch (decision D9):
 *     publish it to turn the v1 wire on, publish without it to turn it off. No other flag.
 *  3. **The signed config's `tidal_relay_url`**: the legacy wire, exactly as today.
 *  4. Nothing: the rung is off.
 *
 * Switched off on its row in Settings › Add-ons (add-ons plan phase 4 §2.8), the rung has no route
 * at all, whatever servers it knows.
 *
 * **A v1 face that stops reading our calls is set aside** (review-881 M7). After
 * [AddonV1Wire.HARD_FAILURE_LIMIT] hard failures in a row (a 401, a 400, an answer that breaks the
 * contract; [AddonV1Wire.broken]) step 2 is skipped and lookups go to the signed config's relay,
 * which answers on the same server, until the next applied config ([LosslessConfigFetcher.refreshedAt]
 * moves: every 6 hours and at each cold start). Then the v1 wire gets another go. Without it, a
 * broken face (a wrong token in the published entry, a refused User-Agent) failed every Tidal
 * lookup until a publish without the entry reached the phone. Only when there is a relay to fall
 * back to, and never on a forced wire.
 *
 * Debug builds only ([TidalDebugSettings]): force either wire, and stand an origin in for the
 * signed config's relay and Tidal add-on. A forced v1 with no add-on loaded is off, so a test never
 * quietly runs on the other wire.
 */
@Singleton
class TidalWires @Inject constructor(
    private val config: LosslessConfigFetcher,
    private val registry: AddonRegistry,
    val legacy: LegacyRelayWire,
    private val v1: AddonV1Wire,
    private val debug: TidalDebugSettings,
    private val own: OwnTidalServer,
    /** Settings › Add-ons' switch on the Tidal row (add-ons plan phase 4 §2.8); everything on in hand-built tests. */
    private val switches: PlayOrderSwitches = PlayOrderSwitches.allOn(),
) {
    /** Where a lookup goes, before it is bound to a wire. */
    sealed interface Route {
        data class Relay(val relay: TidalRelay) : Route {
            override fun toString(): String = "Relay(userOwned=${relay.userOwned})"
        }

        data class Addon(val addon: InstalledAddon) : Route
    }

    /** The first-party Tidal add-on, when it is loaded and can search and stream. */
    fun tidalAddon(): InstalledAddon? =
        registry.firstParty(NamedRungAddons.TIDAL)?.takeIf { it.offers(Resource.SEARCH) && it.offers(Resource.STREAM) }

    /** The signed config's relay, or the debug override standing in for it. */
    fun ownerRelay(): TidalRelay? = debug.relay(config.tidalRelay.value)

    /** The user's own server is set (a spent day never stops it: its answers never touch Stash's relay). */
    suspend fun ownServerSet(): Boolean = own.now() != null

    /** The user switched Tidal off in Settings › Add-ons: the rung asks nothing, whichever server it has. */
    suspend fun switchedOff(): Boolean = switches.isOff(PlayOrder.TIDAL)

    /**
     * No route ([route] null) is the rung's settled answer: it is switched off, or a config is
     * applied and gives it none, or this build has no config to wait for. Before the cached config
     * has loaded, in a cold start's first moment, a Tidal user's phone has no route either, and that
     * only means "not known yet" (review-885 round 2, N1).
     */
    suspend fun offSettled(): Boolean = switchedOff() || config.applied.value || !config.enabled

    /**
     * The route a lookup takes now (the class doc's order); null = the rung is off. [ownServer] false
     * skips the user's own server. Switched off in Settings › Add-ons, there is no route at all: it
     * must not fall through to Stash's relay.
     */
    suspend fun route(ownServer: Boolean = true): Route? {
        if (switchedOff()) return null
        return routeIfOn(ownServer)
    }

    /** [route] as it would be with the switch on: what the Tidal row and its page describe. */
    suspend fun routeIfOn(ownServer: Boolean = true): Route? {
        val forced = debug.state.value.wire
        if (ownServer && forced != TidalDebugSettings.Wire.V1) {
            own.now()?.let { return Route.Relay(TidalRelay(it.base, it.token, userOwned = true)) }
        }
        val relay = ownerRelay()
        val setAside = forced == TidalDebugSettings.Wire.AUTO && relay != null && v1SetAside()
        if (forced != TidalDebugSettings.Wire.LEGACY && !setAside) tidalAddon()?.let { return Route.Addon(it) }
        if (forced == TidalDebugSettings.Wire.V1) return null
        return relay?.let { Route.Relay(it) }
    }

    /** [LosslessConfigFetcher.refreshedAt] when the v1 wire was set aside; null while it is in use. */
    @Volatile private var setAsideAtRefresh: Long? = null

    /**
     * True while the v1 wire is set aside (the class doc). A config applied since it was set aside
     * ends that and starts its failure counts over. So does one applied after the last counted
     * failure and before anyone looked: those failures say nothing about the face after it, and
     * counting them set a mended face aside for one more config cycle (review-881 R2-n1). One
     * caller at a time, so two lookups cannot each see half of a change.
     */
    @Synchronized
    private fun v1SetAside(): Boolean {
        val at = setAsideAtRefresh
        if (at != null) {
            if (config.refreshedAt.value == at) return true
            v1.forgive()
            setAsideAtRefresh = null
            Log.i(TAG, "a config was applied: the v1 add-on is asked again")
            return false
        }
        if (!v1.broken) return false
        val refreshed = config.refreshedAt.value
        // Both are the phone's clock: [LosslessConfigFetcher.refreshedAt] is stamped with it.
        if (v1.lastHardFailureAtMs < refreshed) {
            v1.forgive()
            return false
        }
        setAsideAtRefresh = refreshed
        Log.w(TAG, "the v1 add-on failed ${AddonV1Wire.HARD_FAILURE_LIMIT} calls in a row; using the legacy relay until the next applied config")
        return true
    }

    /** The wire for a lookup, bound to its server; null = the rung is off. */
    suspend fun current(clock: () -> Long): TidalWire? = when (val r = route()) {
        is Route.Relay -> legacy.bind(r.relay, clock)
        is Route.Addon -> v1.bind(r.addon, clock)
        null -> null
    }

    /**
     * The diagnostics line (plan §5.4): `v1 add-on`, `legacy relay`, `legacy relay (your own
     * server)` or `off`, never a base or a token. Debug overrides are named so a bundle shows them,
     * and so is a v1 wire set aside after failures.
     */
    suspend fun describe(): String {
        val d = debug.state.value
        val r = route()
        val what = when (r) {
            is Route.Addon -> TidalWire.Kind.V1.label
            is Route.Relay -> TidalWire.Kind.LEGACY.label + if (r.relay.userOwned) " (your own server)" else ""
            null -> if (switchedOff()) "off (switched off in Add-ons)" else "off"
        }
        val notes = listOfNotNull(
            "v1 add-on set aside after failures".takeIf { setAsideAtRefresh != null && r is Route.Relay && !r.relay.userOwned },
            "forced".takeIf { d.wire != TidalDebugSettings.Wire.AUTO },
            "debug server override".takeIf { d.origin != null },
        )
        return if (notes.isEmpty()) what else "$what (${notes.joinToString(", ")})"
    }

    /**
     * Non-suspending: some Tidal server is known, for the player's media-source factory, which runs
     * on the playback thread. Eager, so it has settled long before the first track is prepared.
     */
    val configured: StateFlow<Boolean> =
        combine(own.endpoint, config.tidalRelay, registry.addons, debug.state, switches.off) { own, signed, _, d, off ->
            PlayOrder.TIDAL !in off && when (d.wire) {
                TidalDebugSettings.Wire.V1 -> tidalAddon() != null
                TidalDebugSettings.Wire.LEGACY -> own != null || debug.relay(signed) != null
                TidalDebugSettings.Wire.AUTO -> own != null || tidalAddon() != null || debug.relay(signed) != null
            }
        }.stateIn(CoroutineScope(SupervisorJob() + Dispatchers.IO), SharingStarted.Eagerly, false)

    /**
     * Some Tidal server is known, whether or not the rung is switched on: Settings › Add-ons shows
     * the Tidal row (and its switch) for as long as this holds.
     */
    val known: kotlinx.coroutines.flow.Flow<Boolean> =
        combine(own.endpoint, config.tidalRelay, registry.addons, debug.state) { own, signed, _, _ ->
            own != null || tidalAddon() != null || debug.relay(signed) != null
        }

    private companion object {
        const val TAG = "TidalWires"
    }
}
