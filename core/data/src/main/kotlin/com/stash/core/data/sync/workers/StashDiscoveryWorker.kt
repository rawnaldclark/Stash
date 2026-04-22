package com.stash.core.data.sync.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stash.core.data.db.dao.DiscoveryQueueDao
import com.stash.core.model.DownloadNetworkMode
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.DiscoveryQueueEntity
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.sync.TrackMatcher
import com.stash.core.model.MusicSource
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Drains pending [DiscoveryQueueEntity] rows queued by
 * [StashMixRefreshWorker]. For each one:
 *
 *  1. Check whether a track with the same canonical identity already
 *     exists in the library — if so, skip the download and just link the
 *     existing track into the recipe's playlist.
 *  2. Otherwise create a stub [TrackEntity] (is_downloaded = false) and
 *     file a [DownloadQueueEntity] row. The existing
 *     [TrackDownloadWorker] picks that up and performs the actual YT
 *     search + download, reusing every bit of matching infra we already
 *     ship. No duplicate code paths.
 *  3. Link the new (or found) track into the recipe's playlist so it
 *     appears in the mix as soon as its audio is downloaded.
 *  4. Mark the discovery row DONE with a reference to the created track
 *     so diagnostics can trace the seed → candidate → download chain.
 *
 * Caps per-recipe throughput at 10 new discoveries per rolling 7 days so
 * a mix with many pending candidates doesn't blow up the user's disk
 * overnight. Requires unmetered network + charging to be polite about
 * data and battery.
 */
@HiltWorker
class StashDiscoveryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val discoveryQueueDao: DiscoveryQueueDao,
    private val downloadQueueDao: DownloadQueueDao,
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
    private val recipeDao: StashMixRecipeDao,
    private val trackMatcher: TrackMatcher,
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "StashDiscovery"
        private const val WORK_NAME = "stash_discovery"
        private const val BATCH_SIZE = 60
        // Raised from 30 → 100 on 2026-04-22. With Stash Discover at 100%
        // discovery ratio + targetLength=50, a 30/week drain couldn't
        // sustain a fresh mix; Last.fm was producing candidates 3× faster
        // than downloads could complete them. 100/week tracks a steady-
        // state of "full mix refresh every 10-14 days" — in line with the
        // 14-day freshness window the recipe filters on.
        private const val PER_RECIPE_WEEKLY_CAP = 100
        private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
        /** Age-out cutoff for PENDING discovery rows — 30 days. */
        private const val PENDING_TTL_MS = 30L * 24 * 60 * 60 * 1000

        /**
         * Schedule / re-schedule the periodic worker with [mode]'s
         * constraints. Uses `UPDATE` policy so a running schedule is
         * replaced in place when the user changes their download-network
         * preference — WorkManager snapshots constraints at enqueue time,
         * so the re-schedule is what makes a setting change take effect.
         */
        fun schedulePeriodic(context: Context, mode: DownloadNetworkMode) {
            val work = PeriodicWorkRequestBuilder<StashDiscoveryWorker>(
                repeatInterval = 1,
                repeatIntervalTimeUnit = TimeUnit.DAYS,
            )
                .setConstraints(constraintsFor(mode))
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                work,
            )
        }
    }

    override suspend fun doWork(): Result {
        // TTL pass: drop PENDING rows that have been sitting longer than
        // 30 days. Stale candidates clog the drain order without value —
        // fresher similar-artist queries in newer refresh cycles would
        // re-surface anything still relevant to the user's taste today.
        val aged = discoveryQueueDao.deleteStalePending(
            cutoffMillis = System.currentTimeMillis() - PENDING_TTL_MS,
        )
        if (aged > 0) {
            Log.i(TAG, "aged out $aged stale PENDING row(s) older than 30 days")
        }

        val pending = discoveryQueueDao.getPending(BATCH_SIZE)
        if (pending.isEmpty()) {
            Log.d(TAG, "no pending discoveries")
            return Result.success()
        }
        Log.i(TAG, "draining ${pending.size} discovery candidates")

        val now = System.currentTimeMillis()
        val weekAgo = now - WEEK_MS

        // Per-recipe caps — counted lazily to avoid a DAO hit per candidate.
        val recipeBudget = HashMap<Long, Int>()

        for (entry in pending) {
            val used = recipeBudget.getOrPut(entry.recipeId) {
                discoveryQueueDao.countRecentCompletedForRecipe(entry.recipeId, weekAgo)
            }
            if (used >= PER_RECIPE_WEEKLY_CAP) {
                // Leave as PENDING so next week's cycle can pick it up.
                Log.d(TAG, "recipe ${entry.recipeId} hit weekly cap — deferring")
                continue
            }

            val result = handle(entry, now)
            if (result.trackId != null) {
                recipeBudget[entry.recipeId] = used + 1
            }
            discoveryQueueDao.updateStatus(
                id = entry.id,
                status = result.status,
                trackId = result.trackId,
                completedAt = now,
                errorMessage = result.error,
            )
        }
        return Result.success()
    }

    private data class HandledResult(
        val status: String,
        val trackId: Long?,
        val error: String?,
    )

    /**
     * Processes a single pending discovery row. Reuses the existing
     * "create track + enqueue download" pattern that DiffWorker uses for
     * unmatched Spotify tracks, so downloads run through the same queue
     * the sync pipeline already drains.
     */
    private suspend fun handle(
        entry: DiscoveryQueueEntity,
        now: Long,
    ): HandledResult {
        val recipe = recipeDao.getById(entry.recipeId)
            ?: return HandledResult(
                DiscoveryQueueEntity.STATUS_FAILED,
                null,
                "recipe ${entry.recipeId} missing",
            )
        val playlistId = recipe.playlistId
            ?: return HandledResult(
                DiscoveryQueueEntity.STATUS_FAILED,
                null,
                "recipe has no playlist yet — refresh hasn't materialized it",
            )

        // De-dup against the existing library by canonical title+artist
        // match. Saves a redundant download when the user already has the
        // track from another source.
        val canonicalTitle = trackMatcher.canonicalTitle(entry.title)
        val canonicalArtist = trackMatcher.canonicalArtist(entry.artist)
        val existing = trackDao.findDownloadedByCanonical(
            canonicalTitle = canonicalTitle.lowercase(),
            canonicalArtist = canonicalArtist.lowercase(),
        )

        val trackId = if (existing != null) {
            // Nothing to download — just link.
            existing.id
        } else {
            // Create stub + enqueue.
            val stub = TrackEntity(
                title = entry.title,
                artist = entry.artist,
                source = MusicSource.YOUTUBE,
                canonicalTitle = canonicalTitle,
                canonicalArtist = canonicalArtist,
                isDownloaded = false,
            )
            val newId = trackDao.insert(stub)
            downloadQueueDao.insert(
                DownloadQueueEntity(
                    trackId = newId,
                    syncId = null,
                    searchQuery = "${entry.artist} - ${entry.title}",
                    youtubeUrl = null,
                )
            )
            newId
        }

        // Link to the mix's playlist at the end of the current ordering.
        // The Home card surfaces these as they become playable; until
        // download completes they're present-but-unplayable.
        val currentCount = playlistDao.getById(playlistId)?.trackCount ?: 0
        playlistDao.insertCrossRef(
            PlaylistTrackCrossRef(
                playlistId = playlistId,
                trackId = trackId,
                position = currentCount,
                addedAt = Instant.ofEpochMilli(now),
            )
        )
        playlistDao.updateTrackCount(playlistId, currentCount + 1)

        return HandledResult(
            status = DiscoveryQueueEntity.STATUS_DONE,
            trackId = trackId,
            error = null,
        )
    }
}
