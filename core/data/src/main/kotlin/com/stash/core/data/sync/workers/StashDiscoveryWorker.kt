package com.stash.core.data.sync.workers

import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stash.core.data.db.dao.DiscoveryQueueDao
import com.stash.core.data.sync.SyncNotificationManager
import com.stash.core.model.DownloadNetworkMode
import com.stash.core.data.prefs.DownloadNetworkPreference
import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.DiscoveryQueueEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.sync.TrackMatcher
import com.stash.core.model.MusicSource
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Drains pending [DiscoveryQueueEntity] rows queued by
 * [StashMixRefreshWorker]. For each one:
 *
 *  1. Check whether a track with the same canonical identity already
 *     exists (downloaded) in the library — if so, skip and just reuse
 *     the existing track row when the materializer later links the
 *     recipe's playlist.
 *  2. Otherwise create a stub [TrackEntity] with `isStreamable = true,
 *     isDownloaded = false`. v0.9.37 stream-only seam: no
 *     `download_queue` row is filed. The v0.9.30 streaming engine
 *     (`PlayerRepositoryImpl.buildMediaItemForTrack` → Qobuz/Kennyy +
 *     YouTube fallback) plays the stub on demand without ever writing
 *     a file to disk. Saves data + storage for what is, by recipe
 *     design, ephemeral discovery content. Existing downloaded Mix
 *     tracks remain on disk; no purge.
 *  3. Mark the discovery row DONE with a reference to the created (or
 *     reused) track so the next [StashMixRefreshWorker.materializeMix]
 *     pass can link it into the recipe's playlist via
 *     `PlaylistDao.getStreamableOrDoneTrackIdsForRecipe`.
 *
 * v0.9.37 also dropped the `DownloadQueueDao` constructor injection —
 * this worker no longer files download rows. The chained
 * [DiscoveryDownloadWorker] still drains legacy / leftover rows in
 * `download_queue` (orphan PENDING, retry-eligible FAILED from prior
 * runs); see `doWork()`'s tail chain.
 *
 * Caps per-recipe throughput at 100 new discoveries per rolling 7 days
 * (a hold-over from the download-era storage cap; storage cost is gone
 * now, raising the cap is tracked as separate future work). Requires
 * unmetered network + charging to be polite about data and battery.
 */
@HiltWorker
class StashDiscoveryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val discoveryQueueDao: DiscoveryQueueDao,
    private val trackDao: TrackDao,
    private val recipeDao: StashMixRecipeDao,
    private val trackMatcher: TrackMatcher,
    private val blocklistGuard: com.stash.core.data.blocklist.BlocklistGuard,
    private val downloadNetworkPreference: DownloadNetworkPreference,
    private val syncNotificationManager: SyncNotificationManager,
) : CoroutineWorker(appContext, params) {

    /**
     * Required for expedited execution on API < 31 (where expedited work runs
     * as a short foreground service). Mirrors [DiscoveryDownloadWorker]'s
     * notification so the brief "preparing your mix" promotion looks
     * consistent with the rest of the discovery pipeline. On API 31+
     * WorkManager uses the platform expedited job and never shows this.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        buildForegroundInfo("Building your mix", "Finding tracks…", progress = -1f)

    private fun buildForegroundInfo(title: String, text: String, progress: Float): ForegroundInfo {
        val notification = syncNotificationManager.buildProgressNotification(
            title = title,
            text = text,
            progress = progress,
            cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
        )
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                SyncNotificationManager.NOTIFICATION_ID_PROGRESS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(SyncNotificationManager.NOTIFICATION_ID_PROGRESS, notification)
        }
    }

    companion object {
        private const val TAG = "StashDiscovery"
        private const val WORK_NAME = "stash_discovery"
        private const val ONE_SHOT_WORK_NAME = "stash_discovery_oneshot"
        private const val BATCH_SIZE = 60
        // #287: was 100/rolling-7-days — a download-era relic (its own
        // comment sanctioned raising it once storage cost was gone, which
        // v0.9.37's stream-only stubs delivered). A capped pipe starves
        // "Daily" Discover: on the reporting device 212 candidates sat
        // PENDING behind an exhausted weekly window, so every refresh
        // rotated a frozen backlog. 40/rolling-24h (~280/wk of DB rows,
        // no files) keeps genuinely-new tracks arriving every day while
        // still bounding Last.fm/resolver work.
        private const val PER_RECIPE_DAILY_CAP = 40
        private val DAY_CAP_WINDOW_MS = TimeUnit.DAYS.toMillis(1)
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

        /**
         * Fire a one-shot discovery sweep — manual user trigger, no charging
         * requirement. Respects [DownloadNetworkMode] for cellular gating via
         * [constraintsForManualTrigger]. Unique work name + REPLACE policy so a
         * rapid double-tap coalesces. At the end of [doWork], the existing
         * v0.9.20 chain to [DiscoveryDownloadWorker] fires, completing the
         * pipeline: discovery_queue PENDING → stubs + download_queue PENDING →
         * actual downloads.
         */
        fun enqueueOneTime(context: Context, mode: DownloadNetworkMode, expedited: Boolean = false) {
            val builder = OneTimeWorkRequestBuilder<StashDiscoveryWorker>()
            if (expedited) {
                // Jump the queue: when a mix is created/refreshed mid library-sync
                // the drain would otherwise sit behind hundreds of sync-spawned
                // jobs (downloads, lyrics, art-backfill) on the OS JobScheduler,
                // leaving the mix on "Building…" for a long time. RUN_AS_NON_-
                // EXPEDITED fallback means an out-of-quota app still drains, just
                // not expedited — never worse than the non-expedited path.
                //
                // Expedited jobs may carry ONLY network + storage constraints —
                // battery-not-low (in constraintsForManualTrigger) is rejected by
                // WorkRequest.build() — so use a network-only constraint here.
                builder
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                    )
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            } else {
                builder.setConstraints(constraintsForManualTrigger(mode))
            }
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_SHOT_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                builder.build(),
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

        // v0.9.21: pre-filter the PENDING fetch by under-cap recipes so a
        // single recipe's deferred-at-cap backlog doesn't starve other
        // recipes' fresh candidates out of the BATCH_SIZE window.
        //
        // v0.9.38: combine the cap pre-filter with round-robin batching.
        // Plain FIFO `getPending` lets one recipe with a deep PENDING
        // backlog monopolise the BATCH_SIZE window even when it's *under*
        // cap (conversation 2026-05-28: Daily Discover had 131 PENDING
        // queued before Deep Cuts/First Listen's batches and consumed 57
        // of every 60-row drain; Deep Cuts + First Listen never ran). The
        // cap query was also dead in v0.9.37 — fixed in the DAO by
        // counting both downloaded AND streamable DONE rows.
        val cappedRecipeIds = discoveryQueueDao.findRecipesAtWeeklyCap(
            sinceMillis = System.currentTimeMillis() - DAY_CAP_WINDOW_MS,
            cap = PER_RECIPE_DAILY_CAP,
        )
        if (cappedRecipeIds.isNotEmpty()) {
            Log.i(TAG, "recipes at cap (excluded from fetch): $cappedRecipeIds")
        }
        // Room rejects empty `IN ()` lists. -1L is safe as a sentinel —
        // real recipe ids are autogen > 0.
        val cappedSentinel = cappedRecipeIds.ifEmpty { listOf(-1L) }
        val activeRecipes = discoveryQueueDao.getRecipesWithPending(cappedSentinel)
        val pending = if (activeRecipes.isEmpty()) {
            emptyList()
        } else {
            // Fair quota: ceil(BATCH_SIZE / activeRecipes). With the typical
            // 3 builtin recipes that's 20/each; a single solo recipe still
            // gets the full 60.
            val perRecipeQuota = (BATCH_SIZE + activeRecipes.size - 1) / activeRecipes.size
            activeRecipes.flatMap { rid ->
                discoveryQueueDao.getPendingForRecipe(rid, perRecipeQuota)
            }
        }
        // Count stream-only stubs created/reused this run so we can trigger a
        // (network-only) re-materialize afterwards — see the post-loop kick.
        var newlyMaterialized = 0
        if (pending.isEmpty()) {
            Log.d(TAG, "no pending discoveries")
            // Don't return early — fall through to the chain. download_queue
            // is a separate table and may hold orphan PENDING or retry-
            // eligible FAILED rows from prior runs that still need draining.
        } else {
            Log.i(TAG, "draining ${pending.size} discovery candidates")

            val now = System.currentTimeMillis()
            val capWindowStart = now - DAY_CAP_WINDOW_MS

            // Per-recipe caps — counted lazily to avoid a DAO hit per candidate.
            val recipeBudget = HashMap<Long, Int>()
            // Per-recipe one-shot "cap fired" log so a recipe with dozens of
            // pending rows doesn't spam logcat with the same deferral line.
            val cappedRecipesLogged = HashSet<Long>()

            for (entry in pending) {
                val used = recipeBudget.getOrPut(entry.recipeId) {
                    discoveryQueueDao.countRecentCompletedForRecipe(entry.recipeId, capWindowStart)
                }
                if (used >= PER_RECIPE_DAILY_CAP) {
                    // Leave as PENDING so tomorrow's window picks it up.
                    if (cappedRecipesLogged.add(entry.recipeId)) {
                        Log.i(
                            TAG,
                            "recipe ${entry.recipeId} at cap " +
                                "($used completed in last 24h, limit $PER_RECIPE_DAILY_CAP) — deferring pending",
                        )
                    }
                    continue
                }

                val result = handle(entry, now)
                if (result.trackId != null) {
                    recipeBudget[entry.recipeId] = used + 1
                    newlyMaterialized++
                }
                discoveryQueueDao.updateStatus(
                    id = entry.id,
                    status = result.status,
                    trackId = result.trackId,
                    completedAt = now,
                    errorMessage = result.error,
                )
            }
        }

        // Stream-only stubs need no download — only a materialize pass to link
        // them into the recipe playlists. That pass runs in StashMixRefreshWorker,
        // which the DiscoveryDownloadWorker chain below also re-kicks — but that
        // worker is battery-not-low gated (it downloads files), so on a low
        // battery the freshly-drained stubs would never surface and the mix sits
        // on "Building…" indefinitely (root cause: device at 4%, stubs DONE,
        // playlist empty). Kick the network-only refresh directly so stream-only
        // mixes materialize regardless of battery. Same unique work as the
        // chain's kick (REPLACE) → the two coalesce when both fire. Guarded, and
        // only when we actually produced stubs so an idle run doesn't spin the
        // refresh⇄drain loop.
        if (newlyMaterialized > 0) {
            // Materialize-only: LINK the freshly-drained stubs into the mix
            // playlists, but do NOT re-queue discovery or re-kick this drain —
            // otherwise refresh⇄drain loops forever, continuously clearing +
            // reinserting every mix (the multi-genre "repopulate" churn).
            runCatching { StashMixRefreshWorker.enqueueOneTime(applicationContext, materializeOnly = true) }
                .onFailure { Log.w(TAG, "post-drain re-materialize enqueue failed; stubs surface on next refresh", it) }
        }

        // v0.9.20: after queueing/processing discoveries, kick the downloader
        // so the new tracks become playable.
        //
        // Always chain — even when discovery_queue was empty this run. Prior
        // runs may have queued download_queue rows that haven't been drained
        // yet (FAILED-with-retry, leftover PENDING, app crash mid-drain).
        //
        // Use manual-trigger constraints (drop charging, respect user network
        // pref) regardless of whether THIS worker invocation was periodic or
        // manual. For the periodic path, the parent's own charging requirement
        // already gated this worker from running — by the time we chain, we
        // know the device is charging + on WiFi, so dropping the charging req
        // on the chain is a no-op. For the manual path, dropping charging is
        // the whole point: the user is actively asking for content; honor that.
        val mode = downloadNetworkPreference.current()
        DiscoveryDownloadWorker.enqueueOneTime(
            applicationContext,
            constraintsForManualTrigger(mode),
        )
        return Result.success()
    }

    private data class HandledResult(
        val status: String,
        val trackId: Long?,
        val error: String?,
    )

    /**
     * Processes a single pending discovery row. v0.9.37 stream-only
     * contract: creates (or reuses) a streamable stub [TrackEntity] for
     * the row. The v0.9.30 streaming engine plays the stub on demand via
     * the Qobuz/Kennyy + YouTube fallback chain; no file is downloaded.
     * [StashMixRefreshWorker.materializeMix] picks the stubs up via
     * `PlaylistDao.getStreamableOrDoneTrackIdsForRecipe` (v0.9.37) on the
     * next refresh pass to link them into the recipe's playlist.
     *
     * Guards: blocklist-rejects the candidate before any insert, and
     * skips recipes whose materialized playlist doesn't exist yet (the
     * very first refresh hasn't run — materializer owns playlist
     * creation, not this worker).
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
        // Don't process discoveries for un-materialized recipes — the
        // first StashMixRefreshWorker pass creates the playlist row, and
        // until that's run there's nothing for materializeMix to link
        // this stub into. Leave the row PENDING-failed; next refresh
        // cycle will re-queue.
        recipe.playlistId
            ?: return HandledResult(
                DiscoveryQueueEntity.STATUS_FAILED,
                null,
                "recipe has no playlist yet — refresh hasn't materialized it",
            )

        // v0.9.15: Reject blocklisted identities. Without this, a blocked
        // track that was previously discovered (and is still in the
        // library row-wise via the rolling rollout) would re-link into
        // the recipe's playlist on every refresh, AND a fresh-stub branch
        // would create a new TrackEntity that bypasses the blocklist.
        if (blocklistGuard.isBlocked(
                artist = entry.artist, title = entry.title,
                spotifyUri = null, youtubeId = null,
            )) {
            return HandledResult(
                status = DiscoveryQueueEntity.STATUS_FAILED,
                trackId = null,
                error = "blocklisted",
            )
        }

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
            // v0.9.37: stream-only seam. Every recipe in `stash_mix_recipes`
            // is materialized as PlaylistType.STASH_MIX (see
            // StashMixRefreshWorker.materializeMix), so every PENDING row
            // this worker drains belongs to a Stash Mix. Per the v0.9.37
            // spec we no longer file a `download_queue` row for these —
            // instead the stub lands with `isStreamable = true` and the
            // v0.9.30 streaming engine plays it on-demand via the
            // Qobuz/Kennyy + YouTube fallback chain. Existing downloaded
            // Mix tracks remain on disk and are untouched.
            //
            // Note: no `findByYoutubeId` upsert defense needed at this
            // call site because the stub is inserted with `youtubeId =
            // null` (the videoId is resolved later by
            // StashDiscoveryWorker's chain into the streaming engine /
            // player path, never by THIS row's insert). With both
            // `spotifyUri` and `youtubeId` NULL the UNIQUE indexes can't
            // collide; the canonical-identity dedup above already absorbs
            // the only realistic cross-source race (streaming engine
            // inserting a row with matching canonical title+artist first,
            // which `findDownloadedByCanonical` won't catch because
            // streaming inserts aren't `is_downloaded = 1`). Broadening
            // that lookup is a separate refactor — out of scope for the
            // stream-only seam; the worst-case here is a duplicate stub,
            // not a constraint violation.
            val stub = TrackEntity(
                title = entry.title,
                artist = entry.artist,
                source = MusicSource.YOUTUBE,
                canonicalTitle = canonicalTitle,
                canonicalArtist = canonicalArtist,
                isDownloaded = false,
                isStreamable = true,
            )
            trackDao.insert(stub)
        }

        // v0.9.21: Do NOT insert into playlist_tracks here. Earlier versions
        // inserted a cross-ref at this point so the mix would "show" stubs
        // before downloads completed, but the UI hides non-downloaded
        // tracks anyway AND a concurrent StashMixRefreshWorker's
        // clearPlaylistTracks would race-wipe these inserts (user-visible
        // 5 → 13 → 5 flash, conversation 2026-05-12). Linking is owned
        // solely by materializeMix() via getDoneTrackIdsForRecipe(), which
        // only sees is_downloaded=1 tracks — eliminates the race and the
        // phantom-stub flash.
        return HandledResult(
            status = DiscoveryQueueEntity.STATUS_DONE,
            trackId = trackId,
            error = null,
        )
    }
}
