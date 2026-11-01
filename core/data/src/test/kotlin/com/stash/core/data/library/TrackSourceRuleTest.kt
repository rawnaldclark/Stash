package com.stash.core.data.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.common.TrackCanonical
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The source dot is honest: a YouTube stream is never a Spotify song ([TrackSourceRule]), and the
 * one-time data fix ([com.stash.core.data.db.dao.TrackDao.relabelYouTubeRowsClaimingSpotify])
 * relabels only the rows that lie, on a real `tracks` table, leaving every row with a Spotify id
 * alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TrackSourceRuleTest {

    private lateinit var db: StashDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() = db.close()

    // ── The rule ───────────────────────────────────────────────────────────

    @Test fun `a Spotify claim with a video id and no Spotify id is a YouTube song`() {
        assertEquals(MusicSource.YOUTUBE, TrackSourceRule.honestSource(MusicSource.SPOTIFY, null, "Ikn6Z2fxMs4"))
        assertEquals(MusicSource.YOUTUBE, TrackSourceRule.honestSource(MusicSource.SPOTIFY, " ", "Ikn6Z2fxMs4"))
    }

    @Test fun `a Spotify song keeps its claim, downloaded under a video or not`() {
        assertEquals(MusicSource.SPOTIFY, TrackSourceRule.honestSource(MusicSource.SPOTIFY, "spotify:track:1", "Ikn6Z2fxMs4"))
        assertEquals(MusicSource.SPOTIFY, TrackSourceRule.honestSource(MusicSource.SPOTIFY, "spotify:track:1", null))
        // Neither id (a local file in a Spotify playlist): nothing contradicts the claim.
        assertEquals(MusicSource.SPOTIFY, TrackSourceRule.honestSource(MusicSource.SPOTIFY, null, null))
    }

    @Test fun `every other claim stands`() {
        for (claim in listOf(MusicSource.YOUTUBE, MusicSource.LOCAL, MusicSource.CLOUD, MusicSource.SOUNDCLOUD, MusicSource.BOTH)) {
            assertEquals(claim, TrackSourceRule.honestSource(claim, null, "Ikn6Z2fxMs4"))
            assertEquals(claim, TrackSourceRule.honestSource(claim, "spotify:track:1", null))
            assertEquals(claim, TrackSourceRule.honestSource(claim, null, null))
        }
    }

    @Test fun `with no claim a song is Spotify's only with a Spotify id`() {
        assertEquals(MusicSource.SPOTIFY, TrackSourceRule.honestSource(null, "spotify:track:1", "Ikn6Z2fxMs4"))
        assertEquals(MusicSource.YOUTUBE, TrackSourceRule.honestSource(null, null, "Ikn6Z2fxMs4"))
        assertEquals(MusicSource.YOUTUBE, TrackSourceRule.honestSource(null, null, null))
    }

    // ── The one-time data fix ──────────────────────────────────────────────

    @Test fun `the data fix relabels only YouTube rows saved as Spotify songs`() = runTest {
        val lie = row("Starting Over", MusicSource.SPOTIFY, youtubeId = "Ikn6Z2fxMs4", file = "/music/a.flac")
        val blankUri = row("Blank Uri", MusicSource.SPOTIFY, youtubeId = "video000002", spotifyUri = "")
        val spotifyWithVideo = row("Synced", MusicSource.SPOTIFY, youtubeId = "video000003", spotifyUri = "spotify:track:3")
        val spotifyOnly = row("Not Matched Yet", MusicSource.SPOTIFY, spotifyUri = "spotify:track:4")
        val noIds = row("No Ids", MusicSource.SPOTIFY)
        val youtube = row("Red", MusicSource.YOUTUBE, youtubeId = "video000005")
        val local = row("Imported", MusicSource.LOCAL, file = "/music/b.mp3")
        val inSpotifyPlaylist = row("Kept Spotify", MusicSource.SPOTIFY, youtubeId = "video000006")
        val pid = db.playlistDao().insert(
            PlaylistEntity(name = "Discover Weekly", source = MusicSource.SPOTIFY, sourceId = "spotify:playlist:dw", type = PlaylistType.CUSTOM),
        )
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = pid, trackId = inSpotifyPlaylist, position = 0))

        assertEquals(2, db.trackDao().relabelYouTubeRowsClaimingSpotify())

        assertEquals(MusicSource.YOUTUBE, source(lie))
        assertEquals(MusicSource.YOUTUBE, source(blankUri))
        assertEquals("a row with a Spotify id is never touched", MusicSource.SPOTIFY, source(spotifyWithVideo))
        assertEquals("a row with a Spotify id is never touched", MusicSource.SPOTIFY, source(spotifyOnly))
        assertEquals("nothing contradicts a row with no ids", MusicSource.SPOTIFY, source(noIds))
        assertEquals("the Spotify sync downloads its playlists' rows by source", MusicSource.SPOTIFY, source(inSpotifyPlaylist))
        assertEquals(MusicSource.YOUTUBE, source(youtube))
        assertEquals(MusicSource.LOCAL, source(local))

        // Only the label changed: the downloaded file and the ids are as they were.
        val fixed = db.trackDao().getById(lie)!!
        assertEquals("/music/a.flac", fixed.filePath)
        assertEquals("Ikn6Z2fxMs4", fixed.youtubeId)

        assertEquals("idempotent: a second run finds nothing", 0, db.trackDao().relabelYouTubeRowsClaimingSpotify())
        assertEquals("nothing deleted", 8, db.trackDao().getAllForIntegrityScan().size)
    }

    private suspend fun row(
        title: String,
        source: MusicSource,
        youtubeId: String? = null,
        spotifyUri: String? = null,
        file: String? = null,
    ): Long = db.trackDao().insert(
        TrackEntity(
            title = title, artist = "LSD and the Search for God", source = source,
            youtubeId = youtubeId, spotifyUri = spotifyUri, filePath = file, isDownloaded = file != null,
            canonicalTitle = TrackCanonical.title(title), canonicalArtist = TrackCanonical.artist("LSD and the Search for God"),
        ),
    )

    private suspend fun source(id: Long): MusicSource = db.trackDao().getById(id)!!.source
}
