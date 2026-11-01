package com.stash.core.data.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.common.TrackCanonical
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [SameSongRule] against a real `tracks` table: which row an incoming song may take by its loose
 * canonical words. One key holds every version and every upload of a song, so the rule, not the
 * key, decides.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SameSongRuleTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: TrackDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.trackDao()
    }

    @After fun tearDown() = db.close()

    /** A row as every writer now stores it: canonical words by the one loose rule. */
    private suspend fun row(
        title: String,
        artist: String = "A & B",
        youtubeId: String? = null,
        spotifyUri: String? = null,
        file: String? = null,
    ): Long = dao.insert(
        TrackEntity(
            title = title, artist = artist, youtubeId = youtubeId, spotifyUri = spotifyUri,
            filePath = file, isDownloaded = file != null,
            canonicalTitle = TrackCanonical.title(title), canonicalArtist = TrackCanonical.artist(artist),
        ),
    )

    private suspend fun find(title: String, artist: String = "B & A", youtubeId: String? = null, spotifyUri: String? = null, forDownload: Boolean = false) =
        dao.findReusableByWords(title, artist, youtubeId, spotifyUri, forDownload)?.id

    @Test fun `a synced song is found from Search across punctuation, credits and artist order`() = runTest {
        // The owner's example: written by the sync, looked up by a Search save.
        val synced = row("Don't Stop (feat. X)", artist = "A & B", spotifyUri = "spotify:track:1")
        assertEquals(synced, find("Don't Stop", artist = "B, A", youtubeId = "video000001"))
    }

    @Test fun `another version stays apart`() = runTest {
        row("Song")
        assertNull(find("Song (Live)"))
        assertNull(find("Song (Remix)"))
        assertNull(find("Song (Acoustic)"))
        assertNull(find("Song (Sped Up)"))
    }

    @Test fun `presentation tags, credits, remasters and editions are the same version`() = runTest {
        val song = row("Song")
        assertEquals(song, find("Song (Official Video)"))
        assertEquals(song, find("Song [Lyrics]"))
        assertEquals(song, find("Song ft. X"))
        assertEquals(song, find("Song [2011 Remaster]"))
        assertEquals(song, find("Song (Clean)"))
        assertEquals(song, find("Song (Single Version)"))
    }

    @Test fun `real titles from the two services meet`() = runTest {
        // Spotify's credit vs YouTube's bare title, and YouTube's presentation tags vs the bare title.
        val stay = row("STAY (with Justin Bieber)", artist = "The Kid LAROI & Justin Bieber", spotifyUri = "spotify:track:stay")
        assertEquals(stay, find("STAY", artist = "The Kid LAROI & Justin Bieber", youtubeId = "video000001"))

        val prblms = row("PRBLMS", artist = "6LACK", youtubeId = "video000002")
        assertEquals(prblms, find("PRBLMS [Official Music Video]", artist = "6LACK"))

        val sun = row("When the Sun Hits", artist = "Slowdive", spotifyUri = "spotify:track:sun")
        assertEquals(sun, find("When the Sun Hits (Audio)", artist = "Slowdive", youtubeId = "video000003"))
    }

    @Test fun `a conflicting video or Spotify id stays apart, a missing one never conflicts`() = runTest {
        val video = row("Song", youtubeId = "video000001")
        assertNull(find("Song", youtubeId = "video000002"))
        assertEquals(video, find("Song", youtubeId = "video000001"))
        assertEquals("an incoming song with no id never conflicts", video, find("Song"))

        val spotify = row("Other", spotifyUri = "spotify:track:1")
        assertNull(find("Other", spotifyUri = "spotify:track:2"))
        assertEquals(spotify, find("Other", youtubeId = "video000003"))
    }

    @Test fun `a download never takes a row that holds a file, a stub may`() = runTest {
        val withFile = row("Song", file = "/m/song.flac")
        assertNull(find("Song", youtubeId = "video000001", forDownload = true))
        assertEquals("not downloading: the library's copy is the song", withFile, find("Song", forDownload = false))

        val stub = row("Song (feat. C)")
        assertEquals(stub, find("Song", youtubeId = "video000001", forDownload = true))
    }

    @Test fun `every row that is the song is listed, with or without its file`() = runTest {
        val stub = row("Song (feat. X)", spotifyUri = "spotify:track:1")
        row("Song (Live)", file = "/m/live.opus")
        row("Song", youtubeId = "video000009", file = "/m/other-video.opus")
        val copy = row("Song [Official Audio]", file = "/m/song.flac")

        val same = dao.findSameSongByWords("Song", "B & A", youtubeId = "video000001", spotifyUri = null)

        assertEquals(listOf(stub, copy), same.map { it.id })
        assertEquals(listOf(false, true), same.map(SameSongRule::holdsFile))
    }

    @Test fun `the oldest row the rule allows wins, not the oldest by words`() = runTest {
        row("Song (Live)")
        row("Song", youtubeId = "video000009")
        val allowed = row("Song (Official Audio)")
        row("Song")
        assertEquals(allowed, find("Song", youtubeId = "video000001"))
    }

    @Test fun `a title or artist with no canonical words matches nothing`() = runTest {
        row("(Intro)")
        assertNull("a bracket-only title has a blank key", find("(Intro)"))
        row("Song", artist = "")
        assertNull(find("Song", artist = " "))
    }

    @Test fun `a mid-word ft that the loose rule cuts does not merge two songs`() = runTest {
        // TrackCanonical.title("Lift Me Up") == "li" == TrackCanonical.title("Lift Off").
        row("Lift Me Up")
        assertNull(find("Lift Off"))
    }

    // ── findLibraryCopy: the one "already on your phone" check every download path shares ──

    /** A row with a length, for the copy checks (the rows above have none). */
    private suspend fun copyRow(
        title: String,
        lengthMs: Long,
        youtubeId: String? = null,
        spotifyUri: String? = null,
        file: String? = null,
        soundCloudId: String? = null,
    ): Long = dao.insert(
        TrackEntity(
            title = title, artist = "A & B", durationMs = lengthMs, youtubeId = youtubeId, spotifyUri = spotifyUri,
            soundCloudId = soundCloudId, filePath = file, isDownloaded = file != null,
            canonicalTitle = TrackCanonical.title(title), canonicalArtist = TrackCanonical.artist("A & B"),
        ),
    )

    private suspend fun copyOf(title: String, lengthMs: Long, youtubeId: String? = null, soundCloudId: String? = null, exclude: Long? = null) =
        dao.findLibraryCopy(title, "B & A", lengthMs, youtubeId = youtubeId, soundCloudId = soundCloudId, excludeTrackId = exclude)?.id

    @Test fun `a song downloaded under another video, or from Spotify, is the phone's copy, ids don't count`() = runTest {
        val copy = copyRow("Song", 304_000L, youtubeId = "video000001", spotifyUri = "spotify:track:1", file = "/m/song.flac")
        // Another upload of the song, from a YouTube playlist: its row could never take the copy's (ids disagree),
        // but its audio is the same song, so it is not downloaded again.
        assertEquals(copy, copyOf("Song", 305_000L, youtubeId = "video000002"))
        // An id-less discovery stub of the song.
        assertEquals(copy, copyOf("Song (Official Audio)", 304_000L))
    }

    @Test fun `the same video's row with a file is the copy, whatever its words`() = runTest {
        val sameVideo = copyRow("Song - Remastered 2011 (Live at the BBC)", 1_000L, youtubeId = "video000001", file = "/m/x.opus")
        assertEquals(sameVideo, copyOf("Song", 300_000L, youtubeId = "video000001"))
    }

    @Test fun `a row without a file is no copy`() = runTest {
        copyRow("Song", 304_000L, youtubeId = "video000001")
        assertNull(copyOf("Song", 304_000L))
        assertNull(copyOf("Song", 304_000L, youtubeId = "video000001"))
    }

    @Test fun `another version or a clearly different length is another song`() = runTest {
        copyRow("Song", 180_000L, file = "/m/song.flac")
        assertNull("live is another version", copyOf("Song (Live)", 180_000L))
        assertNull("8 minutes is not the 3-minute song", copyOf("Song", 480_000L))
        assertEquals("within 10 s is the song", true, copyOf("Song", 189_000L) != null)
    }

    @Test fun `lengths agree within 10 s or 5 percent, or when either is unknown`() {
        assertEquals(true, SameSongRule.lengthsAgree(304_000L, 314_000L))
        assertEquals(false, SameSongRule.lengthsAgree(180_000L, 190_001L))
        assertEquals(true, SameSongRule.lengthsAgree(600_000L, 629_000L)) // 5 % of 10 min = 30 s
        assertEquals(true, SameSongRule.lengthsAgree(0L, 629_000L))
        assertEquals(true, SameSongRule.lengthsAgree(300_000L, 0L))
    }

    @Test fun `a song is never its own copy`() = runTest {
        val kept = copyRow("Song", 304_000L, file = "/m/song.flac")
        assertNull(copyOf("Song", 304_000L, exclude = kept))
    }

    @Test fun `a SoundCloud upload is never matched by words`() = runTest {
        copyRow("Song", 304_000L, file = "/m/song.flac")
        assertNull(copyOf("Song", 304_000L, soundCloudId = "123"))
    }

    @Test fun `a downloaded SoundCloud upload is no copy of a song by words`() = runTest {
        copyRow("Song", 304_000L, soundCloudId = "456", file = "/m/sc.mp3")
        assertNull(copyOf("Song", 304_000L))
    }
}
