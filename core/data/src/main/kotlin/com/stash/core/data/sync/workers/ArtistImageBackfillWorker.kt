package com.stash.core.data.sync.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stash.core.common.primaryArtist
import com.stash.core.data.db.dao.ArtistImageDao
import com.stash.core.data.db.entity.ArtistImageEntity
import com.stash.data.ytmusic.YTMusicApiClient
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * Background backfill that resolves the official artist photo for every
 * artist shown on the Library Artists tab.
 *
 * ## Why
 * Before this worker, the Artists tab rendered album art (or a gradient
 * initial) for every artist — there was no source of *artist* images. YT
 * Music's artists search returns the official avatar, so this worker walks
 * the distinct artist credits in `tracks`, collapses them to their primary
 * act ([primaryArtist], matching the Library regroup), and stores each
 * resolved avatar in the `artist_images` table. The Library then renders
 * photo → album-art proxy → gradient initial, in that order.
 *
 * ## Idempotency / sentinel
 * Each name is upserted with an `attempted_at` stamp whether or not a photo
 * resolved. A NULL `image_url` with a stamp is a permanent "no photo" row, so
 * local-only rips and unresolvable acts are never re-polled on later runs —
 * the candidate set is `names NOT IN artist_images`, which shrinks to zero
 * once every act has been attempted.
 *
 * ## Scheduling & throttling
 * Enqueued once per install from [StashApplication] (`KEEP`) and re-fired
 * after each sync (`REPLACE`), so newly-synced artists are picked up. Each
 * run handles [BATCH_SIZE] names at ~[REQUEST_INTERVAL_MS]ms apart to stay
 * well under InnerTube's rate limits; a fresh sync (or app relaunch) drains
 * the remainder. Network is gated to UNMETERED so a full library walk
 * (~hundreds of small searches) never burns cellular data.
 */
@HiltWorker
class ArtistImageBackfillWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val artistImageDao: ArtistImageDao,
    private val ytMusicApiClient: YTMusicApiClient,
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "ArtistImgBackfill"
        private const val WORK_NAME = "stash_artist_image_backfill"
        private const val BATCH_SIZE = 150
        private const val REQUEST_INTERVAL_MS = 400L

        /**
         * Schedule the artist-photo backfill. `KEEP`: a queued or running pass
         * is left alone, so a re-launch before the worker completes is a no-op.
         */
        fun enqueueOneTime(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .build()
            val work = OneTimeWorkRequestBuilder<ArtistImageBackfillWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                work,
            )
        }

        /**
         * Post-sync re-fire. `REPLACE` so a freshly-finished sync immediately
         * re-runs the backfill for any artists the sync just added, instead of
         * waiting for the next app launch's `KEEP` request to be a no-op.
         */
        fun enqueueAfterSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .build()
            val work = OneTimeWorkRequestBuilder<ArtistImageBackfillWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                work,
            )
        }
    }

    override suspend fun doWork(): Result {
        // Names already resolved OR marked unresolvable (NULL image + stamp).
        val observed = artistImageDao.observedNames().toHashSet()

        // Distinct raw track credits → primary acts, matching exactly what the
        // Artists tab groups by — a photo keyed on the displayed name is a
        // clean 1:1 lookup for the UI.
        val missing = artistImageDao.distinctArtistNames()
            .asSequence()
            .map { it.primaryArtist() }
            .filter { it.isNotBlank() && it !in observed }
            .distinct()
            .toList()

        if (missing.isEmpty()) {
            Log.d(TAG, "no artists need a photo")
            return Result.success()
        }

        var filled = 0
        var unresolved = 0
        for (name in missing.take(BATCH_SIZE)) {
            val avatar = runCatching { ytMusicApiClient.resolveArtist(name)?.avatarUrl }
                .getOrNull()
            artistImageDao.upsert(
                ArtistImageEntity(
                    artistName = name,
                    imageUrl = avatar?.takeIf { it.isNotBlank() },
                    attemptedAt = System.currentTimeMillis(),
                ),
            )
            if (avatar.isNullOrBlank()) unresolved++ else filled++
            // Rate limit — one YT Music search per candidate.
            delay(REQUEST_INTERVAL_MS)
        }

        Log.i(
            TAG,
            "artist-photo backfill: processed=${minOf(BATCH_SIZE, missing.size)} " +
                "filled=$filled unresolved=$unresolved remaining=${missing.size - BATCH_SIZE}",
        )
        return Result.success()
    }
}
