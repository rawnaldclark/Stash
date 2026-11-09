package com.stash.core.data.mix

import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.DiscoveryQueueDao
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.ListeningEventDao.TrackPlayCountWithLatest
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.dao.TrackSkipEventDao
import com.stash.core.data.db.dao.TrackTagDao
import com.stash.core.data.db.entity.StashMixRecipeEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.db.entity.TrackTagEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [MixGenerator.generate]'s v0.9.20 shortfall-fill threshold.
 * The old gate was `discoveryRatio < 1.0f`; the new gate is
 * `discoveryRatio < 0.5f` — high-discovery recipes return a sparser
 * library slice rather than backfilling from the rest of the library.
 */
class MixGeneratorShortfallFillTest {

    private val trackDao: TrackDao = mockk()
    private val trackTagDao: TrackTagDao = mockk(relaxed = true)
    private val listeningEventDao: ListeningEventDao = mockk(relaxed = true)
    private val discoveryQueueDao: DiscoveryQueueDao = mockk(relaxed = true)
    private val blocklistGuard: BlocklistGuard = mockk(relaxed = true)
    private val trackSkipEventDao: TrackSkipEventDao = mockk(relaxed = true)
    private lateinit var generator: MixGenerator

    @Before fun setUp() {
        generator = MixGenerator(
            trackDao,
            trackTagDao,
            listeningEventDao,
            discoveryQueueDao,
            blocklistGuard,
            trackSkipEventDao,
        )
        coEvery { listeningEventDao.getPlayCountsSinceWithLatest(any()) } returns emptyList()
        coEvery { listeningEventDao.getTrackIdsPlayedSince(any()) } returns emptyList()
        coEvery { trackTagDao.getByTracks(any()) } returns emptyList()
    }

    @Test fun `shortfall fill triggers at discoveryRatio = 0_4`() = runTest {
        coEvery { trackDao.getAllDownloaded() } returns (1L..50L).map { stubTrack(it) }

        val result = generator.generate(
            stubRecipe(discoveryRatio = 0.4f, targetLength = 50),
        )

        // librarySlots = 50 * (1 - 0.4) = 30; pool = 50; shortfall fills to 50.
        assertEquals(50, result.size)
    }

    @Test fun `shortfall fill does NOT trigger at discoveryRatio = 0_5`() = runTest {
        coEvery { trackDao.getAllDownloaded() } returns (1L..50L).map { stubTrack(it) }

        val result = generator.generate(
            stubRecipe(discoveryRatio = 0.5f, targetLength = 50),
        )

        // librarySlots = 50 * (1 - 0.5) = 25; no shortfall fill.
        assertEquals(25, result.size)
    }

    @Test fun `shortfall fill does NOT trigger at discoveryRatio = 0_85`() = runTest {
        coEvery { trackDao.getAllDownloaded() } returns (1L..50L).map { stubTrack(it) }

        val result = generator.generate(
            stubRecipe(discoveryRatio = 0.85f, targetLength = 40),
        )

        // librarySlots = 40 - 34 recommendations = 6 (MixSlots; the old
        // truncation of 40 * (1f - 0.85f) = 5.9999... made it 5). The key
        // assertion is that this is far below targetLength = 40 — i.e. no
        // shortfall fill triggered.
        assertEquals(6, result.size)
    }

    @Test fun `the shipped Daily Discover never reads the downloads`() = runTest {
        coEvery { trackDao.getAllDownloaded() } returns (1L..50L).map { stubTrack(it) }
        val daily = StashMixDefaults.ALL.single { it.name == "Daily Discover" }.copy(id = 1L)

        val result = generator.generate(daily)

        // All 40 places are recommendations (materializeMix fills them); none are downloads.
        assertTrue("expected empty, got ${result.size}", result.isEmpty())
        coVerify(exactly = 0) { trackDao.getAllDownloaded() }
    }

    @Test fun `pure discovery recipe at ratio = 1_0 returns empty library slice`() = runTest {
        coEvery { trackDao.getAllDownloaded() } returns (1L..50L).map { stubTrack(it) }

        val result = generator.generate(
            stubRecipe(discoveryRatio = 1.0f, targetLength = 50),
        )

        // librarySlots = 0; no shortfall fill (already gated by < 0.5).
        assertTrue("expected empty, got ${result.size}", result.isEmpty())
        // ...so the library is not even loaded, let alone scored.
        coVerify(exactly = 0) { trackDao.getAllDownloaded() }
    }

    /**
     * Scoring reads track tags in batches. One query per library track (and
     * per played track) was tens of thousands of queries per refresh on a
     * large library, repeated for every recipe.
     */
    @Test fun `tags are read in batched queries and not one per track`() = runTest {
        coEvery { trackDao.getAllDownloaded() } returns (1L..50L).map { stubTrack(it) }
        coEvery { listeningEventDao.getPlayCountsSinceWithLatest(any()) } returns listOf(
            TrackPlayCountWithLatest(trackId = 1L, plays = 3, latestPlayedAt = System.currentTimeMillis()),
        )
        coEvery { trackTagDao.getByTracks(any()) } answers {
            firstArg<List<Long>>().map { id ->
                TrackTagEntity(trackId = id, tag = "indie", weight = 50f, source = "TRACK", fetchedAt = 0L)
            }
        }

        val result = generator.generate(stubRecipe(discoveryRatio = 0.4f, targetLength = 50))

        assertEquals(50, result.size)
        coVerify(exactly = 0) { trackTagDao.getByTrack(any()) }
        // One for the user's tag vector, one for the candidate pool.
        coVerify(exactly = 2) { trackTagDao.getByTracks(any()) }
    }

    private fun stubRecipe(discoveryRatio: Float, targetLength: Int) = StashMixRecipeEntity(
        id = 1L,
        name = "Test",
        discoveryRatio = discoveryRatio,
        targetLength = targetLength,
        freshnessWindowDays = 0,
    )

    private fun stubTrack(id: Long) = TrackEntity(
        id = id,
        title = "Track $id",
        artist = "Artist $id",
        canonicalTitle = "track $id",
        canonicalArtist = "artist $id",
        isDownloaded = true,
    )
}
