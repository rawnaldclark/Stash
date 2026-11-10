package com.stash.data.download.lossless.tidal

import com.stash.addons.contract.Resource
import com.stash.core.addons.AddonRegistry
import com.stash.core.addons.InstalledAddon
import com.stash.core.addons.NamedRungAddons
import com.stash.data.download.lossless.LosslessSourcePreferences
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
 *  1. **The user's own server** (Settings › Lossless sources › Advanced): the legacy wire, as
 *     before. An explicit choice beats an implicit one, and its token goes with it.
 *  2. **The first-party Tidal add-on**: the v1 wire, when an enabled `first_party` default entry
 *     whose manifest id is `tidal` has a loaded manifest (a kept one counts: the registry keeps the
 *     last good manifest across restarts). The `default_addons` entry IS the switch (decision D9):
 *     publish it to turn the v1 wire on, publish without it to turn it off. No other flag.
 *  3. **The signed config's `tidal_relay_url`**: the legacy wire, exactly as today.
 *  4. Nothing: the rung is off.
 *
 * Debug builds only ([TidalDebugSettings]): force either wire, and stand an origin in for the
 * signed config's relay and Tidal add-on. A forced v1 with no add-on loaded is off, so a test never
 * quietly runs on the other wire.
 */
@Singleton
class TidalWires @Inject constructor(
    private val prefs: LosslessSourcePreferences,
    private val config: LosslessConfigFetcher,
    private val registry: AddonRegistry,
    val legacy: LegacyRelayWire,
    private val v1: AddonV1Wire,
    private val debug: TidalDebugSettings,
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

    /** The route a lookup takes now (the class doc's order); null = the rung is off. [ownServer] false skips the user's own server. */
    suspend fun route(ownServer: Boolean = true): Route? {
        val forced = debug.state.value.wire
        if (ownServer && forced != TidalDebugSettings.Wire.V1) {
            prefs.bitchordEndpointNow()?.let { return Route.Relay(TidalRelay(it, prefs.bitchordAccessTokenNow(), userOwned = true)) }
        }
        if (forced != TidalDebugSettings.Wire.LEGACY) tidalAddon()?.let { return Route.Addon(it) }
        if (forced == TidalDebugSettings.Wire.V1) return null
        return ownerRelay()?.let { Route.Relay(it) }
    }

    /** The wire for a lookup, bound to its server; null = the rung is off. */
    suspend fun current(clock: () -> Long): TidalWire? = when (val r = route()) {
        is Route.Relay -> legacy.bind(r.relay, clock)
        is Route.Addon -> v1.bind(r.addon, clock)
        null -> null
    }

    /**
     * The diagnostics line (plan §5.4): `v1 add-on`, `legacy relay`, `legacy relay (your own
     * server)` or `off`, never a base or a token. Debug overrides are named so a bundle shows them.
     */
    suspend fun describe(): String {
        val d = debug.state.value
        val what = when (val r = route()) {
            is Route.Addon -> TidalWire.Kind.V1.label
            is Route.Relay -> TidalWire.Kind.LEGACY.label + if (r.relay.userOwned) " (your own server)" else ""
            null -> "off"
        }
        val notes = listOfNotNull(
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
        combine(prefs.bitchordEndpoint, config.tidalRelay, registry.addons, debug.state) { own, signed, _, d ->
            when (d.wire) {
                TidalDebugSettings.Wire.V1 -> tidalAddon() != null
                TidalDebugSettings.Wire.LEGACY -> own != null || debug.relay(signed) != null
                TidalDebugSettings.Wire.AUTO -> own != null || tidalAddon() != null || debug.relay(signed) != null
            }
        }.stateIn(CoroutineScope(SupervisorJob() + Dispatchers.IO), SharingStarted.Eagerly, false)
}
