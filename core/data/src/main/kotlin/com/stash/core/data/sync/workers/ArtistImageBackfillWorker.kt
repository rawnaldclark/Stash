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
import com.stash.data.ytmusic.model.ArtistPhotoResolution
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
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
 * Only a GENUINE "no avatar" answer stamps the permanent sentinel: a row with
 * NULL `image_url` + a stamp means the API answered and had no photo, so that
 * name is never re-polled. Transient failures (rate limit, DNS blip, InnerTube
 * 5xx) write NOTHING — the name stays in the candidate set and is re-picked
 * on the next pass, mirroring `ArtBackfillWorker`'s still-blank retry guard.
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
        // Case-insensitive: the PK is COLLATE NOCASE, so the lowercase set
        // dedupes a case-variant credit against an already-attempted name.
        val observed = artistImageDao.observedNames()
            .map { it.lowercase() }
            .toHashSet()

        // Distinct raw track credits → primary acts, matching exactly what the
        // Artists tab groups by — a photo keyed on the displayed name is a
        // clean 1:1 lookup for the UI.
        val missing = artistImageDao.distinctArtistNames()
            .asSequence()
            .map { it.primaryArtist() }
            .filter { it.isNotBlank() && it.lowercase() !in observed }
            .distinct()
            .toList()

        if (missing.isEmpty()) {
            Log.d(TAG, "no artists need a photo")
            return Result.success()
        }

        var filled = 0
        var unresolved = 0
        var failed = 0
        var processed = 0
        val batch = mutableListOf<ArtistImageEntity>()

        for (name in missing.take(BATCH_SIZE)) {
            if (isStopped) break
            val resolution = try {
                ytMusicApiClient.resolveArtistPhoto(name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "resolveArtistPhoto($name) failed: ${e.message}")
                failed++
                continue
            }
            processed++
            when (resolution) {
                is ArtistPhotoResolution.Resolved -> {
                    batch += ArtistImageEntity(
                        artistName = name,
                        imageUrl = resolution.avatarUrl?.takeIf { it.isNotBlank() },
                        attemptedAt = System.currentTimeMillis(),
                    )
                    if (resolution.avatarUrl.isNullOrBlank()) unresolved++ else filled++
                }
                ArtistPhotoResolution.NoAvatar -> {
                    // API answered and had no artist — a genuine no-photo,
                    // stamped so it is never re-polled.
                    batch += ArtistImageEntity(
                        artistName = name,
                        imageUrl = null,
                        attemptedAt = System.currentTimeMillis(),
                    )
                    unresolved++
                }
                ArtistPhotoResolution.Failed -> {
                    // API did not answer (network / 5xx / rate limit). Write
                    // nothing — the name stays missing and is retried next pass.
                    failed++
                }
            }
            // Rate limit — one YT Music search per candidate.
            delay(REQUEST_INTERVAL_MS)
        }

        // One transactional write for the whole pass so observers re-emit once.
        if (batch.isNotEmpty()) artistImageDao.upsertAll(batch)

        Log.i(
            TAG,
            "artist-photo backfill: processed=$processed filled=$filled " +
                "unresolved=$unresolved failed=$failed remaining=${(missing.size - processed).coerceAtLeast(0)}",
        )
        return Result.success()
    }
}