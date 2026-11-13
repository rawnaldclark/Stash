package com.stash.data.download.lossless

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-source content-health gate. When a source is observed serving a
 * degraded file (a preview sample or a lossy downgrade, e.g. the duration
 * backstop in DownloadManager) it is marked degraded for [COOLDOWN_MS], so subsequent resolves skip it
 * cheaply instead of paying a wasted round-trip per track.
 *
 * Streams read it too: an add-on whose stream the player refused (`AddonStreamCheck` in
 * `:core:media`: a lossy file under "lossless", a video, a file far shorter than the song) is set
 * aside here, under its `addon:<id>` key, which is also its download source's id. So it is skipped
 * by the stream chain and the download chain alike for the same cooldown.
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

    /** A source set aside: until when, and whether for a file that was not what it was said to be. */
    data class SetAside(val untilMs: Long, val wrongFile: Boolean)

    /** sourceId → until when (epoch ms) the source is considered degraded, and why. */
    private val degradedUntil = ConcurrentHashMap<String, SetAside>()

    /** Bumped on every record: wakes [setAside]'s readers. */
    private val records = MutableStateFlow(0L)

    /** Mark [sourceId] degraded for [COOLDOWN_MS] from now. */
    fun recordDegraded(sourceId: String) = record(sourceId, wrongFile = false)

    /**
     * Mark [sourceId] degraded for [COOLDOWN_MS] from now because a file it handed over was not what
     * it said: a lossy file under "lossless", a video, a stub far shorter than the song. The same
     * cooldown as [recordDegraded]; Settings › Add-ons says this one in words ([setAside]).
     */
    fun recordWrongFile(sourceId: String) = record(sourceId, wrongFile = true)

    private fun record(sourceId: String, wrongFile: Boolean) {
        val now = nowMs()
        // A slow download while a wrong file still holds it aside keeps the reason Settings shows for it (review-888 m6).
        degradedUntil.compute(sourceId) { _, was ->
            SetAside(now + COOLDOWN_MS, wrongFile || (was != null && now < was.untilMs && was.wrongFile))
        }
        records.update { it + 1 }
    }

    /** True while [sourceId] is within its cooldown window. */
    fun isDegraded(sourceId: String): Boolean {
        val entry = degradedUntil[sourceId] ?: return false
        if (nowMs() >= entry.untilMs) {
            // Lapsed — clear so the map doesn't grow unbounded and the next
            // real resolve acts as the passive canary.
            degradedUntil.remove(sourceId, entry)
            return false
        }
        return true
    }

    /**
     * The sources set aside right now, by id: a new map whenever one is set aside, and again when
     * one's cooldown ends, so a screen that says "Not used until 14:30" stops saying it on time.
     * Cold; each collector waits on its own.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val setAside: Flow<Map<String, SetAside>> = records.transformLatest {
        while (true) {
            val now = nowMs()
            val live = degradedUntil.filterValues { it.untilMs > now }
            emit(live)
            // The next to lapse: wait for it, then say again. A new record cancels the wait.
            val next = live.values.minOfOrNull { it.untilMs } ?: break
            delay(next - now)
        }
    }.distinctUntilChanged()

    companion object {
        /** Cooldown before a degraded source is retried. ~5 minutes. */
        const val COOLDOWN_MS = 5 * 60 * 1000L
    }
}
