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
import com.stash.core.data.db.entity.StashMixRecipeEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmCredentials
import com.stash.core.data.lastfm.LastFmSessionPreference
import com.stash.core.data.mix.MixGenerator
import com.stash.core.data.mix.MixSeedGenerator
import com.stash.core.data.mix.MixSeedStrategy
import com.stash.core.data.mix.TagPoolBuilder
import com.stash.core.data.prefs.DownloadNetworkPreference
import com.stash.core.data.record.MixTaste
import com.stash.core.data.record.RecordMixTaste
import com.stash.core.data.sync.TrackMatcher
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.weblink.SongKey
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Daily Discover with Your record's taste (stash-player `discover/mix.ts`, PHASE5 §4): the taste read once a run and handed to
 * the generator and the discovery queue; the seed blend for ARTIST_SIMILAR (own seeds, then the taste's artists keeping
 * `max(3, 8 − own)` places; no own seeds: the taste alone, before the library's top artists); the finds it keeps out never
 * linked. With no taste (none, or the switch off) the run is the one it always was.
 */
class StashMixRefreshWorkerTasteTest {
    private val appContext: Context = mockk(relaxed = true)
    private val recipeDao: StashMixRecipeDao = mockk(relaxed = true)
    private val playlistDao: PlaylistDao = mockk(relaxed = true)
    private val discoveryQueueDao: DiscoveryQueueDao = mockk(relaxed = true)
    private val listeningEventDao: ListeningEventDao = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val mixGenerator: MixGenerator = mockk(relaxed = true)
    private val seedGenerator: MixSeedGenerator = mockk()
    private val lastFmApiClient: LastFmApiClient = mockk(relaxed = true)
    private val lastFmCredentials: LastFmCredentials = mockk { coEvery { isConfigured } returns true }
    private val sessionPreference: LastFmSessionPreference = mockk { coEvery { session } returns flowOf(null) }
    private val blocklistGuard: BlocklistGuard = mockk { coEvery { isBlockedByTrackId(any()) } returns false }
    private val trackSkipEventDao: TrackSkipEventDao = mockk(relaxed = true)
    private val trackMatcher: TrackMatcher = mockk {
        coEvery { canonicalArtist(any()) } answers { firstArg<String>().lowercase() }
        coEvery { canonicalTitle(any()) } answers { firstArg<String>().lowercase() }
    }
    private val downloadNetworkPreference: DownloadNetworkPreference = mockk(relaxed = true)
    private val tagPoolBuilder = TagPoolBuilder(lastFmApiClient)

    private val recipe = StashMixRecipeEntity(id = 1L, name = "Daily Discover", discoveryRatio = 0.85f, targetLength = 40, seedStrategy = "ARTIST_SIMILAR", isBuiltin = true)

    private fun newWorker(taste: MixTaste?): StashMixRefreshWorker {
        val params: WorkerParameters = mockk(relaxed = true) { coEvery { inputData } returns workDataOf(StashMixRefreshWorker.KEY_RECIPE_ID to 1L) }
        val record = object : RecordMixTaste {
            override suspend fun current(): MixTaste? = taste
        }
        return StashMixRefreshWorker(
            appContext, params, recipeDao, playlistDao, discoveryQueueDao, listeningEventDao, trackDao, mixGenerator, seedGenerator,
            lastFmApiClient, lastFmCredentials, sessionPreference, blocklistGuard, trackSkipEventDao, tagPoolBuilder, trackMatcher,
            downloadNetworkPreference, record,
        )
    }

    private fun taste(artists: List<String>, avoid: Set<String> = emptySet()) = MixTaste(artists, avoid, emptyMap())

    /** This phone's top artists: [recent] for the last 48 hours, [longRun] for 180 days. */
    private fun played(recent: List<String>, longRun: List<String>) {
        val now = System.currentTimeMillis()
        coEvery { listeningEventDao.getTopArtistsSince(any(), any()) } answers {
            val since = firstArg<Long>()
            (if (now - since < 3 * 86_400_000L) recent else longRun).map { ListeningEventDao.ArtistPlayCount(it, 1) }
        }
    }

    private fun seeds(): io.mockk.CapturingSlot<List<String>> {
        coEvery { recipeDao.getById(1L) } returns recipe
        coEvery { recipeDao.getActive() } returns listOf(recipe)
        val seeds = slot<List<String>>()
        coEvery { seedGenerator.generate(MixSeedStrategy.ARTIST_SIMILAR, capture(seeds), any(), any(), any()) } returns
            (1..30).map { MixGenerator.DiscoveryCandidate("New $it", "Song $it", "seed") }
        coEvery { seedGenerator.generate(MixSeedStrategy.TAG_GRAPH, any(), any(), any(), any()) } returns emptyList()
        return seeds
    }

    @Test fun `a busy listener's own seeds lead, the taste keeps three places`() = runTest {
        val own = (0 until 8).map { "Here $it" }
        played(recent = own, longRun = own)
        val seeds = seeds()

        newWorker(taste(listOf("T1", "T2", "T3", "T4", "T5"))).doWork()

        assertEquals(own.take(5) + listOf("T1", "T2", "T3"), seeds.captured)
    }

    @Test fun `a quiet listener's own seeds, the taste fills the rest, an artist played here not twice`() = runTest {
        played(recent = listOf("Recent"), longRun = listOf("Older"))
        val seeds = seeds()

        newWorker(taste(listOf("RECENT feat. Somebody", "T1", "T2", "T3", "T4", "T5", "T6", "T7"))).doWork()

        assertEquals(listOf("Recent", "Older", "T1", "T2", "T3", "T4", "T5", "T6"), seeds.captured)
    }

    @Test fun `no plays here - the taste alone seeds the first mix, before the library's top artists`() = runTest {
        played(recent = emptyList(), longRun = emptyList())
        coEvery { trackDao.getTopArtistsByTrackCount(any()) } returns listOf("Library One", "Library Two")
        val seeds = seeds()

        newWorker(taste(listOf("Loved Band", "Second Band"))).doWork()

        assertEquals(listOf("Loved Band", "Second Band"), seeds.captured)
        coVerify(exactly = 0) { trackDao.getTopArtistsByTrackCount(any()) }
    }

    @Test fun `no plays and a taste with no artists - the library's top artists, as before`() = runTest {
        played(recent = emptyList(), longRun = emptyList())
        coEvery { trackDao.getTopArtistsByTrackCount(any()) } returns listOf("Library One", "Library Two")
        val seeds = seeds()

        newWorker(taste(emptyList())).doWork()

        assertEquals(listOf("Library One", "Library Two"), seeds.captured)
    }

    @Test fun `no taste - exactly the seeds of before`() = runTest {
        played(recent = listOf("Recent"), longRun = listOf("Older"))
        val seeds = seeds()
        newWorker(null).doWork()
        assertEquals(listOf("Recent", "Older"), seeds.captured)

        played(recent = emptyList(), longRun = emptyList())
        coEvery { trackDao.getTopArtistsByTrackCount(any()) } returns listOf("Library One")
        newWorker(null).doWork()
        assertEquals(listOf("Library One"), seeds.captured)
    }

    @Test fun `the taste is read once and handed to the generator and the discovery queue`() = runTest {
        played(recent = listOf("Recent"), longRun = emptyList())
        seeds()
        val t = taste(listOf("T1"))
        val generated = slot<MixTaste>()
        coEvery { mixGenerator.generate(recipe, any(), capture(generated)) } returns emptyList()
        val queued = slot<MixTaste>()
        coEvery { mixGenerator.queueDiscoveryCandidates(recipe, any(), capture(queued)) } returns Unit

        newWorker(t).doWork()

        assertSame(t, generated.captured)
        assertSame(t, queued.captured)
    }

    @Test fun `a find the taste keeps out is never linked, without a taste every find is`() = runTest {
        coEvery { lastFmCredentials.isConfigured } returns false
        coEvery { recipeDao.getById(1L) } returns recipe
        coEvery { recipeDao.getActive() } returns listOf(recipe)
        coEvery { mixGenerator.generate(any(), any(), any()) } returns emptyList()
        coEvery { playlistDao.findBySourceId(any()) } returns null
        coEvery { playlistDao.getStreamableOrDoneTrackIdsForRecipe(1L) } returns listOf(11L, 12L, 13L)
        coEvery { trackDao.getByIds(any()) } returns listOf(track(11L, "Fine"), track(12L, "Never Again (Remastered)"), track(13L, "Also Fine"))
        val linked = slot<List<Long>>()
        coEvery { playlistDao.createMixWithMembership(any(), capture(linked), any()) } returns 100L

        newWorker(taste(emptyList(), avoid = setOf(SongKey.songKey("Never Again", "Artist Y")))).doWork()
        assertEquals(listOf(11L, 13L), linked.captured)

        newWorker(null).doWork()
        assertEquals(listOf(11L, 12L, 13L), linked.captured)
    }

    @Test fun `the switch off reads as no taste at all`() = runTest {
        val prefs = com.stash.core.data.prefs.RecordInMixesPreference.Fixed(on = false)
        val room = com.stash.core.data.record.RoomRecordMixTaste(mockk(relaxed = true), prefs)
        assertNull(room.current())
    }

    private fun track(id: Long, title: String) = TrackEntity(id = id, title = title, artist = "Artist Y", canonicalTitle = title.lowercase(), canonicalArtist = "artist y")

    @Suppress("unused")
    private fun playlist(id: Long) = PlaylistEntity(id = id, name = "Daily Discover", source = MusicSource.BOTH, sourceId = "stash_mix_1", type = PlaylistType.STASH_MIX, trackCount = 0, syncEnabled = true, isActive = true)
}
