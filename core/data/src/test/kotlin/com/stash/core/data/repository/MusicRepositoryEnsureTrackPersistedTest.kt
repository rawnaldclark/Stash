package com.stash.core.data.repository

import android.database.sqlite.SQLiteConstraintException
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.Track
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit (REPLACE→ABORT): with `TrackDao.insert` now ABORTing on a natural-key
 * conflict instead of REPLACE-wiping, [MusicRepositoryImpl.ensureTrackPersisted]
 * must dedup by EVERY unique key before inserting — otherwise a Spotify track
 * whose canonical identity drifted (feat./remaster tags) but whose spotify_uri
 * still matches an existing row would throw at the insert. Previously it
 * checked youtube_id + canonical but not spotify_uri.
 */
class MusicRepositoryEnsureTrackPersistedTest {

    @Test fun `SoundCloud uses its upload id before a stale positive room id`() = runTest {
        val dao = mockk<TrackDao>(relaxed = true)
        coEvery { dao.findBySoundCloudId("123") } returns TrackEntity(id = 42, title = "Song", artist = "Artist", soundCloudId = "123")
        assertEquals(42L, buildRepo(dao).ensureTrackPersisted(Track(id = 99, title = "Song", artist = "Artist", soundCloudId = "123")))
        coVerify(exactly = 0) { dao.getById(99) }
        coVerify(exactly = 0) { dao.findAllByCanonicalIdentity(any(), any()) }
    }

    @Test fun `missing SoundCloud row gets an exact stub instead of a title match`() = runTest {
        val dao = mockk<TrackDao>(relaxed = true)
        coEvery { dao.findBySoundCloudId("123") } returns null
        coEvery { dao.insert(any()) } returns 88L
        val guard = mockk<BlocklistGuard> { coEvery { isKeyBlocked(any(), any()) } returns false }
        assertEquals(88L, buildRepo(dao, guard).ensureTrackPersisted(Track(title = "Song", artist = "Artist", soundCloudId = "123")))
        coVerify { dao.insert(match { it.soundCloudId == "123" && it.source == MusicSource.SOUNDCLOUD && it.youtubeId == null && it.spotifyUri == null }) }
        coVerify(exactly = 0) { dao.findAllByCanonicalIdentity(any(), any()) }
    }

    @Test fun `concurrent saves adopt the winning SoundCloud upload row`() = runTest {
        val dao = mockk<TrackDao>(relaxed = true)
        coEvery { dao.findBySoundCloudId("123") } returnsMany listOf(null,
            TrackEntity(id = 42, title = "Song", artist = "Artist", soundCloudId = "123"))
        coEvery { dao.insert(any()) } throws SQLiteConstraintException("duplicate")
        val guard = mockk<BlocklistGuard> { coEvery { isKeyBlocked(any(), any()) } returns false }
        assertEquals(42L, buildRepo(dao, guard).ensureTrackPersisted(Track(title = "Song", artist = "Artist", soundCloudId = "123")))
    }

    @Test fun `ensureTrackPersisted returns the existing id on a spotify_uri match and does not insert`() = runTest {
        val trackDao = mockk<TrackDao>(relaxed = true)
        // No id, no youtube match, no canonical match — only the spotify_uri hits.
        coEvery { trackDao.findByYoutubeId(any()) } returns null
        coEvery { trackDao.findAllByCanonicalIdentity(any(), any()) } returns emptyList()
        coEvery { trackDao.findBySpotifyUri("spotify:track:xyz") } returns
            TrackEntity(
                id = 55L,
                title = "Song (2011 Remaster)",
                artist = "Artist",
                spotifyUri = "spotify:track:xyz",
                canonicalTitle = "song 2011 remaster",
                canonicalArtist = "artist",
                source = MusicSource.SPOTIFY,
            )

        val repo = buildRepo(trackDao)
        val id = repo.ensureTrackPersisted(
            Track(title = "Song", artist = "Artist", spotifyUri = "spotify:track:xyz"),
        )

        assertEquals(55L, id)
        coVerify(exactly = 0) { trackDao.insert(any()) }
    }

    @Test fun `an id-matched track gets the incoming isrc backfilled`() = runTest {
        val trackDao = mockk<TrackDao>(relaxed = true)
        coEvery { trackDao.findByYoutubeId("yt1") } returns
            TrackEntity(id = 7L, title = "Song", artist = "Artist", youtubeId = "yt1", source = MusicSource.YOUTUBE)
        val repo = buildRepo(trackDao)
        repo.ensureTrackPersisted(Track(title = "Song", artist = "Artist", youtubeId = "yt1", isrc = "USABC1234567", album = "LP"))
        coVerify { trackDao.backfillIsrcIfMissing(7L, "USABC1234567") }
    }

    @Test fun `a fuzzy title-artist match never takes the incoming isrc`() = runTest {
        val trackDao = mockk<TrackDao>(relaxed = true)
        coEvery { trackDao.findAllByCanonicalIdentity("song", "artist") } returns
            listOf(TrackEntity(id = 8L, title = "Song", artist = "Artist", source = MusicSource.SPOTIFY))
        val repo = buildRepo(trackDao)
        val id = repo.ensureTrackPersisted(Track(title = "Song", artist = "Artist", isrc = "USABC1234567"))
        assertEquals(8L, id)
        coVerify(exactly = 0) { trackDao.backfillIsrcIfMissing(any(), any()) }
    }

    @Test fun `a words match of another version or another video is not taken`() = runTest {
        val trackDao = mockk<TrackDao>(relaxed = true)
        coEvery { trackDao.findByYoutubeId(any()) } returns null
        coEvery { trackDao.findBySpotifyUri(any()) } returns null
        // Loose words: "Song (Live)" and another upload of "Song" both key as song|artist.
        coEvery { trackDao.findAllByCanonicalIdentity("song", "artist") } returns listOf(
            TrackEntity(id = 3L, title = "Song (Live)", artist = "Artist", source = MusicSource.YOUTUBE),
            TrackEntity(id = 4L, title = "Song", artist = "Artist", youtubeId = "otherVideo1", source = MusicSource.YOUTUBE),
        )
        coEvery { trackDao.insert(any()) } returns 9L

        val id = buildRepo(trackDao).ensureTrackPersisted(Track(title = "Song", artist = "Artist", youtubeId = "thisVideo01"))

        assertEquals("neither the live row nor the other video's row is this song's", 9L, id)
        coVerify(exactly = 1) { trackDao.insert(match { it.canonicalTitle == "song" && it.canonicalArtist == "artist" }) }
    }

    @Test fun `a saved song whose loose key is blocked keeps its strict words`() = runTest {
        // The user blocked the studio "Song"; the blocklist key is loose, so it covers "Song (Live)" too.
        val trackDao = mockk<TrackDao>(relaxed = true)
        coEvery { trackDao.findByYoutubeId(any()) } returns null
        coEvery { trackDao.findAllByCanonicalIdentity(any(), any()) } returns emptyList()
        coEvery { trackDao.insert(any()) } returns 9L
        val guard = mockk<BlocklistGuard>(relaxed = true)
        coEvery { guard.isKeyBlocked("song", "a, b") } returns true

        buildRepo(trackDao, guard).ensureTrackPersisted(Track(title = "Song (Live)", artist = "B & A", youtubeId = "thisVideo01"))

        coVerify(exactly = 1) { trackDao.insert(match { it.canonicalTitle == "song live" && it.canonicalArtist == "b a" }) }
    }

    @Test fun `a saved song with nothing blocked is stored under the loose words`() = runTest {
        val trackDao = mockk<TrackDao>(relaxed = true)
        coEvery { trackDao.findByYoutubeId(any()) } returns null
        coEvery { trackDao.findAllByCanonicalIdentity(any(), any()) } returns emptyList()
        coEvery { trackDao.insert(any()) } returns 9L
        val guard = mockk<BlocklistGuard>(relaxed = true)
        coEvery { guard.isKeyBlocked(any(), any()) } returns false

        buildRepo(trackDao, guard).ensureTrackPersisted(Track(title = "Song (Live)", artist = "B & A", youtubeId = "thisVideo01"))

        coVerify(exactly = 1) { trackDao.insert(match { it.canonicalTitle == "song" && it.canonicalArtist == "a, b" }) }
    }

    @Test fun `losing the insert race to a concurrent caller adopts the winner's row`() = runTest {
        val trackDao = mockk<TrackDao>(relaxed = true)
        val winner = TrackEntity(id = 91L, title = "Song", artist = "Artist", youtubeId = "yt1", source = MusicSource.YOUTUBE)
        // Our lookup misses; by the time we insert, the other caller's row exists.
        coEvery { trackDao.findByYoutubeId("yt1") } returnsMany listOf(null, winner)
        coEvery { trackDao.findBySpotifyUri(any()) } returns null
        coEvery { trackDao.findAllByCanonicalIdentity(any(), any()) } returns emptyList()
        coEvery { trackDao.insert(any()) } throws SQLiteConstraintException("UNIQUE constraint failed: tracks.youtube_id")

        val id = buildRepo(trackDao).ensureTrackPersisted(Track(title = "Song", artist = "Artist", youtubeId = "yt1"))

        assertEquals(91L, id)
    }

    @Test fun `a constraint failure with no winning row still throws`() = runTest {
        val trackDao = mockk<TrackDao>(relaxed = true)
        coEvery { trackDao.findByYoutubeId(any()) } returns null
        coEvery { trackDao.findBySpotifyUri(any()) } returns null
        coEvery { trackDao.findAllByCanonicalIdentity(any(), any()) } returns emptyList()
        coEvery { trackDao.insert(any()) } throws SQLiteConstraintException("NOT NULL constraint failed")

        val thrown = runCatching {
            buildRepo(trackDao).ensureTrackPersisted(Track(title = "Song", artist = "Artist", youtubeId = "yt1"))
        }.exceptionOrNull()

        assertTrue(thrown is SQLiteConstraintException)
    }

    @Test fun `a synthetic stream id equal to an unrelated row's id is not trusted`() = runTest {
        val trackDao = mockk<TrackDao>(relaxed = true)
        val videoId = "dQw4w9WgXcQ"
        val syntheticId = videoId.hashCode().toLong()
        assertTrue(syntheticId > 0L) // the case the old id fast path trusted
        coEvery { trackDao.getById(syntheticId) } returns
            TrackEntity(id = syntheticId, title = "Other Song", artist = "Other", youtubeId = "other", source = MusicSource.YOUTUBE)
        coEvery { trackDao.findByYoutubeId(videoId) } returns
            TrackEntity(id = 12L, title = "Song", artist = "Artist", youtubeId = videoId, source = MusicSource.YOUTUBE)

        val id = buildRepo(trackDao).ensureTrackPersisted(
            Track(id = syntheticId, title = "Song", artist = "Artist", youtubeId = videoId),
        )

        assertEquals(12L, id)
    }

    private fun buildRepo(
        trackDao: TrackDao,
        blocklistGuard: BlocklistGuard = mockk(relaxed = true),
    ): MusicRepositoryImpl = MusicRepositoryImpl(
        context = mockk(relaxed = true),
        trackDao = trackDao,
        playlistDao = mockk(relaxed = true),
        syncHistoryDao = mockk(relaxed = true),
        downloadQueueDao = mockk(relaxed = true),
        discoveryQueueDao = mockk(relaxed = true),
        blocklistGuard = blocklistGuard,
        trackMatcher = mockk(relaxed = true),
        stashMixRecipeDao = mockk(relaxed = true),
        downloadNetworkPreference = mockk(relaxed = true),
        streamingPreference = mockk(relaxed = true),
        libraryScopePreference = mockk(relaxed = true),
        localFileOps = mockk(relaxed = true),
        syncPreferencesManager = mockk(relaxed = true),
        singleTrackDownloadEnqueuer = mockk(relaxed = true),
        lastFmRecommendationSource = mockk(relaxed = true),
        sharedMixDao = mockk(relaxed = true),
        downloadJobs = com.stash.core.data.sync.DownloadJobRegistry(),
    )
}
