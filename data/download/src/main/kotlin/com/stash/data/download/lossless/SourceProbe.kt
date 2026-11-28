package com.stash.data.download.lossless

import com.stash.core.addons.AddonHold
import com.stash.core.addons.InTreeAddonRegistry
import com.stash.data.download.lossless.quota.LosslessQuota
import com.stash.data.download.lossless.relay.RelaySigner
import com.stash.data.download.lossless.tidal.TidalClient
import com.stash.data.download.lossless.tidal.TidalWires
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * On-demand check of each ROUTING row (Settings › Lossless sources › Check again).
 * Rows themselves still describe configuration only; this is a snapshot layered on top.
 *
 * Never log a base URL, a token, or an exception message: an own server's base can carry its token.
 */
@Singleton
class SourceProbe @Inject constructor(
    private val availability: LosslessAvailability,
    private val wires: TidalWires,
    private val tidalClient: TidalClient,
    private val signer: RelaySigner,
    private val quota: LosslessQuota,
    private val addons: InTreeAddonRegistry,
    sharedClient: OkHttpClient,
) {
    private val http = sharedClient.newBuilder()
        .callTimeout(5, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    suspend fun probeAll(rows: List<RoutingRow>): Map<String, ProbeState> = coroutineScope {
        // One manifest pass up front: covers the add-on rows and the first-party Tidal add-on.
        runCatching { addons.refresh(force = true) }
        rows.map { row ->
            async {
                row.id to (runCatching { withTimeoutOrNull(6_000) { probe(row) } }
                    .getOrNull() ?: ProbeState.NotAnswering)
            }
        }.awaitAll().toMap()
    }

    private suspend fun probe(row: RoutingRow): ProbeState {
        if (row.state == RoutingState.NOT_CONFIGURED) return ProbeState.Off
        return when {
            // Credential check, not a ping: signed in and not in its dead-cooldown.
            row.id == PlayOrder.QOBUZ ->
                if (availability.ownAccountLiveNow()) ProbeState.Ready else ProbeState.NotAnswering
            row.id == PlayOrder.TIDAL -> tidal()
            else -> addon(row.id)
        }
    }

    private suspend fun tidal(): ProbeState {
        if (wires.switchedOff()) return ProbeState.Off
        val route = wires.routeIfOn() ?: return ProbeState.Off
        // Same rule as TidalClient.lookup: the daily allowance is Stash's, never the user's own server's.
        val stashServed = !(route is TidalWires.Route.Relay && route.relay.userOwned)
        val reached = when (route) {
            is TidalWires.Route.Addon ->
                reach(route.addon.base, route.addon.token, strict = false, ownerRelay = false)
            is TidalWires.Route.Relay ->
                reach(
                    route.relay.base, route.relay.token,
                    strict = route.relay.userOwned,
                    ownerRelay = !route.relay.userOwned,
                )
        }
        if (reached != ProbeState.Ready) return reached // a down host matters more than a spent day
        quota.awaitLoaded()
        return when {
            stashServed && quota.isSpent() -> ProbeState.LimitReached(quota.resetsAtMs())
            tidalClient.isBusy() -> ProbeState.Paused
            else -> ProbeState.Ready
        }
    }

    private suspend fun reach(
        base: String,
        token: String?,
        strict: Boolean,
        ownerRelay: Boolean,
    ): ProbeState = withContext(Dispatchers.IO) {
        val url = base.toHttpUrlOrNull() ?: return@withContext ProbeState.NotAnswering
        val req = Request.Builder().url(url).get()
            .apply {
                if (token != null) header("X-Access-Token", token)
                if (ownerRelay) signer.appBuild(this)
            }
            .build()
        try {
            http.newCall(req).execute().use { r ->
                when {
                    r.code == RelaySigner.APP_TOO_OLD -> ProbeState.NotAnswering
                    strict && (r.code == 401 || r.code == 403) -> ProbeState.NotAnswering
                    r.code < 500 -> ProbeState.Ready
                    else -> ProbeState.NotAnswering
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ProbeState.NotAnswering
        }
    }

    /** Reads the registry's verdict from the pass [probeAll] just ran. */
    private fun addon(key: String): ProbeState {
        val id = PlayOrder.addonIdOf(key) ?: return ProbeState.NotAnswering
        val entry = addons.entries.value.firstOrNull { it.manifest?.manifest?.id == id }
            ?: return ProbeState.NotAnswering
        return when (entry.hold) {
            null -> ProbeState.Ready
            AddonHold.OFF -> ProbeState.Off
            else -> ProbeState.NotAnswering
        }
    }
}