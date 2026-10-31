package com.stash.core.data.weblink.handoff

import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.repository.MusicRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A hand-off song whose canonical words are blank ("(Intro)", a credit-only title, no artist) is
 * never looked up by them: a blank key names no song, and ("", "") is every never-keyed local import.
 */
class HandoffSongMatcherBlankKeyTest {

    private val trackDao = mockk<TrackDao>(relaxed = true)
    private val music = mockk<MusicRepository>(relaxed = true)
    private val matcher = HandoffSongMatcher(trackDao, music, database = mockk(relaxed = true))

    @Test fun `a blank key skips the words lookup and persists the song exactly`() = runTest {
        coEvery { music.ensureExactTrackPersisted(any()) } returns 5L
        coEvery { trackDao.getById(5L) } returns TrackEntity(id = 5L, title = "(Intro)", artist = "Artist")

        val row = matcher.match(WireSong(title = "(Intro)", artist = "Artist"))

        assertEquals(5L, row?.id)
        coVerify(exactly = 0) { trackDao.findAllByCanonicalIdentity(any(), any()) }
    }

    @Test fun `a blank key is found again by its exact words, never made twice`() = runTest {
        coEvery { trackDao.findAllByExactWords("(Intro)", "Artist") } returns listOf(TrackEntity(id = 9L, title = "(Intro)", artist = "Artist"))

        val row = matcher.match(WireSong(title = "(Intro)", artist = "Artist"))

        assertEquals(9L, row?.id)
        coVerify(exactly = 0) { music.ensureExactTrackPersisted(any()) }
        coVerify(exactly = 0) { trackDao.findAllByCanonicalIdentity(any(), any()) }
    }

    @Test fun `a blank key with an id is never matched by words, so two different Intros stay two songs`() = runTest {
        coEvery { trackDao.findBySpotifyUri(any()) } returns null
        coEvery { trackDao.findByIsrc(any()) } returns null
        coEvery { trackDao.findByYoutubeId(any()) } returns null
        coEvery { trackDao.findAllByExactWords("(Intro)", "Artist") } returns listOf(TrackEntity(id = 9L, title = "(Intro)", artist = "Artist"))
        coEvery { music.ensureExactTrackPersisted(any()) } returns 11L
        coEvery { trackDao.getById(11L) } returns TrackEntity(id = 11L, title = "(Intro)", artist = "Artist")

        val row = matcher.match(WireSong(title = "(Intro)", artist = "Artist", spotifyId = "0123456789abcdefghij11"))

        assertEquals(11L, row?.id)
        coVerify(exactly = 0) { trackDao.findAllByExactWords(any(), any()) }
    }

    @Test fun `a song with words is still looked up by them`() = runTest {
        coEvery { trackDao.findAllByCanonicalIdentity("intro", "artist") } returns
            listOf(TrackEntity(id = 7L, title = "Intro", artist = "Artist"))

        val row = matcher.match(WireSong(title = "Intro", artist = "Artist"))

        assertEquals(7L, row?.id)
    }
}
