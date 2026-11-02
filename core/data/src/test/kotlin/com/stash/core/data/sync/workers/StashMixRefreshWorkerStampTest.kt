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
import com.stash.core.data.lastfm.LastFmSession
import com.stash.core.data.lastfm.LastFmSessionPreference
import com.stash.core.data.mix.MixGenerator
import com.stash.core.data.mix.MixSeedGenerator
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
import java.io.IOException
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
    private val mixGenerator: MixGenerator = mockk(relaxed = true)

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

    // ── A full run whose network part didn't work is not recorded ─────────────────────────────

    /** The control for the three below: Last.fm set up and signed in, online, every call came back. */
    @Test fun `an online full run with Last fm records the refresh`() = runBlocking {
        assertEquals(ListenableWorker.Result.success(), lastFmWorker().doWork())

        assertTrue("the online run left no record", stamp().lastFullRefreshAt() > 0L)
    }

    /**
     * The review's case: the daily job has no network constraint, so it runs in an airplane-mode night. Its persona fetch
     * and discovery seeds fail quietly (local seeds, no candidates), and the record it wrote made the next online cold
     * start skip the refresh for 20 h, night after night.
     */
    @Test fun `an offline full run writes no record, so the next online cold start refreshes`() = runBlocking {
        val worker = lastFmWorker(personasCameBack = false).apply { networkAvailable = { false } }

        assertEquals("the refresh itself still succeeds", ListenableWorker.Result.success(), worker.doWork())

        assertEquals(0L, stamp().lastFullRefreshAt())
        assertTrue(stamp().coldStartRefreshDue(mixNeedsBuilding = false))
    }

    /** No Last.fm username: no persona fetch to fail, and the seed calls swallow theirs. Only the connection tells. */
    @Test fun `an offline full run without a Last fm username writes no record either`() = runBlocking {
        val worker = lastFmWorker(username = null).apply { networkAvailable = { false } }

        assertEquals(ListenableWorker.Result.success(), worker.doWork())

        assertEquals(0L, stamp().lastFullRefreshAt())
    }

    @Test fun `a persona fetch that didn't come back writes no record, even online`() = runBlocking {
        assertEquals(ListenableWorker.Result.success(), lastFmWorker(personasCameBack = false).doWork())

        assertEquals(0L, stamp().lastFullRefreshAt())
    }

    @Test fun `a discovery queueing that failed writes no record`() = runBlocking {
        assertEquals(ListenableWorker.Result.success(), lastFmWorker(discoveryFails = true).doWork())

        assertEquals(0L, stamp().lastFullRefreshAt())
    }

    /**
     * [newWorker] with Last.fm set up and [username] signed in, online unless a test says otherwise. Persona calls come
     * back ([personasCameBack]) or fail as offline; the discovery seed generator finds nothing or throws ([discoveryFails]).
     */
    private fun lastFmWorker(
        username: String? = "listener",
        personasCameBack: Boolean = true,
        discoveryFails: Boolean = false,
    ): StashMixRefreshWorker {
        val client: LastFmApiClient = mockk(relaxed = true)
        if (personasCameBack) {
            coEvery { client.getUserTopTracks(any(), any(), any()) } returns Result.success(emptyList())
            coEvery { client.getUserTopArtists(any(), any(), any()) } returns Result.success(emptyList())
        } else {
            coEvery { client.getUserTopTracks(any(), any(), any()) } returns Result.failure(IOException("offline"))
            coEvery { client.getUserTopArtists(any(), any(), any()) } returns Result.failure(IOException("offline"))
        }
        val seeds: MixSeedGenerator = mockk {
            if (discoveryFails) {
                coEvery { generate(any(), any(), any(), any(), any()) } throws IOException("offline")
            } else {
                coEvery { generate(any(), any(), any(), any(), any()) } returns emptyList()
            }
        }
        return newWorker(
            lastFmApiClient = client,
            seedGenerator = seeds,
            lastFmConfigured = true,
            signedIn = username?.let { LastFmSession(it, "session-key") },
        ).apply { networkAvailable = { true } }
    }

    /** The real worker with a real Context (for the stamp's SharedPreferences); network-facing parts stubbed off. */
    private fun newWorker(
        input: androidx.work.Data = workDataOf(),
        lastFmApiClient: LastFmApiClient = mockk(relaxed = true),
        seedGenerator: MixSeedGenerator = mockk(relaxed = true),
        lastFmConfigured: Boolean = false,
        signedIn: LastFmSession? = null,
    ): StashMixRefreshWorker {
        val params: WorkerParameters = mockk(relaxed = true) { every { inputData } returns input }
        return StashMixRefreshWorker(
            appContext = context,
            params = params,
            recipeDao = db.stashMixRecipeDao(),
            playlistDao = db.playlistDao(),
            discoveryQueueDao = mockk(relaxed = true),
            listeningEventDao = mockk(relaxed = true),
            trackDao = mockk(relaxed = true),
            mixGenerator = mixGenerator,
            seedGenerator = seedGenerator,
            lastFmApiClient = lastFmApiClient,
            lastFmCredentials = mockk<LastFmCredentials> { every { isConfigured } returns lastFmConfigured },
            sessionPreference = mockk<LastFmSessionPreference> { every { session } returns flowOf(signedIn) },
            blocklistGuard = mockk<BlocklistGuard>(relaxed = true),
            trackSkipEventDao = mockk(relaxed = true),
            tagPoolBuilder = TagPoolBuilder(lastFmApiClient),
            trackMatcher = mockk(relaxed = true),
            downloadNetworkPreference = mockk(relaxed = true),
        )
    }
}
