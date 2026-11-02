package com.stash.core.data.sync.workers

import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.DiscoveryQueueDao
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.dao.TrackSkipEventDao
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.StashMixRecipeEntity
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmCredentials
import com.stash.core.data.lastfm.LastFmSessionPreference
import com.stash.core.data.mix.MixGenerator
import com.stash.core.data.mix.MixSeedGenerator
import com.stash.core.data.mix.TagPoolBuilder
import com.stash.core.data.prefs.DownloadNetworkPreference
import com.stash.core.data.sync.TrackMatcher
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MockK end-to-end test for the v0.9.37 Stash Mix streaming-first rollout
 * (Task 3). `StashMixRefreshWorker.materializeMix` now sources discovery
 * survivors via `PlaylistDao.getStreamableOrDoneTrackIdsForRecipe` instead
 * of `DiscoveryQueueDao.getDoneTrackIdsForRecipe`, so DONE rows whose track
 * stub is stream-only (is_streamable = 1, is_downloaded = 0) are linked
 * into the materialized Mix playlist alongside downloaded survivors.
 *
 * The DAO query itself is unit-tested in `PlaylistDaoStreamableOrDoneTest`
 * — this test verifies the WORKER calls the streaming-aware query (not the
 * legacy downloaded-only one) and inserts the returned ids as
 * [PlaylistTrackCrossRef] rows on the Mix playlist.
 */
class StashMixRefreshWorkerStreamOnlyMaterializeTest {

    private val appContext: Context = mockk(relaxed = true)
    private val recipeDao: StashMixRecipeDao = mockk(relaxed = true)
    private val playlistDao: PlaylistDao = mockk(relaxed = true)
    private val discoveryQueueDao: DiscoveryQueueDao = mockk(relaxed = true)
    private val listeningEventDao: ListeningEventDao = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val mixGenerator: MixGenerator = mockk()
    private val seedGenerator: MixSeedGenerator = mockk(relaxed = true)
    private val lastFmApiClient: LastFmApiClient = mockk(relaxed = true)
    private val lastFmCredentials: LastFmCredentials = mockk {
        // Skip the discovery/Last.fm queueing branch entirely — irrelevant to
        // the materializeMix swap under test.
        coEvery { isConfigured } returns false
    }
    private val sessionPreference: LastFmSessionPreference = mockk {
        coEvery { session } returns flowOf(null)
    }
    private val blocklistGuard: BlocklistGuard = mockk {
        coEvery { isBlockedByTrackId(any()) } returns false
    }
    private val trackSkipEventDao: TrackSkipEventDao = mockk(relaxed = true)
    private val trackMatcher: TrackMatcher = mockk(relaxed = true)
    private val downloadNetworkPreference: DownloadNetworkPreference = mockk(relaxed = true)
    private val tagPoolBuilder = TagPoolBuilder(lastFmApiClient)

    private fun newWorker(recipeId: Long): StashMixRefreshWorker {
        val params: WorkerParameters = mockk(relaxed = true) {
            coEvery { inputData } returns workDataOf(
                StashMixRefreshWorker.KEY_RECIPE_ID to recipeId,
            )
        }
        return StashMixRefreshWorker(
            appContext, params,
            recipeDao, playlistDao, discoveryQueueDao, listeningEventDao,
            trackDao, mixGenerator, seedGenerator, lastFmApiClient,
            lastFmCredentials, sessionPreference, blocklistGuard,
            trackSkipEventDao, tagPoolBuilder, trackMatcher, downloadNetworkPreference,
        )
    }

    @Test fun `materializeMix links downloaded AND stream-only discovery survivors`() = runTest {
        val recipe = recipe(id = 1L, name = "Daily Discover", playlistId = 100L)

        coEvery { recipeDao.getById(1L) } returns recipe
        coEvery { recipeDao.getActive() } returns listOf(recipe)

        // Library section empty — focus the assertion on the discovery
        // survivors pulled in via the new DAO query.
        coEvery { mixGenerator.generate(recipe, any()) } returns emptyList()

        // Existing playlist found (skips the create branch).
        coEvery { playlistDao.getById(100L) } returns PlaylistEntity(
            id = 100L,
            name = "Daily Discover",
            source = MusicSource.BOTH,
            sourceId = "stash_mix_1",
            type = PlaylistType.STASH_MIX,
            trackCount = 0,
            syncEnabled = true,
            isActive = true,
        )

        // The new query returns BOTH the downloaded survivor AND the
        // stream-only one. The legacy DAO would have filtered out the
        // stream-only id by `is_downloaded = 1` — this is the swap's
        // whole point.
        val downloadedTrackId = 555L
        val streamOnlyTrackId = 777L
        coEvery {
            playlistDao.getStreamableOrDoneTrackIdsForRecipe(1L)
        } returns listOf(downloadedTrackId, streamOnlyTrackId)

        // Membership is written atomically via replaceMixMembership (audit:
        // FK-787 torn-prefix guard) — assert on the ordered ids handed to that
        // transaction rather than on individual insertCrossRef calls, which now
        // live inside the @Transaction default method (invisible on a mock).
        val ordered = slot<List<Long>>()
        coEvery {
            playlistDao.replaceMixMembership(any(), capture(ordered), any(), any())
        } returns Unit

        newWorker(recipeId = 1L).doWork()

        // BOTH ids must have been linked to the Mix playlist (id=100L).
        coVerify(exactly = 1) {
            playlistDao.replaceMixMembership(eq(100L), any(), any(), any())
        }
        assertEquals(
            "materializeMix must link both downloaded and stream-only DONE survivors",
            setOf(downloadedTrackId, streamOnlyTrackId),
            ordered.captured.toSet(),
        )

        // Legacy DAO method MUST NOT be used as the survivor source — if it
        // is, stream-only stubs disappear from the Mix again. (This is the
        // core regression we'd silently introduce by reverting the swap.)
        coVerify(exactly = 0) {
            discoveryQueueDao.getDoneTrackIdsForRecipe(any(), any())
        }
    }

    // ── v0.9.42 idempotency backstop ──────────────────────────────────────
    // materializeMix must skip the destructive clear + reinsert when the
    // playlist's CURRENT ordered membership already equals what it would
    // write — this prevents the visible "tracks vanish then repopulate"
    // flash on no-op refreshes (the backstop to the worker-loop gate).

    @Test fun `materializeMix skips clear and reinsert when membership is unchanged`() = runTest {
        val recipe = recipe(id = 1L, name = "Daily Discover", playlistId = 100L)
        coEvery { recipeDao.getById(1L) } returns recipe
        coEvery { recipeDao.getActive() } returns listOf(recipe)

        // Library section empty → final membership is exactly the discovery
        // survivors, in order.
        coEvery { mixGenerator.generate(recipe, any()) } returns emptyList()
        coEvery { playlistDao.getById(100L) } returns PlaylistEntity(
            id = 100L,
            name = "Daily Discover",
            source = MusicSource.BOTH,
            sourceId = "stash_mix_1",
            type = PlaylistType.STASH_MIX,
            trackCount = 2,
            syncEnabled = true,
            isActive = true,
        )
        coEvery {
            playlistDao.getStreamableOrDoneTrackIdsForRecipe(1L)
        } returns listOf(555L, 777L)
        // Current ordered membership EQUALS what we'd write (same ids, same order).
        coEvery { playlistDao.getOrderedTrackIdsForPlaylist(100L) } returns listOf(555L, 777L)

        newWorker(recipeId = 1L).doWork()

        // No destructive churn: the atomic membership swap must not run at all.
        // (clearPlaylistTracks/insertCrossRef now live inside the @Transaction
        // method, so they can't be observed on a relaxed mock — assert on the
        // txn boundary itself, or a removed short-circuit would pass silently.)
        coVerify(exactly = 0) { playlistDao.replaceMixMembership(any(), any(), any(), any()) }
        coVerify(exactly = 0) { playlistDao.createMixWithMembership(any(), any(), any()) }
        // The cover is still rebuilt: its songs may have got their art since it was last built.
        coVerify(exactly = 1) { playlistDao.refreshMixCover(100L) }
    }

    @Test fun `materializeMix re-materializes when membership order differs`() = runTest {
        val recipe = recipe(id = 1L, name = "Daily Discover", playlistId = 100L)
        coEvery { recipeDao.getById(1L) } returns recipe
        coEvery { recipeDao.getActive() } returns listOf(recipe)

        coEvery { mixGenerator.generate(recipe, any()) } returns emptyList()
        coEvery { playlistDao.getById(100L) } returns PlaylistEntity(
            id = 100L,
            name = "Daily Discover",
            source = MusicSource.BOTH,
            sourceId = "stash_mix_1",
            type = PlaylistType.STASH_MIX,
            trackCount = 2,
            syncEnabled = true,
            isActive = true,
        )
        coEvery {
            playlistDao.getStreamableOrDoneTrackIdsForRecipe(1L)
        } returns listOf(555L, 777L)
        // Same ids but DIFFERENT order → not equal → must re-materialize.
        coEvery { playlistDao.getOrderedTrackIdsForPlaylist(100L) } returns listOf(777L, 555L)

        val ordered = slot<List<Long>>()
        coEvery {
            playlistDao.replaceMixMembership(any(), capture(ordered), any(), any())
        } returns Unit

        newWorker(recipeId = 1L).doWork()

        // A differing order must trigger exactly one atomic re-materialize,
        // carrying the freshly-computed order.
        coVerify(exactly = 1) {
            playlistDao.replaceMixMembership(eq(100L), any(), any(), any())
        }
        assertEquals(
            "differing order must trigger a full re-materialize in the new order",
            listOf(555L, 777L),
            ordered.captured,
        )
    }

    // ── Loop-break: materialize-only re-kick ──────────────────────────────
    // The post-drain re-kick from StashDiscoveryWorker exists only to LINK
    // freshly-drained stream-only stubs into the playlists. It must NOT
    // re-queue discovery or re-kick the drain again — otherwise refresh⇄drain
    // form a runaway loop that continuously clears + reinserts every mix
    // (the user-visible "load 40, then repopulate" churn).

    private fun newWorkerMaterializeOnly(): StashMixRefreshWorker {
        val params: WorkerParameters = mockk(relaxed = true) {
            coEvery { inputData } returns workDataOf(
                StashMixRefreshWorker.KEY_MATERIALIZE_ONLY to true,
            )
        }
        return StashMixRefreshWorker(
            appContext, params,
            recipeDao, playlistDao, discoveryQueueDao, listeningEventDao,
            trackDao, mixGenerator, seedGenerator, lastFmApiClient,
            lastFmCredentials, sessionPreference, blocklistGuard,
            trackSkipEventDao, tagPoolBuilder, trackMatcher, downloadNetworkPreference,
        )
    }

    @Test fun `materialize-only refresh does not re-kick the discovery drain`() = runTest {
        val recipe = recipe(id = 1L, name = "Daily Discover", playlistId = 100L)
        coEvery { recipeDao.getActive() } returns listOf(recipe)
        coEvery { mixGenerator.generate(recipe, any()) } returns emptyList()
        coEvery { playlistDao.getById(100L) } returns PlaylistEntity(
            id = 100L, name = "Daily Discover", source = MusicSource.BOTH,
            sourceId = "stash_mix_1", type = PlaylistType.STASH_MIX,
            trackCount = 0, syncEnabled = true, isActive = true,
        )
        coEvery { playlistDao.getStreamableOrDoneTrackIdsForRecipe(1L) } returns listOf(555L)
        // Force a materialize (current membership differs from what we'd write).
        coEvery { playlistDao.getOrderedTrackIdsForPlaylist(100L) } returns emptyList()

        newWorkerMaterializeOnly().doWork()

        // The end-of-refresh discovery-drain kick reads downloadNetworkPreference
        // .current() for its constraints — so a zero call count proves the kick
        // (the loop edge) was skipped.
        coVerify(exactly = 0) { downloadNetworkPreference.current() }
    }

    @Test fun `normal refresh still kicks the discovery drain`() = runTest {
        val recipe = recipe(id = 1L, name = "Daily Discover", playlistId = 100L)
        coEvery { recipeDao.getById(1L) } returns recipe
        coEvery { recipeDao.getActive() } returns listOf(recipe)
        coEvery { mixGenerator.generate(recipe, any()) } returns emptyList()
        coEvery { playlistDao.getById(100L) } returns PlaylistEntity(
            id = 100L, name = "Daily Discover", source = MusicSource.BOTH,
            sourceId = "stash_mix_1", type = PlaylistType.STASH_MIX,
            trackCount = 0, syncEnabled = true, isActive = true,
        )
        coEvery { playlistDao.getStreamableOrDoneTrackIdsForRecipe(1L) } returns listOf(555L)
        coEvery { playlistDao.getOrderedTrackIdsForPlaylist(100L) } returns emptyList()

        newWorker(recipeId = 1L).doWork()

        coVerify(atLeast = 1) { downloadNetworkPreference.current() }
    }

    private fun recipe(id: Long, name: String, playlistId: Long?) = StashMixRecipeEntity(
        id = id,
        name = name,
        discoveryRatio = 0.85f,
        targetLength = 40,
        playlistId = playlistId,
        isBuiltin = true,
        isActive = true,
    )
}
