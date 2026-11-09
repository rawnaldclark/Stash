package com.stash.data.download.lossless

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-source content-health gate. When a source is observed serving a
 * degraded file (a preview sample or a lossy downgrade, e.g. the duration
 * backstop in DownloadManager) it is marked degraded for [COOLDOWN_MS], so subsequent resolves skip it
 * cheaply instead of paying a wasted round-trip per track.
 *
 * **Independent per source** — sources that share an upstream often degrade
 * together but recover independently (observed 2026-06-04 on the since-removed
 * Qobuz proxies). Coupling would wrongly keep a recovered source disabled, so
 * each id has its own entry.
 *
 * **Passive recovery** — when the cooldown lapses the source is simply
 * retried by the next real resolve; if it's still degraded the inspector
 * re-marks it. This is the content-health layer; breakers and rate limits
 * live in [AggregatorRateLimiter].
 *
 * The injectable [nowMs] clock keeps this fully unit-testable; Hilt uses the
 * no-arg secondary constructor (System time).
 */
@Singleton
class LosslessSourceHealthGate(
    private val nowMs: () -> Long,
) {
    @Inject constructor() : this(nowMs = { System.currentTimeMillis() })

    /** sourceId → epoch-ms until which the source is considered degraded. */
    private val degradedUntil = ConcurrentHashMap<String, Long>()

    /** Mark [sourceId] degraded for [COOLDOWN_MS] from now. */
    fun recordDegraded(sourceId: String) {
        degradedUntil[sourceId] = nowMs() + COOLDOWN_MS
    }

    /** True while [sourceId] is within its cooldown window. */
    fun isDegraded(sourceId: String): Boolean {
        val until = degradedUntil[sourceId] ?: return false
        if (nowMs() >= until) {
            // Lapsed — clear so the map doesn't grow unbounded and the next
            // real resolve acts as the passive canary.
            degradedUntil.remove(sourceId, until)
            return false
        }
        return true
    }

    companion object {
        /** Cooldown before a degraded source is retried. ~5 minutes. */
        const val COOLDOWN_MS = 5 * 60 * 1000L
    }
}
