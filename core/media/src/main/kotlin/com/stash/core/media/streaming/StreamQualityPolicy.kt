package com.stash.core.media.streaming

import com.stash.data.download.lossless.LosslessQualityTier
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.prefs.StreamingQualityPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single decision point for "what lossless tier should a streaming
 * resolve request right now?". Streaming resolvers call this instead of
 * reading the download tier; downloads never call it.
 *
 * Phase 1 returns just a tier. Phase 2 will widen the return to a
 * StreamDecision (Tier | ForceYouTube) for the cellular budget without
 * changing this class's callers' shape beyond the new branch.
 *
 * Also the one place that notices the effective quality CHANGING between resolves
 * (Save Data or the Lossless switch flipped, a tier picked, Wi-Fi → cellular) and drops the
 * [StreamUrlCache], whose entries are keyed by track id alone with ~1 h TTLs —
 * otherwise the old tier's URLs kept being served for up to an hour after the
 * user asked for something else.
 */
@Singleton
class StreamQualityPolicy @Inject constructor(
    private val connectivity: ConnectivityMonitor,
    private val prefs: StreamingQualityPreferences,
    private val urlCache: StreamUrlCache,
    private val losslessPrefs: LosslessSourcePreferences,
) {
    /** Everything a resolve's quality depends on. A change between resolves means every cached URL is for the wrong quality. */
    private data class Decision(val lossless: Boolean, val saveData: Boolean, val tier: LosslessQualityTier)

    @Volatile private var lastDecision: Decision? = null

    /**
     * Save Data never leaves lossless: it is the LOWEST lossless tier, CD (about 28 MB
     * per four-minute track against 70 for Hi-Res and 140 for Max), on every network.
     * A user who wants less than FLAC turns lossless off — and on that lossy path Save
     * Data asks YouTube for its lowest audio quality instead (see [saveData]).
     *
     * It briefly (v0.9.101–102) requested Qobuz MP3 320 instead; no path ever served
     * that, and it is not what the setting means: lossless stays lossless.
     */
    suspend fun streamingTier(): LosslessQualityTier = snapshot().tier

    /**
     * Save Data on the lossy path: the YouTube resolver asks for the lowest audio
     * quality when this is true. Read here, not from the preference directly, so the
     * cache-clearing below sees this flip too.
     */
    suspend fun saveData(): Boolean = snapshot().saveData

    // ponytail: cleared at the next RESOLVE after a change, not at the change itself — a
    // track whose URL was prefetched just before can still play once at the old quality.
    // Stamping entries with their quality and checking at read time closes that; not yet earned.
    private suspend fun snapshot(): Decision {
        val saveData = prefs.saveDataNow()
        val tier = when {
            saveData -> LosslessQualityTier.CD
            connectivity.isCellular() -> prefs.cellularTierNow()
            else -> prefs.wifiTierNow()
        }
        // The Lossless switch is read here too: with it off the lossless resolvers never ask
        // for a tier, so the lossy path's [saveData] read is what notices the flip.
        val decision = Decision(lossless = losslessPrefs.enabledNow(), saveData = saveData, tier = tier)
        val previous = lastDecision
        lastDecision = decision
        if (previous != null && previous != decision) urlCache.clear()
        return decision
    }
}
