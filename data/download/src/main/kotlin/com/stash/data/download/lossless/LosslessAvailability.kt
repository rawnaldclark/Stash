package com.stash.data.download.lossless

import com.stash.data.download.lossless.qbdlx.QbdlxCredentialStore
import com.stash.data.download.lossless.quota.LosslessQuota
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import com.stash.data.download.lossless.relay.LosslessRelayClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * The ONE place that answers "is lossless available, and whose is it?" — two
 * as a Flow (for Home's combine) each with a suspend getter (for the download
 * pipeline), plus one per-call check ([fileUrlAvailableNow] — relay cooldowns are
 * in-memory and not observable). Defined once so the source, the download
 * deferral reason and the Home banner can never disagree.
 *
 *  - [qbdlxEnabled]       BYO login || relay configured || custom endpoint set
 *  - [fileUrlAvailableNow] login LIVE (not merely present) || custom endpoint not
 *                         cooled || any config relay not cooled while the day's
 *                         lossless allowance lasts ([LosslessQuota]) — per call
 *  - [anyUserOwned]       BYO login || custom endpoint → the Home banner
 *                         (a dead PUBLIC relay must not hide the "connect your
 *                         own account" banner — that is the outage it exists for)
 *  - [ownTidalFirst]      own Tidal server && !anyUserOwned → the Tidal rung leads
 *                         both chains (downloads and streaming)
 *  - [routingRows]        the same facts spelled out per path, in the order a song
 *                         asks them, for Settings › Audio
 */
@Singleton
class LosslessAvailability @Inject constructor(
    private val credentialStore: QbdlxCredentialStore,
    private val config: LosslessConfigFetcher,
    private val prefs: LosslessSourcePreferences,
    private val relayClient: LosslessRelayClient,
    private val quota: LosslessQuota,
) {
    // ponytail: config.relays is empty until loadCached() completes, so a tap in the
    // first tens of ms after a cold start can see false and fall to the next rung once.
    val qbdlxEnabled: Flow<Boolean> =
        combine(credentialStore.hasLogin, config.relays, prefs.customLosslessEndpoint) { login, relays, custom ->
            login || relays.isNotEmpty() || custom != null
        }

    suspend fun qbdlxEnabledNow(): Boolean = qbdlxEnabled.first()

    /**
     * Point-in-time: is the user's OWN Qobuz login live? The only lossless path
     * with no shared budget behind it — the relay has per-account caps — so it is the only path speculative work (row prefetch) may
     * spend. A configured relay deliberately does not count.
     */
    suspend fun ownAccountLiveNow(): Boolean = credentialStore.loginLive()

    /** Point-in-time: is there a file-URL path that is not cooled right now? */
    suspend fun fileUrlAvailableNow(): Boolean {
        if (credentialStore.loginLive()) return true
        prefs.customLosslessEndpointNow()?.let { if (!relayClient.isCooled(it)) return true }
        // Spent for today: the config relays would not mint, so they are no path (zero catalog
        // calls, and a sweep does not read the null as "no lossless version").
        if (quota.isSpent()) return false
        return config.relays.value.any { !relayClient.isCooled(it.base) }
    }

    val anyUserOwned: Flow<Boolean> =
        combine(credentialStore.hasLogin, prefs.customLosslessEndpoint) { l, c -> l || c != null }
    suspend fun anyUserOwnedNow(): Boolean = anyUserOwned.first()

    /**
     * The user's own Tidal server is set and none of their own Qobuz is: the Tidal rung
     * goes ahead of the Qobuz one, whose file URL would otherwise come from Stash's shared
     * relay before the user's own server was ever asked. That relay stays the fallback,
     * right after it. With their own Qobuz as well, Qobuz keeps the lead: it answers from
     * their own account, and Stash's relay is reached only when that call fails.
     * Configuration, not liveness, like [routingRows]: the order must not flip on one
     * transient failure.
     */
    val ownTidalFirst: Flow<Boolean> =
        combine(credentialStore.hasLogin, prefs.customLosslessEndpoint, prefs.bitchordEndpoint) { login, custom, tidal ->
            ownTidalLeads(login, custom, tidal)
        }
    suspend fun ownTidalFirstNow(): Boolean = ownTidalFirst.first()

    /**
     * The Settings › Audio ROUTING list — one row per lossless path, built here
     * from the same predicates the source and downloads read, so the screen cannot
     * disagree with what actually resolves. (It used to be a hardcoded
     * "Qobuz — active" row, true only while a token pool shipped.)
     *
     * In the order a song asks them ([com.stash.data.download.lossless.qbdlx.QbdlxFileUrlRouter],
     * then the Tidal rung, or the Tidal rung first per [ownTidalFirst]): the screen draws
     * each row as a `↳`, so the list reads as "then". It once listed the user's custom
     * endpoint after Stash's relay, which it outranks.
     *
     * Rows describe CONFIGURATION, not liveness, deliberately: relay health needs
     * the relay's `/v1/status`, which nothing serves yet (Plan B), and a connected
     * account inside its 60s [QbdlxCredentialStore.DEAD_COOLDOWN_MS] must not
     * flicker to "offline" over one transient 401.
     */
    val routingRows: Flow<List<RoutingRow>> = combine(
        credentialStore.hasLogin,
        credentialStore.connectedEmailFlow,
        config.relays,
        prefs.customLosslessEndpoint,
        prefs.bitchordEndpoint,
    ) { hasLogin, email, relays, custom, tidal ->
        // Omitted entirely when unset, like the custom endpoint's row below. Never the
        // address itself: a BitChord base can carry the server's access token as its path.
        val tidalRow = tidal?.let { RoutingRow("tidal", "Custom Tidal server", "configured", RoutingState.CONFIGURED) }
        val tidalFirst = ownTidalLeads(hasLogin, custom, tidal)
        buildList {
            // Listed first even when not connected: it is where a connected account would go.
            add(
                RoutingRow(
                    id = "qobuz",
                    label = "Your Qobuz account",
                    // Keyed on hasLogin, NEVER on the email: a migrated pasted token
                    // has none, and keying on it would render "not connected" while
                    // the badge on the same screen says a source IS configured.
                    detail = if (hasLogin) (email ?: "connected (token)") else "not connected",
                    state = if (hasLogin) RoutingState.CONNECTED else RoutingState.NOT_CONFIGURED,
                ),
            )
            if (tidalFirst && tidalRow != null) add(tidalRow)
            // Omitted entirely when unset — an empty "Custom endpoint — not configured"
            // row is noise on the screen of everyone who will never set one.
            if (custom != null) {
                add(RoutingRow("custom", "Custom endpoint", "configured", RoutingState.CONFIGURED))
            }
            add(
                RoutingRow(
                    id = "relay",
                    label = "Stash lossless",
                    detail = if (relays.isNotEmpty()) "configured" else "not configured",
                    state = if (relays.isNotEmpty()) RoutingState.CONFIGURED else RoutingState.NOT_CONFIGURED,
                ),
            )
            if (!tidalFirst && tidalRow != null) add(tidalRow)
        }
    }

    private companion object {
        /** [ownTidalFirst]'s rule, shared with [routingRows] so the list and the chains agree. */
        fun ownTidalLeads(hasLogin: Boolean, customEndpoint: String?, tidalServer: String?): Boolean =
            tidalServer != null && !hasLogin && customEndpoint == null
    }
}

/** How a [RoutingRow]'s path is set up — never whether it is reachable right now. */
enum class RoutingState { CONNECTED, CONFIGURED, NOT_CONFIGURED }

/** One line of the Settings ROUTING block. [id] is stable; the rest is display text. */
data class RoutingRow(val id: String, val label: String, val detail: String, val state: RoutingState)
