package com.stash.data.download.lossless

import android.util.Log
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
) {

    /**
     * Walk sources in priority order, return the first match that meets
     * the user's quality threshold. Returns null when no source has a
     * confident match — caller should fall through to the YouTube/yt-dlp
     * pipeline as a last resort (the strict-superset behavior we want for
     * Path ii of the source-priority model).
     */
    suspend fun resolve(query: TrackQuery): SourceResult? {
        val ordered = orderedSources()
        val minQuality = prefs.minQualityNow()

        for (source in ordered) {
            if (!source.isEnabled()) continue
            val result = runCatching { source.resolve(query) }
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
    }
}
