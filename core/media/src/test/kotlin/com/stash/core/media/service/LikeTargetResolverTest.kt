package com.stash.core.media.service

import com.stash.core.model.scannedTrackId
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LikeTargetResolver], the row a heart on the media notification lands on: a real row by id, then
 * by video id, then by words under SameSongRule, else a new streamable row. That new row is the
 * user's own save, so under a blocked loose key it keeps its strict words and isn't hidden and swept.
 */
class LikeTargetResolverTest {

    private val trackDao = mockk<TrackDao>(relaxed = true)
    private val blocklistGuard = mockk<BlocklistGuard>(relaxed = true)
    private val resolver = LikeTargetResolver(trackDao, blocklistGuard)

    private suspend fun resolve(candidateId: Long = SYNTHETIC, youtubeId: String? = VIDEO, title: String = "Song (Live)") =
        resolver.resolve(candidateId, youtubeId, title, artist = "B & A", album = "", albumArtist = "")

    @Test fun `a real row id is used as is`() = runTest {
        coEvery { trackDao.getById(5L) } returns TrackEntity(id = 5L, title = "Song", artist = "A")

        assertEquals(5L, resolve(candidateId = 5L))
        coVerify(exactly = 0) { trackDao.insert(any()) }
    }

    @Test fun `a synthetic id resolves by the video id`() = runTest {
        coEvery { trackDao.getById(SYNTHETIC) } returns null
        coEvery { trackDao.findByYoutubeId(VIDEO) } returns TrackEntity(id = 12L, title = "Song (Live)", artist = "A", youtubeId = VIDEO)

        assertEquals(12L, resolve())
    }

    @Test fun `a new row under a blocked loose key keeps its strict words`() = runTest {
        // The user blocked the studio "Song"; this like is on the live take.
        coEvery { trackDao.getById(any()) } returns null
        coEvery { trackDao.findByYoutubeId(any()) } returns null
        coEvery { trackDao.findAllByCanonicalIdentity(any(), any()) } returns emptyList()
        coEvery { blocklistGuard.isKeyBlocked("song", "a, b") } returns true
        val inserted = slot<TrackEntity>()
        coEvery { trackDao.insert(capture(inserted)) } returns 77L

        assertEquals(77L, resolve())
        with(inserted.captured) {
            assertEquals("song live", canonicalTitle)
            assertEquals("b a", canonicalArtist)
            assertEquals(VIDEO, youtubeId)
            assertEquals(MusicSource.YOUTUBE, source)
            assertTrue(isStreamable)
        }
    }

    @Test fun `a new row with nothing blocked is stored under the loose words`() = runTest {
        coEvery { trackDao.getById(any()) } returns null
        coEvery { trackDao.findByYoutubeId(any()) } returns null
        coEvery { trackDao.findAllByCanonicalIdentity(any(), any()) } returns emptyList()
        coEvery { blocklistGuard.isKeyBlocked(any(), any()) } returns false
        val inserted = slot<TrackEntity>()
        coEvery { trackDao.insert(capture(inserted)) } returns 78L

        assertEquals(78L, resolve())
        assertEquals("song" to "a, b", inserted.captured.canonicalTitle to inserted.captured.canonicalArtist)
    }

    @Test fun `the same song found by its words takes the like, another version does not`() = runTest {
        coEvery { trackDao.getById(any()) } returns null
        coEvery { trackDao.findByYoutubeId(any()) } returns null
        coEvery { trackDao.findAllByCanonicalIdentity("song", "a, b") } returns listOf(
            TrackEntity(id = 3L, title = "Song", artist = "A & B"),
            TrackEntity(id = 4L, title = "Song (Live) [Official Video]", artist = "A & B"),
        )

        assertEquals(4L, resolve())
        coVerify(exactly = 0) { trackDao.insert(any()) }
    }

    @Test(expected = IllegalStateException::class)
    fun `nothing to resolve by fails rather than inserting a blank row`() = runTest {
        coEvery { trackDao.getById(any()) } returns null
        resolver.resolve(SYNTHETIC, youtubeId = null, title = "", artist = "", album = "", albumArtist = "")
    }

    private companion object {
        const val VIDEO = "dQw4w9WgXcQ"
        val SYNTHETIC = VIDEO.hashCode().toLong()
    }


    /** An All Songs file has no row: liking it must neither insert one nor land on a same-named song. */
    @Test(expected = IllegalStateException::class)
    fun `a scanned song is refused`() = runTest {
        coEvery { trackDao.findAllByCanonicalIdentity(any(), any()) } returns
            listOf(TrackEntity(id = 8L, title = "Song (Live)", artist = "B & A"))
        try {
            resolver.resolve(scannedTrackId(1L), youtubeId = null, title = "Song (Live)", artist = "B & A", album = "", albumArtist = "")
        } finally {
            coVerify(exactly = 0) { trackDao.insert(any()) }
        }
    }
}
