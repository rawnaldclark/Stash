package com.stash.core.data.mix

import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.DiscoveryQueueDao
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.dao.TrackSkipEventDao
import com.stash.core.data.db.dao.TrackTagDao
import com.stash.core.data.db.entity.DiscoveryQueueEntity
import com.stash.core.data.db.entity.StashMixRecipeEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.record.MixTaste
import com.stash.core.model.weblink.SongKey
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MixGenerator] with Your record's taste (stash-player `discover/mix.ts`): the songs it keeps out leave the library pool and the
 * discovery queue (matched by song key, remaster marks dropped), and a song's affinity is the larger of its own and `0.6 · its
 * weight`, even in a pool with no other signal. With no taste every existing suite runs unchanged.
 */
class MixGeneratorTasteTest {
    private val trackDao: TrackDao = mockk()
    private val trackTagDao: TrackTagDao = mockk(relaxed = true)
    private val listeningEventDao: ListeningEventDao = mockk(relaxed = true)
    private val discoveryQueueDao: DiscoveryQueueDao = mockk(relaxed = true)
    private val blocklistGuard: BlocklistGuard = mockk(relaxed = true)
    private val trackSkipEventDao: TrackSkipEventDao = mockk(relaxed = true)
    private val generator = MixGenerator(trackDao, trackTagDao, listeningEventDao, discoveryQueueDao, blocklistGuard, trackSkipEventDao)

    private fun taste(avoid: Set<String> = emptySet(), weights: Map<String, Double> = emptyMap()) = MixTaste(listOf("Loved"), avoid, weights)

    private fun library(tracks: List<TrackEntity>, plays: List<ListeningEventDao.TrackPlayCountWithLatest> = emptyList()) {
        coEvery { trackDao.getAllDownloaded() } returns tracks
        coEvery { listeningEventDao.getPlayCountsSinceWithLatest(any()) } returns plays
        coEvery { listeningEventDao.getTrackIdsPlayedSince(any()) } returns emptyList()
        coEvery { trackTagDao.getByTracks(any()) } returns emptyList()
    }

    @Test fun `the songs the taste keeps out never enter the pool, remastered or not`() = runTest {
        val tracks = (1L..5L).map { track(it) } + track(6L, title = "Track 3 - 2011 Remaster", artist = "Artist 3")
        library(tracks)
        val avoid = setOf(SongKey.songKey("Track 2", "Artist 2"), SongKey.songKey("Track 3", "Artist 3"))

        val result = generator.generate(recipe(), taste = taste(avoid = avoid))

        assertEquals(setOf(1L, 4L, 5L), result.map { it.id }.toSet())
        // with no taste, the whole pool as before
        assertEquals(6, generator.generate(recipe()).size)
    }

    @Test fun `affinity is the larger of a song's own and 0,6 of its weight, never their sum`() = runTest {
        val tracks = (1L..4L).map { track(it) }
        // Track 1 played once, track 3 three times (the most): own affinities 0.5 and 1.0 (ln 2 / ln 4), just played
        val now = System.currentTimeMillis()
        library(tracks, plays = listOf(playCount(1L, 1, now), playCount(3L, 3, now)))
        val w = mapOf(SongKey.songKey("Track 1", "Artist 1") to 0.5 / 0.6, SongKey.songKey("Track 2", "Artist 2") to 1.0)

        val aff = generator.buildAffinityMap(tracks, taste(weights = w))

        assertEquals(0.5f, aff.getValue(1L), 1e-4f) // max(0.5, 0.5), not 1.0
        assertEquals(0.6f, aff.getValue(2L), 1e-6f) // the taste alone
        assertEquals(1.0f, aff.getValue(3L), 1e-4f)
        assertTrue(4L !in aff)
        // no taste: own only
        val plain = generator.buildAffinityMap(tracks)
        assertTrue(2L !in plain)
    }

    @Test fun `a pool with no other signal still gets the taste's (no early out), and the weighted song leads`() = runTest {
        val tracks = (1L..6L).map { track(it) }
        library(tracks)

        assertTrue(generator.buildAffinityMap(tracks).isEmpty())
        val aff = generator.buildAffinityMap(tracks, taste(weights = mapOf(SongKey.songKey("Track 4", "Artist 4") to 1.0)))
        assertEquals(mapOf(4L to 0.6f), aff)

        // 0.6 x 0.40 = 0.24 of score, more than the jitter's 0.10 can undo
        val first = generator.generate(recipe(targetLength = 1), taste = taste(weights = mapOf(SongKey.songKey("Track 4", "Artist 4") to 1.0)))
        assertEquals(listOf(4L), first.map { it.id })
    }

    @Test fun `the discovery queue never takes a song the taste keeps out`() = runTest {
        coEvery { blocklistGuard.isBlocked(any(), any(), any(), any()) } returns false
        coEvery { discoveryQueueDao.existsForRecipeSince(any(), any(), any(), any()) } returns false
        val queued = slot<List<DiscoveryQueueEntity>>()
        coEvery { discoveryQueueDao.insertAllIfNew(capture(queued)) } returns Unit
        val cands = listOf(
            MixGenerator.DiscoveryCandidate("Artist Y", "Never Again (Remastered)", "Seed"),
            MixGenerator.DiscoveryCandidate("Artist Y", "Fine", "Seed"),
        )

        generator.queueDiscoveryCandidates(recipe(), cands, taste(avoid = setOf(SongKey.songKey("Never Again", "Artist Y"))))
        assertEquals(listOf("Fine"), queued.captured.map { it.title })

        generator.queueDiscoveryCandidates(recipe(), cands)
        assertEquals(listOf("Never Again (Remastered)", "Fine"), queued.captured.map { it.title })
        coVerify(exactly = 2) { discoveryQueueDao.insertAllIfNew(any()) }
    }

    private fun recipe(targetLength: Int = 10) = StashMixRecipeEntity(id = 1L, name = "Test", discoveryRatio = 0.0f, targetLength = targetLength, freshnessWindowDays = 0)

    private fun track(id: Long, title: String = "Track $id", artist: String = "Artist $id") =
        TrackEntity(id = id, title = title, artist = artist, canonicalTitle = title.lowercase(), canonicalArtist = artist.lowercase(), isDownloaded = true)

    private fun playCount(id: Long, plays: Int, at: Long) = ListeningEventDao.TrackPlayCountWithLatest(trackId = id, plays = plays, latestPlayedAt = at)
}
