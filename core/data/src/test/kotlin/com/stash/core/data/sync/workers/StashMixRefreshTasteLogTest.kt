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
import com.stash.core.data.db.dao.TrackTagDao
import com.stash.core.data.db.entity.StashMixRecipeEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmCredentials
import com.stash.core.data.lastfm.LastFmSessionPreference
import com.stash.core.data.mix.MixGenerator
import com.stash.core.data.mix.MixSeedGenerator
import com.stash.core.data.mix.MixSeedStrategy
import com.stash.core.data.mix.TagPoolBuilder
import com.stash.core.data.record.MixTaste
import com.stash.core.data.record.RecordMixTaste
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * What the mixes log about Your record: nothing at all with no taste (the old path, logs included, byte for byte), and with one
 * counts only, never an artist's name (logs can leave the phone with shared diagnostics).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class StashMixRefreshTasteLogTest {
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val listeningEventDao: ListeningEventDao = mockk(relaxed = true)
    private val trackTagDao: TrackTagDao = mockk(relaxed = true)
    private val recipeDao: StashMixRecipeDao = mockk(relaxed = true)
    private val seedGenerator: MixSeedGenerator = mockk()
    private val playlistDao: PlaylistDao = mockk(relaxed = true)
    private val recipe = StashMixRecipeEntity(id = 1L, name = "Daily Discover", discoveryRatio = 0.85f, targetLength = 40, seedStrategy = "ARTIST_SIMILAR", isBuiltin = true)
    private val generator = MixGenerator(trackDao, trackTagDao, listeningEventDao, mockk<DiscoveryQueueDao>(relaxed = true), mockk<BlocklistGuard>(relaxed = true), mockk<TrackSkipEventDao>(relaxed = true))

    @Before fun setUp() {
        ShadowLog.clear()
        coEvery { trackDao.getAllDownloaded() } returns (1L..5L).map { TrackEntity(id = it, title = "Track $it", artist = "Artist $it", canonicalTitle = "t$it", canonicalArtist = "a$it", isDownloaded = true) }
        coEvery { listeningEventDao.getTopArtistsSince(any(), any()) } returns listOf(ListeningEventDao.ArtistPlayCount("Played Here Artist", 3))
        coEvery { recipeDao.getById(1L) } returns recipe
        coEvery { recipeDao.getActive() } returns listOf(recipe)
        coEvery { seedGenerator.generate(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { playlistDao.findBySourceId(any()) } returns null
        coEvery { playlistDao.getStreamableOrDoneTrackIdsForRecipe(any()) } returns emptyList()
    }

    private fun lines() = ShadowLog.getLogsForTag("StashMixRefresh").map { it.msg }

    private fun worker(taste: MixTaste?): StashMixRefreshWorker {
        val params: WorkerParameters = mockk(relaxed = true) { coEvery { inputData } returns workDataOf(StashMixRefreshWorker.KEY_RECIPE_ID to 1L) }
        val lastFm: LastFmApiClient = mockk(relaxed = true)
        return StashMixRefreshWorker(
            mockk<Context>(relaxed = true), params, recipeDao, playlistDao, mockk(relaxed = true), listeningEventDao,
            trackDao, generator, seedGenerator, lastFm, mockk<LastFmCredentials> { coEvery { isConfigured } returns true },
            mockk<LastFmSessionPreference> { coEvery { session } returns flowOf(null) }, mockk(relaxed = true), mockk(relaxed = true),
            TagPoolBuilder(lastFm), mockk(relaxed = true) { coEvery { canonicalArtist(any()) } answers { firstArg() }; coEvery { canonicalTitle(any()) } answers { firstArg() } },
            mockk(relaxed = true),
            object : RecordMixTaste {
                override suspend fun current() = taste
            },
        )
    }

    @Test fun `no taste - no line about the record, the pool or the seeds`() = runTest {
        worker(null).doWork()
        val log = lines()
        assertTrue(log.isNotEmpty())
        assertEquals(emptyList<String>(), log.filter { "library pool" in it || "seeds" in it || "your record" in it })
    }

    @Test fun `with a taste - counts only, never an artist's name`() = runTest {
        worker(MixTaste(listOf("Secret Loved Artist", "Another Secret"), setOf("track 2|artist 2"), emptyMap())).doWork()
        val log = lines()
        assertTrue(log.any { "library pool 4 of 5 downloaded (with your record)" in it })
        assertTrue(log.any { "seeds from plays: 1 of 1 own, 2 from your record" in it })
        assertEquals(emptyList<String>(), log.filter { "Secret" in it || "Played Here Artist" in it || "Track 2" in it })
    }
}
