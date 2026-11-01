package com.stash.core.media.streaming

import com.stash.core.data.prefs.StreamingPreference
import com.stash.data.download.lossless.LosslessQualityTier
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.prefs.StreamingQualityPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single decision point for "what quality should a streaming resolve ask for right now?".
 * Streaming resolvers call this instead of reading the download tier; downloads never call it.
 *
 * **Data saver** (the `streaming_save_data` preference, "Data saver" in Settings and Now Playing)
 * applies on a METERED network only ([dataSaverApplies]): on unmetered Wi-Fi every answer here is
 * what it would be with the switch off. Where it applies:
 *  - Lossless on: no FLAC. The lossless relays and cloud copies leave the chain
 *    ([StreamSourceRegistry]) and the song streams from JioSaavn at 160 kbps or YouTube's best
 *    ([LossyQuality.DATA_SAVER]), about a sixth of CD FLAC. Not Tidal's AAC 320: every Tidal
 *    answer, AAC included, counts toward the daily Tidal & Qobuz allowance (`BitchordClient.lookup`),
 *    so routing every phone on mobile data to it would spend the allowance it is meant to save.
 *    A user who opted into "Prefer Tidal AAC while streaming" still gets that rung first.
 *  - Lossless off: the lowest each lossy source has ([LossyQuality.LOWEST]), as Save Data always did.
 *
 * Also the one place that notices the effective quality CHANGING between resolves
 * (Data saver, Prefer Tidal AAC or the Lossless switch flipped, a tier picked, Wi-Fi → cellular,
 * unmetered → metered) and drops the [StreamUrlCache], whose entries are keyed by track id alone
 * with ~1 h TTLs — otherwise the old quality's URLs kept being served for up to an hour after the
 * user (or the network) asked for something else.
 */
@Singleton
class StreamQualityPolicy @Inject constructor(
    private val connectivity: ConnectivityMonitor,
    private val prefs: StreamingQualityPreferences,
    private val urlCache: StreamUrlCache,
    private val losslessPrefs: LosslessSourcePreferences,
    /** For the debug-only "skip Qobuz" test toggle, so flipping it drops cached URLs too. */
    private val streamingPreference: StreamingPreference,
) {
    /** Everything a resolve's quality depends on. A change between resolves means every cached URL is for the wrong quality. */
    private data class Decision(
        val lossless: Boolean,
        /** The Data saver switch is on AND the active network is metered. */
        val dataSaver: Boolean,
        val tier: LosslessQualityTier,
        val lossy: LossyQuality,
        val preferTidalAac: Boolean = false,
        val preferCloudCopies: Boolean = false,
        /** Debug-only "Test: skip Qobuz" — always false on a release build. */
        val skipQobuz: Boolean = false,
    )

    @Volatile private var lastDecision: Decision? = null

    /**
     * The FLAC tier a lossless rung asks for: the per-network tier the user picked (cellular's on
     * mobile data, Wi-Fi's otherwise). Where Data saver applies the lossless rungs don't run at all;
     * CD is returned then only as the floor for anything that still asks (the debug force-qbdlx toggle).
     */
    suspend fun streamingTier(): LosslessQualityTier = snapshot().tier

    /**
     * Data saver is in effect for this resolve: the switch is on and the active network is metered.
     * [StreamSourceRegistry] leaves the lossless rungs out then, and the next-song look-ahead
     * ([com.stash.core.media.PlayerRepositoryImpl.prefetchNextTrack], [PrefetchOrchestrator]) is skipped.
     */
    suspend fun dataSaverApplies(): Boolean = snapshot().dataSaver

    /**
     * What the lossy rungs (JioSaavn, YouTube) ask for. Read here, not from the preferences, so the
     * cache-clearing below sees a flip of the switch, the Lossless switch or the network too.
     */
    suspend fun lossyQuality(): LossyQuality = snapshot().lossy

    /**
     * "Prefer Tidal AAC while streaming": [StreamSourceRegistry] asks Tidal for AAC before
     * Qobuz when true. Read here, at the start of every resolve, so flipping it drops the
     * cached URLs of the other kind (FLAC cached while it was off, AAC while it was on).
     */
    suspend fun preferTidalAac(): Boolean = snapshot().preferTidalAac

    /**
     * "Play my cloud copies first": [StreamSourceRegistry] plays a matching lossless cloud file
     * before the lossless relays. In the snapshot, so flipping it drops cached URLs of the other kind.
     */
    suspend fun preferCloudCopies(): Boolean = snapshot().preferCloudCopies

    // ponytail: cleared at the next RESOLVE after a change, not at the change itself — a
    // track whose URL was prefetched just before can still play once at the old quality.
    // Stamping entries with their quality and checking at read time closes that; not yet earned.
    private suspend fun snapshot(): Decision {
        val lossless = losslessPrefs.enabledNow()
        val dataSaver = prefs.saveDataNow() && connectivity.isMetered()
        val tier = when {
            dataSaver -> LosslessQualityTier.CD
            connectivity.isCellular() -> prefs.cellularTierNow()
            else -> prefs.wifiTierNow()
        }
        val decision = Decision(
            lossless = lossless,
            dataSaver = dataSaver,
            tier = tier,
            lossy = lossyQualityFor(dataSaver = dataSaver, lossless = lossless),
            preferTidalAac = prefs.preferTidalAacNow(),
            preferCloudCopies = prefs.preferCloudCopiesNow(),
            skipQobuz = streamingPreference.isSkipQobuz(),
        )
        val previous = lastDecision
        lastDecision = decision
        if (previous != null && previous != decision) urlCache.clear()
        return decision
    }

    internal companion object {
        /** The lossy quality for a resolve: see [LossyQuality]. */
        fun lossyQualityFor(dataSaver: Boolean, lossless: Boolean): LossyQuality = when {
            !dataSaver -> LossyQuality.FULL
            lossless -> LossyQuality.DATA_SAVER
            else -> LossyQuality.LOWEST
        }
    }
}
