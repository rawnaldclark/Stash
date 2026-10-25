package com.stash.core.data.lastfm

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Dedicated DataStore: a few hundred short strings at most, never part of a backup. */
private val Context.lastFmEnrichmentBackoffDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "lastfm_enrichment_backoff",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * The tracks a Last.fm enrichment pass failed on lately, so its next run
 * starts elsewhere.
 *
 * Both enrichment workers take the newest candidates first, and every Last.fm
 * error except "not found" is treated as temporary (no sentinel is written, so
 * the track is asked about again). Five failures in a row end a run, which
 * is how an outage stops spending requests. But when the newest tracks fail
 * the same way every time (a response that won't parse, an artist-level
 * error), they headed every run, the run stopped on them, and no older track
 * was ever enriched. A track that failed waits out [BACKOFF_MS] before it is
 * a candidate again; the batch fills from further down meanwhile.
 *
 * An outage records the few tracks it failed on before the run stopped too.
 * They only wait a while longer; nothing is written to the track itself, so a
 * failure here never settles anything for good. Kept per [Pass], capped at
 * [MAX_ENTRIES] each (the newest failures win).
 */
@Singleton
class LastFmEnrichmentBackoff internal constructor(
    private val dataStore: DataStore<Preferences>,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.lastFmEnrichmentBackoffDataStore)

    /**
     * One enrichment worker's record: how long a track waits ([backoffMs]) and
     * how many tracks are remembered ([maxEntries]).
     */
    enum class Pass(
        internal val key: Preferences.Key<Set<String>>,
        internal val backoffMs: Long = BACKOFF_MS,
        internal val maxEntries: Int = MAX_ENTRIES,
    ) {
        TAGS(stringSetPreferencesKey("tags")),
        TRACK_INFO(stringSetPreferencesKey("track_info")),

        /**
         * ArtBackfillWorker: rows its Last.fm lookup answered but couldn't fill
         * (not found, no art, no duration), or that are never looked up (voice
         * notes, "Unknown Artist"). As long as a remembered miss
         * ([LastFmCachedMiss.TTL_MS]), so a row waits as long as its answer is
         * believed; more entries because a library can hold hundreds of such
         * files and the batch is 500.
         */
        ART_BACKFILL(stringSetPreferencesKey("art_backfill"), LastFmCachedMiss.TTL_MS, 2_000),

        /**
         * ArtBackfillWorker's album pass: rows Last.fm names no album for. More entries: a
         * library's songs without an album run to thousands.
         */
        ALBUM_BACKFILL(stringSetPreferencesKey("album_backfill"), LastFmCachedMiss.TTL_MS, 8_000),
    }

    /**
     * [candidates] for rows of any shape: up to [batchSize] of [fetch]'s rows
     * whose [id] [pass] has not failed on within its back-off.
     */
    suspend fun <T> candidateRows(
        pass: Pass,
        batchSize: Int,
        nowMs: Long,
        id: (T) -> Long,
        fetch: suspend (limit: Int) -> List<T>,
    ): List<T> {
        val backedOff = backedOff(pass, nowMs)
        return fetch(batchSize + backedOff.size).filterNot { id(it) in backedOff }.take(batchSize)
    }

    /**
     * Up to [batchSize] candidates from [fetch] (called with the row limit to
     * ask for), leaving out the tracks [pass] failed on within [BACKOFF_MS] of
     * [nowMs]. Asks for that many more rows, so the batch is still full when
     * the backed-off tracks are the newest. An unreadable record backs nothing
     * off: it must never stop enrichment.
     */
    suspend fun candidates(
        pass: Pass,
        batchSize: Int,
        nowMs: Long,
        fetch: suspend (limit: Int) -> List<Long>,
    ): List<Long> {
        val backedOff = backedOff(pass, nowMs)
        return fetch(batchSize + backedOff.size).filterNot { it in backedOff }.take(batchSize)
    }

    /** The tracks [pass] failed on within [BACKOFF_MS] of [nowMs]. */
    internal suspend fun backedOff(pass: Pass, nowMs: Long): Set<Long> = try {
        decode(dataStore.data.first()[pass.key].orEmpty())
            .filterValues { failedAt -> isBackedOff(failedAt, nowMs, pass.backoffMs) }
            .keys
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "couldn't read the ${pass.name} back-off; trying every track", e)
        emptySet()
    }

    /**
     * Records that [pass] failed on [trackIds] at [nowMs], and drops entries
     * whose back-off is over. Best effort: a lost record costs a retry of the
     * same tracks, never an enrichment.
     */
    suspend fun recordFailures(pass: Pass, trackIds: Collection<Long>, nowMs: Long) {
        if (trackIds.isEmpty()) return
        try {
            dataStore.edit { prefs ->
                val entries = decode(prefs[pass.key].orEmpty())
                    .filterValues { failedAt -> isBackedOff(failedAt, nowMs, pass.backoffMs) }
                    .toMutableMap()
                trackIds.forEach { entries[it] = nowMs }
                prefs[pass.key] = encode(entries, pass.maxEntries)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "couldn't record ${trackIds.size} ${pass.name} failures", e)
        }
    }

    internal companion object {
        private const val TAG = "LastFmEnrichBackoff"

        /** How long a failed track waits. The workers run daily, so a week is a handful of runs. */
        const val BACKOFF_MS = 7L * 24 * 60 * 60 * 1000

        /** Entries kept per pass; the oldest failures go first beyond it. */
        const val MAX_ENTRIES = 500

        /**
         * Inside the window. A failure stamped in the future (the clock moved
         * back) counts as over, so a clock change can't hide a track for good.
         */
        fun isBackedOff(failedAtMs: Long, nowMs: Long, backoffMs: Long = BACKOFF_MS): Boolean =
            nowMs - failedAtMs in 0 until backoffMs

        /** `"<trackId>:<failedAtMs>"` entries; anything else is skipped. */
        fun decode(raw: Set<String>): Map<Long, Long> = raw.mapNotNull { entry ->
            val id = entry.substringBefore(':').toLongOrNull()
            val failedAt = entry.substringAfter(':', "").toLongOrNull()
            if (id == null || failedAt == null) null else id to failedAt
        }.toMap()

        /** The newest [maxEntries] of [entries], encoded for [decode]. */
        fun encode(entries: Map<Long, Long>, maxEntries: Int = MAX_ENTRIES): Set<String> = entries.entries
            .sortedByDescending { it.value }
            .take(maxEntries)
            .mapTo(HashSet()) { "${it.key}:${it.value}" }
    }
}
