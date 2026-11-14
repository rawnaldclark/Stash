package com.stash.data.download.lossless

import com.stash.data.download.lossless.qbdlx.QbdlxCredentialStore
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import com.stash.data.download.lossless.tidal.OwnTidalServer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * The ONE place that answers "is lossless available, and whose is it?" — each
 * as a Flow (for Home's and Settings' combines) with a suspend getter (for the
 * download pipeline), plus one per-call check ([fileUrlAvailableNow] — the
 * login's dead-cooldown is not observable). Defined once so the sources, the
 * download deferral reason and the banners can never disagree.
 *
 * Lossless comes from two rungs: the user's own Qobuz account (the Qobuz rung)
 * and Tidal (Stash's relay from the signed config, or the user's own server).
 *
 *  - [qbdlxEnabled]          the Qobuz rung is set up: a connected Qobuz account
 *  - [fileUrlAvailableNow]   that account's login is LIVE (not merely present,
 *                            not cooling after a rejection) — per call
 *  - [anySourceConfigured]   a Qobuz account or a Tidal server is set up → the
 *                            Settings "no lossless source" line and Now Playing's
 *                            quality sheet
 *  - [anyUserOwned]          a connected Qobuz account → the Home banner (Stash's
 *                            Tidal relay failing must not hide the "connect your
 *                            own account" banner — that is the outage it exists for)
 *  - [ownTidalFirst]         own Tidal server && no Qobuz account → the Tidal rung
 *                            leads both chains above CD quality ([ownTidalFirstNow])
 *  - [routingRows]           the same facts spelled out per path, in the order a
 *                            song asks them, for Settings › Lossless sources
 */
@Singleton
class LosslessAvailability @Inject constructor(
    private val credentialStore: QbdlxCredentialStore,
    private val config: LosslessConfigFetcher,
    /** The user's own Tidal server (add-ons phase 4 §2.10). */
    private val own: OwnTidalServer,
) {
    val qbdlxEnabled: Flow<Boolean> = credentialStore.hasLogin

    suspend fun qbdlxEnabledNow(): Boolean = qbdlxEnabled.first()

    /**
     * Point-in-time: is the user's OWN Qobuz login live? The only lossless path
     * with no shared budget behind it, so it is the only path speculative work
     * (row prefetch) may spend.
     */
    suspend fun ownAccountLiveNow(): Boolean = credentialStore.loginLive()

    /**
     * Point-in-time: can the Qobuz rung turn a match into a file URL right now? Only the
     * user's own live login can; a cooling one must cost zero catalog calls.
     */
    suspend fun fileUrlAvailableNow(): Boolean = credentialStore.loginLive()

    /**
     * Some lossless path is set up: the user's own Qobuz account, the user's own Tidal
     * server, or Stash's Tidal relay from the signed config. Configuration, not liveness.
     */
    val anySourceConfigured: Flow<Boolean> =
        combine(credentialStore.hasLogin, own.endpoint, config.tidalRelay) { login, ownTidal, stashTidal ->
            login || ownTidal != null || stashTidal != null
        }

    /**
     * Stash's Tidal relay is this listener's Tidal: it is in the signed config and they have no
     * Tidal server of their own (whose answers never touch Stash's relay). Only then do the daily
     * allowance and the supporter unlock mean anything to them.
     */
    val stashTidalRelayInUse: Flow<Boolean> =
        combine(config.tidalRelay, own.endpoint) { stashTidal, ownTidal ->
            stashTidal != null && ownTidal == null
        }

    val anyUserOwned: Flow<Boolean> = credentialStore.hasLogin
    suspend fun anyUserOwnedNow(): Boolean = anyUserOwned.first()

    /**
     * The user's own Tidal server is set and no Qobuz account is connected: above CD quality
     * ([ownTidalFirstNow]) the Tidal rung goes ahead of the Qobuz one. With their own Qobuz
     * as well, Qobuz keeps the lead: it answers from their own account, and their Tidal server
     * is asked when that misses. Configuration, not liveness, like [routingRows]: the order
     * must not flip on one transient failure.
     */
    val ownTidalFirst: Flow<Boolean> =
        combine(credentialStore.hasLogin, own.endpoint) { login, tidal ->
            ownTidalLeads(login, tidal)
        }

    /**
     * Whether the Tidal rung leads a request for [tier]: [ownTidalFirst], above CD only. Tidal
     * has no quality setting and serves its best, usually 24-bit (2.5 to 5 times CD's data),
     * while Qobuz asks for the tier. At CD (Save Data, mobile data by default, or a CD download
     * setting) Qobuz keeps the lead, as it always had, and the user's server is the fallback.
     */
    suspend fun ownTidalFirstNow(tier: LosslessQualityTier): Boolean =
        tier > LosslessQualityTier.CD && ownTidalFirst.first()

    /**
     * The Settings › Lossless sources ROUTING list — one row per lossless path,
     * built here from the same predicates the sources and downloads read, so the
     * screen cannot disagree with what actually resolves.
     *
     * In the order a song asks them (the Qobuz rung, then the Tidal rung): the screen
     * draws each row as a `↳`, so the list reads as "then". The Qobuz row is listed
     * even when no account is connected (it is where one would go); the user's own
     * Tidal server, which leads when no Qobuz account is connected ([ownTidalFirst]),
     * says it leads above CD only: the list can't follow the tier, which differs by
     * network and between streams and downloads. The Tidal row is the user's own
     * server when one is set (it replaces Stash's relay), else Stash's relay from the
     * signed config.
     *
     * Rows describe CONFIGURATION, not liveness, deliberately: a connected account
     * inside its 60s [QbdlxCredentialStore.DEAD_COOLDOWN_MS] must not flicker to
     * "offline" over one transient 401.
     */
    val routingRows: Flow<List<RoutingRow>> = combine(
        credentialStore.hasLogin,
        credentialStore.connectedEmailFlow,
        own.endpoint,
        config.tidalRelay,
    ) { hasLogin, email, ownTidal, stashTidal ->
        val tidalFirst = ownTidalLeads(hasLogin, ownTidal)
        // Never the address itself: a BitChord base can carry the server's access token as its path.
        val tidalRow = if (ownTidal != null) {
            RoutingRow("tidal", "Custom Tidal server", if (tidalFirst) "first above CD" else "configured", RoutingState.CONFIGURED)
        } else {
            RoutingRow(
                id = "tidal",
                label = "Tidal",
                detail = if (stashTidal != null) "configured" else "not configured",
                state = if (stashTidal != null) RoutingState.CONFIGURED else RoutingState.NOT_CONFIGURED,
            )
        }
        listOf(
            // Listed first even when not connected: it is where a connected account would go.
            RoutingRow(
                id = "qobuz",
                label = "Your Qobuz account",
                // Keyed on hasLogin, NEVER on the email: a migrated pasted token
                // has none, and keying on it would render "not connected" while
                // the badge on the same screen says a source IS configured.
                detail = if (hasLogin) (email ?: "connected (token)") else "not connected",
                state = if (hasLogin) RoutingState.CONNECTED else RoutingState.NOT_CONFIGURED,
            ),
            tidalRow,
        )
    }

    private companion object {
        /** [ownTidalFirst]'s ownership rule, shared with [routingRows] so the list and the chains agree. */
        fun ownTidalLeads(hasLogin: Boolean, tidalServer: String?): Boolean =
            tidalServer != null && !hasLogin
    }
}

/** How a [RoutingRow]'s path is set up — never whether it is reachable right now. */
enum class RoutingState { CONNECTED, CONFIGURED, NOT_CONFIGURED }

/** One line of the Settings ROUTING block. [id] is stable; the rest is display text. */
data class RoutingRow(val id: String, val label: String, val detail: String, val state: RoutingState)
