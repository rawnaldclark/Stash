package com.stash.core.data.sync.workers

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmCredentials
import com.stash.core.data.lastfm.LastFmSessionPreference
import com.stash.core.data.mix.MixGenerator
import com.stash.core.data.mix.StashMixDefaults
import com.stash.core.data.mix.TagPoolBuilder
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wiring for the cold-start gate: the real worker, against an in-memory database, stamps the time after a full run (the
 * kind the cold start would repeat) and only then. A single-mix "Refresh this mix" run and the post-drain re-link don't
 * count as a full refresh, so they never make a cold start skip one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class StashMixRefreshWorkerStampTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: StashDatabase
    private val mixGenerator: MixGenerator = mockk()

    @Before fun setUp() {
        context.getSharedPreferences(StashMixRefreshStamp.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java).build()
        runBlocking { StashMixDefaults.seedIfNeeded(db.stashMixRecipeDao()) }
        coEvery { mixGenerator.generate(any(), any(), any()) } returns emptyList<TrackEntity>()
    }

    @After fun tearDown() { db.close() }

    private fun stamp() = StashMixRefreshStamp(context)

    @Test fun `a full refresh stamps the time, so the next cold start within the day skips it`() = runBlocking {
        assertEquals(0L, stamp().lastFullRefreshAt())
        val before = System.currentTimeMillis()

        assertEquals(ListenableWorker.Result.success(), newWorker().doWork())

        assertTrue("the full run left no stamp", stamp().lastFullRefreshAt() >= before)
        assertEquals(false, stamp().coldStartRefreshDue(mixNeedsBuilding = false))
    }

    @Test fun `a single-mix refresh is not a full refresh`() = runBlocking {
        val recipeId = db.stashMixRecipeDao().getActive().first().id

        assertEquals(ListenableWorker.Result.success(), newWorker(workDataOf(StashMixRefreshWorker.KEY_RECIPE_ID to recipeId)).doWork())

        assertEquals(0L, stamp().lastFullRefreshAt())
    }

    @Test fun `a materialize-only re-link is not a full refresh`() = runBlocking {
        assertEquals(
            ListenableWorker.Result.success(),
            newWorker(workDataOf(StashMixRefreshWorker.KEY_MATERIALIZE_ONLY to true)).doWork(),
        )

        assertEquals(0L, stamp().lastFullRefreshAt())
    }

    /** The real worker with a real Context (for the stamp's SharedPreferences); network-facing parts stubbed off. */
    private fun newWorker(input: androidx.work.Data = workDataOf()): StashMixRefreshWorker {
        val params: WorkerParameters = mockk(relaxed = true) { every { inputData } returns input }
        val lastFmApiClient: LastFmApiClient = mockk(relaxed = true)
        return StashMixRefreshWorker(
            appContext = context,
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
}
