package com.stash.core.data.library.duplicates

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.dao.findByYoutubeIdOrAlias
import com.stash.core.data.db.dao.findBySpotifyUriOrAlias
import com.stash.core.data.db.dao.getByIdOrMerged
import com.stash.core.data.db.entity.DuplicateDismissalEntity
import com.stash.core.data.db.entity.LyricsEntity
import com.stash.core.data.db.entity.TrackAliasEntity
import com.stash.core.data.db.entity.TrackMergeLogEntity
import com.stash.core.model.DownloadStatus
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [TrackMerger] on a real database: everything that pointed at the merged-away row points at the
 * row that stays, nothing is listed twice, and what can't be merged is left exactly as it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TrackMergerTest {

    private lateinit var db: StashDatabase
    private lateinit var f: DuplicateFixtures
    private lateinit var merger: TrackMerger

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        f = DuplicateFixtures(db)
        merger = TrackMerger(db)
    }

    @After fun tearDown() = db.close()

    @Test fun `a merge moves memberships, plays, likes and history, and writes aliases`() = runBlocking {
        val kept = f.song(youtubeId = "keptVideo1", file = "/music/starting-over.flac", format = "flac", plays = 2)
        val gone = f.song(
            youtubeId = "otherVideo1", spotifyUri = "spotify:track:gone", source = MusicSource.SPOTIFY,
            plays = 4, likedAt = 1_234L, isrc = "USABC2600001",
        )
        val both = f.playlist("Both in it")
        f.member(both, kept, position = 3)
        f.member(both, gone, position = 7, userAdded = true)
        val onlyGone = f.playlist("Only the other", type = PlaylistType.STASH_MIX)
        f.member(onlyGone, gone, position = 1)
        f.play(gone); f.play(gone, at = 20_000L); f.skip(gone)
        val discovery = f.discovered(gone)
        db.lyricsDao().upsert(LyricsEntity(gone, "words", null, false, null, "lrclib", null, 0L))

        val merged = merger.merge(kept, gone, TrackMergeLogEntity.REASON_USER_MERGE, now = 99L)

        assertNotNull(merged)
        assertNull("the merged-away row is gone", f.row(gone))
        // Both in one playlist: one membership, the kept row's place, the user's mark kept.
        assertEquals(1, f.count("SELECT COUNT(*) FROM playlist_tracks WHERE playlist_id = $both"))
        val link = f.link(both, kept)!!
        assertEquals(3, link.position)
        assertTrue("added by the user, as one of the two was", link.locallyAdded)
        // Only the other: moved as it was.
        assertEquals(1, f.link(onlyGone, kept)!!.position)
        // Plays, skips, the mix's discovery row.
        assertEquals(2, f.count("SELECT COUNT(*) FROM listening_events WHERE track_id = $kept"))
        assertEquals(1, f.count("SELECT COUNT(*) FROM track_skip_events WHERE track_id = $kept"))
        assertEquals(kept, f.count("SELECT track_id FROM discovery_queue WHERE id = $discovery").toLong())
        assertNotNull("lyrics came along", db.lyricsDao().get(kept))
        // The row that stays: its own video and file, the other's Spotify id and like, plays added.
        val row = f.row(kept)!!
        assertEquals("keptVideo1", row.youtubeId)
        assertEquals("/music/starting-over.flac", row.filePath)
        assertEquals("spotify:track:gone", row.spotifyUri)
        assertEquals(1_234L, row.stashLikedAt)
        assertEquals(6, row.playCount)
        assertEquals("USABC2600001", row.isrc)
        // The other video and the old row id still find the song; no Spotify alias (the column has it).
        assertEquals(kept, db.trackDao().findByYoutubeIdOrAlias("otherVideo1")!!.id)
        assertEquals(kept, db.trackDao().findBySpotifyUriOrAlias("spotify:track:gone")!!.id)
        assertEquals(kept, db.trackDao().getByIdOrMerged(gone)!!.id)
        assertNull(db.trackDao().findByAlias(TrackAliasEntity.KIND_SPOTIFY_URI, "spotify:track:gone"))
        // One log line, naming both rows.
        val log = db.duplicateDao().recentLog(10).single()
        assertEquals(TrackMergeLogEntity.REASON_USER_MERGE, log.reason)
        assertEquals(kept, log.keptTrackId)
        assertEquals(gone, log.removedTrackId)
        assertTrue(log.detail.contains("otherVideo1"))
    }

    @Test fun `a removed membership of the kept row comes back with the other's place`() = runBlocking {
        val kept = f.song(youtubeId = "keptVideo1", file = "/music/a.flac")
        val gone = f.stub()
        val liked = f.playlist("Liked", type = PlaylistType.STASH_LIKED)
        f.member(liked, kept, position = 2, removed = true)
        f.member(liked, gone, position = 9, userAdded = true)

        merger.merge(kept, gone, TrackMergeLogEntity.REASON_USER_MERGE)

        val link = f.link(liked, kept)!!
        assertNull("the song is still in the playlist", link.removedAt)
        assertEquals(9, link.position)
        assertTrue(link.locallyAdded)
    }

    @Test fun `a waiting download moves to a kept row with no file, and is dropped when it has one`() = runBlocking {
        val bare = f.song(youtubeId = "bareVideo01")
        val stub = f.stub()
        f.download(stub, DownloadStatus.PENDING)
        merger.merge(bare, stub, TrackMergeLogEntity.REASON_AUTO_MERGE)
        val moved = db.downloadQueueDao().getByTrackId(bare)
        assertNotNull("the user's download request now downloads the kept row", moved)
        assertEquals("https://music.youtube.com/watch?v=bareVideo01", moved!!.youtubeUrl)

        val onPhone = f.song(title = "Other Song", youtubeId = "onPhone001", file = "/music/b.flac")
        val stub2 = f.stub(title = "Other Song")
        f.download(stub2, DownloadStatus.PENDING)
        merger.merge(onPhone, stub2, TrackMergeLogEntity.REASON_AUTO_MERGE)
        assertNull("already on the phone: nothing to download", db.downloadQueueDao().getByTrackId(onPhone))
        assertEquals(1, f.count("SELECT COUNT(*) FROM download_queue"))
    }

    @Test fun `explicit and clean are never merged`() = runBlocking {
        val explicit = f.song(youtubeId = "explicit001", explicit = true, file = "/music/e.flac")
        val clean = f.song(youtubeId = "clean000001", explicit = false)

        assertNull(merger.merge(explicit, clean, TrackMergeLogEntity.REASON_USER_MERGE))

        assertNotNull(f.row(explicit)); assertNotNull(f.row(clean))
        assertEquals(0, f.count("SELECT COUNT(*) FROM track_aliases"))
        assertEquals(0, f.count("SELECT COUNT(*) FROM track_merge_log"))
    }

    @Test fun `a row flagged as a wrong match is never merged, on either side`() = runBlocking {
        val flagged = f.song(youtubeId = "flagged0001", file = "/music/wrong.flac", flagged = true)
        val stub = f.stub()

        assertNull(merger.merge(flagged, stub, TrackMergeLogEntity.REASON_USER_MERGE))
        assertNull(merger.merge(stub, flagged, TrackMergeLogEntity.REASON_USER_MERGE))

        assertNotNull(f.row(flagged)); assertNotNull(f.row(stub))
    }

    @Test fun `the song's only file is never merged away`() = runBlocking {
        val bare = f.song(youtubeId = "bareVideo01")
        val withFile = f.song(youtubeId = "fileVideo01", file = "/music/only.flac")

        assertNull(merger.merge(keeperId = bare, loserId = withFile, reason = TrackMergeLogEntity.REASON_USER_MERGE))
        assertEquals("/music/only.flac", f.row(withFile)!!.filePath)
    }

    @Test fun `a local file and a running download are never merged`() = runBlocking {
        val local = f.song(source = MusicSource.LOCAL, file = "/music/mine.mp3")
        val stub = f.stub()
        assertNull(merger.merge(local, stub, TrackMergeLogEntity.REASON_USER_MERGE))

        val kept = f.song(youtubeId = "keptVideo1", file = "/music/k.flac")
        f.download(stub, DownloadStatus.IN_PROGRESS)
        assertNull("its file is landing right now", merger.merge(kept, stub, TrackMergeLogEntity.REASON_USER_MERGE))
        assertNotNull(f.row(stub))
    }

    @Test fun `a merged-away row's not-a-duplicate marks move to the kept row`() = runBlocking {
        val kept = f.song(youtubeId = "keptVideo1", file = "/music/k.flac")
        val gone = f.stub()
        val third = f.song(title = "Starting Over (Live)", youtubeId = "liveVideo01")
        db.duplicateDao().dismiss(listOf(DuplicateDismissalEntity.of(gone, third)))

        merger.merge(kept, gone, TrackMergeLogEntity.REASON_USER_MERGE)

        val marks = db.duplicateDao().allDismissals().map { it.trackA to it.trackB }
        assertEquals(listOf(minOf(kept, third) to maxOf(kept, third)), marks)
    }
}
