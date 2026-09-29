package com.stash.core.data.sync.workers

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmCredentials
import com.stash.core.data.lastfm.LastFmSessionPreference
import com.stash.core.data.mix.MixGenerator
import com.stash.core.data.mix.StashMixDefaults
import com.stash.core.data.mix.TagPoolBuilder
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * The first-launch race: the one-shot and the periodic mix refresh started in
 * the same millisecond, both read Daily Discover with `playlistId = null`, and
 * the second insert of its playlist threw `UNIQUE constraint failed:
 * playlists.source_id`, failing the periodic job for good.
 *
 * Runs the real worker against an in-memory database for the recipe and
 * playlist tables; everything network-facing is stubbed off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class StashMixRefreshWorkerConcurrencyTest {

    private lateinit var db: StashDatabase
    private val mixGenerator: MixGenerator = mockk()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        ).build()
        // As on first launch: StashApplication seeds Daily Discover, then starts both refreshes.
        runBlocking { StashMixDefaults.seedIfNeeded(db.stashMixRecipeDao()) }
    }

    @After fun tearDown() { db.close() }

    @Test fun `two refreshes started together run one after the other and create one playlist`() = runBlocking {
        // Holds the first run inside generate() long enough for an unserialized
        // second run to reach it too, i.e. to have read the same null playlistId.
        val inside = AtomicInteger(0)
        val maxInside = AtomicInteger(0)
        val bothInside = CompletableDeferred<Unit>()
        coEvery { mixGenerator.generate(any(), any()) } coAnswers {
            val now = inside.incrementAndGet()
            maxInside.accumulateAndGet(now, ::maxOf)
            if (now == 2) bothInside.complete(Unit)
            withTimeoutOrNull(OVERLAP_WAIT_MS) { bothInside.await() }
            inside.decrementAndGet()
            emptyList<TrackEntity>()
        }

        val first = async(Dispatchers.Default) { runCatching { newWorker().doWork() } }
        val second = async(Dispatchers.Default) { runCatching { newWorker().doWork() } }

        assertEquals(ListenableWorker.Result.success(), first.await().getOrThrow())
        assertEquals(ListenableWorker.Result.success(), second.await().getOrThrow())
        assertEquals("the two runs overlapped", 1, maxInside.get())
        assertEquals(mixRow().id, dailyDiscover().playlistId)
    }

    @Test fun `a refresh reuses the mix's playlist when the recipe lost its id`() = runBlocking {
        // A run that inserted the playlist but died before saving its id on the recipe.
        val orphanId = db.playlistDao().insert(
            PlaylistEntity(
                name = "Daily Discover",
                source = MusicSource.BOTH,
                sourceId = "stash_mix_${dailyDiscover().id}",
                type = PlaylistType.STASH_MIX,
                syncEnabled = true,
            ),
        )
        coEvery { mixGenerator.generate(any(), any()) } returns emptyList()

        val result = newWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(orphanId, dailyDiscover().playlistId)
    }

    private suspend fun dailyDiscover() = db.stashMixRecipeDao().getActive().single()

    private suspend fun mixRow(): PlaylistEntity =
        checkNotNull(db.playlistDao().findBySourceId("stash_mix_${dailyDiscover().id}"))

    /** A batch refresh, as both the first-launch one-shot and the periodic job run it. */
    private fun newWorker(): StashMixRefreshWorker {
        val params: WorkerParameters = mockk(relaxed = true) {
            every { inputData } returns workDataOf()
        }
        val lastFmApiClient: LastFmApiClient = mockk(relaxed = true)
        return StashMixRefreshWorker(
            appContext = mockk<Context>(relaxed = true),
            params = params,
            recipeDao = db.stashMixRecipeDao(),
            playlistDao = db.playlistDao(),
            discoveryQueueDao = mockk(relaxed = true),
            listeningEventDao = mockk(relaxed = true),
            trackDao = mockk(relaxed = true),
            mixGenerator = mixGenerator,
            seedGenerator = mockk(relaxed = true),
            lastFmApiClient = lastFmApiClient,
            lastFmCredentials = mockk<LastFmCredentials> { every { isConfigured } returns false },
            sessionPreference = mockk<LastFmSessionPreference> { every { session } returns flowOf(null) },
            blocklistGuard = mockk<BlocklistGuard>(relaxed = true),
            trackSkipEventDao = mockk(relaxed = true),
            tagPoolBuilder = TagPoolBuilder(lastFmApiClient),
            trackMatcher = mockk(relaxed = true),
            downloadNetworkPreference = mockk(relaxed = true),
        )
    }

    private companion object {
        const val OVERLAP_WAIT_MS = 1_000L
    }
}
