package com.stash.data.download.lossless

import android.util.Log
import com.stash.core.data.prefs.StreamingPreference
import com.stash.data.download.lossless.addon.AddonDownloadSources
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds every Hilt-bound [LosslessSource] and resolves a [TrackQuery]
 * against them in user-configured priority order, returning the first
 * acceptable match.
 *
 * "Acceptable" means three things in series:
 *   1. The source is enabled (creds set, not circuit-broken, not toggled off)
 *   2. The source returned a non-null [SourceResult]
 *   3. The result's format meets the user's [LosslessSourcePreferences.MinQuality]
 *      threshold — so the chain doesn't swap an AAC 128 for an AAC 128 from
 *      a different host.
 *
 * The order is the PLAY ORDER ([PlayOrder], add-ons plan phase 4 §2.8): the one order streaming
 * follows too and Settings › Add-ons shows. Until the user drags it: the Qobuz source, the Tidal
 * source, the user's add-ons in the order they were added, then the default add-ons. A source the
 * order doesn't name goes last, in registration order.
 *
 * While the order is automatic, a user's own Tidal server, with none of their own Qobuz set up,
 * is asked before the Qobuz source above CD quality ([LosslessAvailability.ownTidalFirst]) — see
 * [withOwnTidalFirst]. A saved order is followed as it is.
 *
 * Add-ons that hand over lossless files ([AddonDownloadSources]) come from the registry at call
 * time. With no add-on on the list is exactly the Hilt-bound one.
 */
@Singleton
class LosslessSourceRegistry @Inject constructor(
    private val sources: Set<@JvmSuppressWildcards LosslessSource>,
    private val prefs: LosslessSourcePreferences,
    private val healthGate: LosslessSourceHealthGate,
    private val streamingPreference: StreamingPreference,
    private val availability: LosslessAvailability,
    private val addons: AddonDownloadSources,
    private val playOrder: PlayOrder,
) {

    /** Where the chain line goes; tests read it. */
    internal var chainLog: (String) -> Unit = { Log.i(TAG, it) }

    /**
     * Walk sources in priority order, return the first match that meets
     * the user's quality threshold. Returns null when no source has a
     * confident match — caller should fall through to the YouTube/yt-dlp
     * pipeline as a last resort (the strict-superset behavior we want for
     * Path ii of the source-priority model).
     */
    suspend fun resolve(query: TrackQuery, bypassRateLimit: Boolean = false): SourceResult? {
        val ordered = chain()
        // The order this download asks its sources in: the play order (Settings › Add-ons).
        chainLog("chain [${ordered.joinToString(",") { it.id }}]")
        val minQuality = prefs.minQualityNow()

        for (source in ordered) {
            if (healthGate.isDegraded(source.id)) {
                Log.d(TAG, "skipping ${source.id}: degraded (content-health cooldown)")
                continue
            }
            if (!source.isEnabled()) {
                // A breaker-open source never searched: a sweep must not read the
                // chain's null as "no lossless version" for this track.
                if (source.rateLimitState().isCircuitBroken) {
                    kotlinx.coroutines.currentCoroutineContext()[
                        com.stash.data.download.lossless.relay.LosslessDownloadPurpose
                    ]?.searchIncomplete = true
                }
                continue
            }
            val result = runCatching { source.resolve(query, bypassRateLimit) }
                .onFailure { e ->
                    // A cancelled caller is not a failing source: rethrow, or the
                    // chain carries on (and the download falls to lossy) after a stop.
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // resolve() should never throw — it should catch and
                    // return null. Defensive log so an unexpected throw
                    // from one source doesn't break the chain for others.
                    Log.w(TAG, "source ${source.id} threw on resolve", e)
                }
                .getOrNull()
                ?: continue

            if (!minQuality.accepts(result.format)) {
                Log.d(
                    TAG,
                    "skipping ${source.id}: format ${result.format.codec} " +
                        "${result.format.bitrateKbps}kbps below threshold $minQuality",
                )
                continue
            }
            return result
        }
        return null
    }

    /**
     * Whether a lookup right now could really search a lossless catalog: some source
     * [resolve] would ask is enabled (not toggled off, not circuit-broken), not degraded,
     * and its last call did not fail. Sources answer null for a miss, a rate-limit block
     * and a network failure alike, so a background sweep reads this right after a null
     * before filing the track as "no lossless version".
     */
    suspend fun canSearchNow(): Boolean = chain().any { source ->
        !healthGate.isDegraded(source.id) &&
            source.isEnabled() &&
            source.rateLimitState().let { !it.isCircuitBroken && it.recentFailures == 0 }
    }

    /** The sources [resolve] asks, in order. */
    private suspend fun chain(): List<LosslessSource> =
        // Test toggles (outage drills): filter the chain to a single source so a
        // forced download exercises that source even when Tidal would answer. A miss
        // falls through to a normal null return (no quota to protect).
        if (streamingPreference.isForceQbdlxOnly()) {
            orderedSources().filter { it.id == PlayOrder.QBDLX_SOURCE_ID }
        } else {
            // qbdlx self-gates on LosslessAvailability.
            ordered().let { normal ->
                // "Test: skip Qobuz (Tidal first)" — debug builds only (the accessor is
                // gated). Leaves out the Qobuz source so a download goes to Tidal.
                if (streamingPreference.isSkipQobuz()) {
                    normal.filterNot { it.id == PlayOrder.QBDLX_SOURCE_ID }.also { chain ->
                        Log.i(TAG, "TEST skip-qobuz on → chain [${chain.joinToString(",") { it.id }}]")
                    }
                } else {
                    normal
                }
            }
        }

    /**
     * The sources in the order a download asks them: the play order, and while that is automatic,
     * the own-server rule ([withOwnTidalFirst]).
     */
    private suspend fun ordered(): List<LosslessSource> {
        val order = playOrder.now()
        // A source switched off in Settings › Add-ons is not in the chain: never asked, and never
        // named in the chain line.
        val off = playOrder.switchedOffNow()
        val sorted = sortedBy(order).let { all -> if (off.isEmpty()) all else all.filterNot { PlayOrder.keyOfSource(it.id) in off } }
        return if (order.automatic) withOwnTidalFirst(sorted) else sorted
    }

    /**
     * [ordered] with the Tidal source moved to just ahead of the Qobuz one when the Tidal
     * server is the user's own, none of their own Qobuz is set up and the download tier is
     * above CD ([LosslessAvailability.ownTidalFirstNow]). The Qobuz source, which needs the
     * user's own account, is off then anyway; nothing else moves. Only while the play order is
     * automatic: once the user has dragged it, their order is the order.
     */
    private suspend fun withOwnTidalFirst(ordered: List<LosslessSource>): List<LosslessSource> {
        val qobuzAt = ordered.indexOfFirst { it.id == PlayOrder.QBDLX_SOURCE_ID }
        val tidalAt = ordered.indexOfFirst { it.id == PlayOrder.TIDAL_SOURCE_ID }
        if (qobuzAt < 0 || tidalAt < qobuzAt) return ordered // already ahead, or either one absent
        // The tier the Qobuz source asks for on downloads (QbdlxQobuzSource): Tidal has no tier.
        if (!availability.ownTidalFirstNow(prefs.qualityTierNow())) return ordered
        return ordered.toMutableList().apply { add(qobuzAt, removeAt(tidalAt)) }
    }

    /**
     * All registered sources in the play order ([PlayOrder]): `qbdlx_qobuz` is its `qobuz`,
     * `tidal_bitchord` its `tidal`, and an add-on's source its `addon:<id>`. Sources it doesn't
     * name go last in registration order. Useful for the Settings → Lossless Sources screen
     * rendering.
     */
    suspend fun orderedSources(): List<LosslessSource> = sortedBy(playOrder.now())

    private fun sortedBy(order: PlayOrder.Order): List<LosslessSource> {
        // Add-on sources come from the registry at call time (they come and go while the app
        // runs). None on: `all` IS the Hilt-bound set, and nothing below changes.
        val dynamic = addons.sources()
        val all: Collection<LosslessSource> = if (dynamic.isEmpty()) sources else sources + dynamic
        return order.sorted(all.toList()) { PlayOrder.keyOfSource(it.id) }
    }

    /** Convenience for diagnostics — used by Settings UI. */
    suspend fun allWithState(): List<SourceWithState> {
        return orderedSources().map { source ->
            SourceWithState(
                source = source,
                enabled = source.isEnabled(),
                rateLimit = source.rateLimitState(),
            )
        }
    }

    data class SourceWithState(
        val source: LosslessSource,
        val enabled: Boolean,
        val rateLimit: RateLimitState,
    )

    companion object {
        private const val TAG = "LosslessRegistry"
    }
}
