package com.stash.data.download.lossless

import android.util.Log
import com.stash.core.data.prefs.StreamingPreference
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
 * Sources that don't appear in [LosslessSourcePreferences.priorityOrder]
 * are appended at the end in registration-order — so a fresh install with
 * no priority configured still tries every available source.
 */
@Singleton
class LosslessSourceRegistry @Inject constructor(
    private val sources: Set<@JvmSuppressWildcards LosslessSource>,
    private val prefs: LosslessSourcePreferences,
    private val healthGate: LosslessSourceHealthGate,
    private val streamingPreference: StreamingPreference,
) {

    /**
     * Walk sources in priority order, return the first match that meets
     * the user's quality threshold. Returns null when no source has a
     * confident match — caller should fall through to the YouTube/yt-dlp
     * pipeline as a last resort (the strict-superset behavior we want for
     * Path ii of the source-priority model).
     */
    suspend fun resolve(query: TrackQuery, bypassRateLimit: Boolean = false): SourceResult? {
        // Test toggles (outage drills). ARCOD-only takes precedence over
        // amz-only: filter the chain to a single source so a forced download
        // exercises that source even when the Qobuz proxies are healthy. A
        // miss falls through to a normal null return (no quota to protect).
        val ordered = if (streamingPreference.isForceQbdlxOnly()) {
            orderedSources().filter { it.id == "qbdlx_qobuz" }
        } else if (streamingPreference.isForceArcodOnly()) {
            orderedSources().filter { it.id == "arcod" }
        } else if (streamingPreference.isForceAmzOnly()) {
            orderedSources().filter { it.id == "amz" }
        } else {
            // Normal chain skips (a) parked (host-down) sources and (b) any
            // source whose build-time credentials aren't fully configured —
            // an unconfigured qbdlx or ARCOD can't produce a result, so
            // skipping it here avoids a wasted HTTP round-trip / rate-limit
            // spend per resolve() call. Force-X toggles above bypass both
            // filters so a manual test can still reach a parked/unconfigured
            // source on demand, and orderedSources()/Settings still list them.
            orderedSources()
                .filterNot { it.id in PARKED_SOURCE_IDS }
                .filterNot { it.id == "qbdlx_qobuz" && !com.stash.data.download.BuildConfig.QBDLX_CONFIGURED }
                .filterNot { it.id == "arcod" && !com.stash.data.download.BuildConfig.ARCOD_CONFIGURED }
        }
        val minQuality = prefs.minQualityNow()

        for (source in ordered) {
            if (healthGate.isDegraded(source.id)) {
                Log.d(TAG, "skipping ${source.id}: degraded (content-health cooldown)")
                continue
            }
            if (!source.isEnabled()) continue
            val result = runCatching { source.resolve(query, bypassRateLimit) }
                .onFailure { e ->
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
     * All registered sources, in user-configured priority order. Sources
     * not mentioned in the prefs go last in registration order.
     * Useful for the Settings → Lossless Sources screen rendering.
     */
    suspend fun orderedSources(): List<LosslessSource> {
        val priority = prefs.priorityOrderNow()
        val byId = sources.associateBy { it.id }
        val ordered = mutableListOf<LosslessSource>()
        val seen = mutableSetOf<String>()

        for (id in priority) {
            byId[id]?.let {
                ordered.add(it)
                seen.add(it.id)
            }
        }
        // Append any registered source not in the priority list (e.g. a
        // newly added source on app upgrade that the user hasn't ranked yet).
        for (source in sources) {
            if (source.id !in seen) ordered.add(source)
        }
        return ordered
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

        /**
         * Lossless sources parked out of the NORMAL resolve chain because their
         * upstreams are down for us (2026-07-01): qobuz.squid.wtf needs a
         * captcha we can't solve headless, kennyy.com.br is health-down, and
         * arcod.xyz returns Cloudflare 403. Their code + Hilt bindings stay
         * intact — re-enabling a source is just removing its id here (and
         * uncommenting the matching line in
         * [com.stash.core.media.streaming.StreamSourceRegistry] for streaming).
         * Force-X test toggles and the Settings source list still reach them.
         */
        val PARKED_SOURCE_IDS = setOf("squid_qobuz", "kennyy_qobuz", "arcod")
    }
}
