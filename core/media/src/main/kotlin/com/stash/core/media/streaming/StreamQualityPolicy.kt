package com.stash.core.media.streaming

import com.stash.core.data.prefs.StreamingPreference
import com.stash.data.download.lossless.LosslessQualityTier
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.prefs.StreamingQualityPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
 * Also the one place that notices the effective quality CHANGING (Data saver, Prefer Tidal AAC or
 * the Lossless switch flipped, a tier picked, Wi-Fi → cellular, unmetered → metered) and drops the
 * [StreamUrlCache], whose entries are keyed by track id alone with ~1 h TTLs — otherwise the old
 * quality's URLs kept being served for up to an hour after the user (or the network) asked for
 * something else. Checked at every resolve, before the tap and look-ahead paths read the cache, and
 * the moment the network's meteredness or the Data saver switch changes ([dataSaverChanges]).
 * The cache itself never serves a lossless link while Data saver applies ([StreamUrlCache.get]).
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
     * Asked before a path reads [StreamUrlCache] without resolving (a tap, the look-ahead): the
     * decision is re-taken, so a link saved under the old one is dropped before it is read.
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

    /**
     * Whether Data saver is in effect, each time the network's meteredness (a default-network
     * callback, [ConnectivityMonitor.meteredChanges]) or the switch changes; the first value is the
     * state now. Each value re-takes the decision, so the moment the phone moves from Wi-Fi to mobile
     * data with the switch on, every cached link is dropped, not at the next resolve.
     * [com.stash.core.media.PlayerRepositoryImpl] collects it for as long as the player lives and
     * lines the next song up again on each change.
     */
    fun dataSaverChanges(): Flow<Boolean> =
        combine(connectivity.meteredChanges(), prefs.saveData) { metered, _ -> snapshot(metered).dataSaver }
            .distinctUntilChanged()

    // ponytail: a change between two resolves (a Prefer Tidal AAC flip, a tier picked) drops the cache
    // at the next resolve or cache read, not at the change itself; Data saver's own changes (the
    // network, the switch) drop it at once (dataSaverChanges). Stamping entries with their quality and
    // checking at read time would close the rest; not yet earned.
    /**
     * @param metered the network's meteredness: the callback's answer when it reports one, so the
     *   decision doesn't race the system's view of the default network; else asked now.
     */
    private suspend fun snapshot(metered: Boolean = connectivity.isMetered()): Decision {
        val lossless = losslessPrefs.enabledNow()
        val dataSaver = prefs.saveDataNow() && metered
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
