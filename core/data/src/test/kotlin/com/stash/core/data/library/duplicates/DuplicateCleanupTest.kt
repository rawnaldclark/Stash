package com.stash.core.data.library.duplicates

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.dao.getByIdOrMerged
import com.stash.core.data.db.entity.DiscoveryQueueEntity
import com.stash.core.data.db.entity.TrackMergeLogEntity
import com.stash.core.data.library.CanonicalRecompute
import com.stash.core.data.library.CanonicalRecomputeRunner
import com.stash.core.data.library.CanonicalRecomputeState
import com.stash.core.model.DownloadStatus
import com.stash.core.model.PlaylistType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The one-time [DuplicateCleanup] on a real database: id-less stubs are merged into the row of
 * their song or, when nothing uses them, deleted; everything uncertain, and every file, is left
 * alone. The owner's case: "Starting Over", one downloaded row with a video id and nine red stubs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DuplicateCleanupTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: StashDatabase
    private lateinit var f: DuplicateFixtures
    private lateinit var storeScope: CoroutineScope
    private lateinit var state: DuplicateCleanupState
    private lateinit var cleanup: DuplicateCleanup

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        f = DuplicateFixtures(db)
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        state = DuplicateCleanupState(
            PreferenceDataStoreFactory.create(scope = storeScope) { java.io.File(tmp.root, "duplicate_cleanup.preferences_pb") },
        )
        val canonicalState = CanonicalRecomputeState(
            PreferenceDataStoreFactory.create(scope = storeScope) { java.io.File(tmp.root, "canonical_recompute.preferences_pb") },
        )
        cleanup = DuplicateCleanup(db, TrackMerger(db), state, CanonicalRecomputeRunner(CanonicalRecompute(db), canonicalState))
    }

    @After fun tearDown() {
        storeScope.cancel()
        db.close()
    }

    @Test fun `a stub in a mix is merged into the downloaded row of its song`() = runBlocking {
        val hand = f.song(youtubeId = "Ikn6Z2fxMs4", file = "/music/starting-over.flac", format = "flac", likedAt = 1L)
        val stub = f.stub()
        val mix = f.playlist("Daily Discover", type = PlaylistType.STASH_MIX)
        f.member(mix, stub, position = 4)

        val summary = cleanup.run()

        assertEquals(1, summary.merged)
        assertNull(f.row(stub))
        assertEquals("the mix plays the downloaded copy where the stub sat", 4, f.link(mix, hand)!!.position)
        assertEquals(hand, db.trackDao().getByIdOrMerged(stub)!!.id)
        assertEquals("/music/starting-over.flac", f.row(hand)!!.filePath)
        assertEquals(TrackMergeLogEntity.REASON_AUTO_MERGE, db.duplicateDao().recentLog(1).single().reason)
    }

    @Test fun `nine stubs of one song become one row`() = runBlocking {
        val hand = f.song(youtubeId = "Ikn6Z2fxMs4", file = "/music/starting-over.flac")
        val mix = f.playlist("Daily Discover", type = PlaylistType.STASH_MIX)
        repeat(9) { i -> f.stub().also { if (i % 3 == 0) f.member(mix, it, position = i) } }

        cleanup.run()

        assertEquals(1, f.count("SELECT COUNT(*) FROM tracks"))
        assertEquals("one membership, never the song twice", 1, f.count("SELECT COUNT(*) FROM playlist_tracks WHERE playlist_id = $mix"))
        assertNotNull(f.link(mix, hand))
    }

    @Test fun `an unused stub with no twin is deleted`() = runBlocking {
        val orphan = f.stub(title = "Never Heard")

        val summary = cleanup.run()

        assertEquals(1, summary.removed)
        assertNull(f.row(orphan))
        val line = db.duplicateDao().recentLog(1).single()
        assertEquals(TrackMergeLogEntity.REASON_AUTO_ORPHAN, line.reason)
        assertEquals("Never Heard", line.title)
    }

    @Test fun `a stub with plays is merged, not deleted, and its plays move`() = runBlocking {
        val hand = f.song(youtubeId = "Ikn6Z2fxMs4", file = "/music/starting-over.flac")
        val playedTwin = f.stub(plays = 2)
        f.play(playedTwin); f.play(playedTwin, at = 30_000L)
        val playedAlone = f.stub(title = "Holy Fucking Shit: 40,000", plays = 1)
        f.play(playedAlone)

        val summary = cleanup.run()

        assertEquals(1, summary.merged)
        assertEquals(0, summary.removed)
        assertNull(f.row(playedTwin))
        assertEquals(2, f.count("SELECT COUNT(*) FROM listening_events WHERE track_id = $hand"))
        assertEquals(2, f.row(hand)!!.playCount)
        assertNotNull("a played stub with no twin keeps its row and its history", f.row(playedAlone))
        assertEquals(1, f.count("SELECT COUNT(*) FROM listening_events WHERE track_id = $playedAlone"))
    }

    @Test fun `a liked stub, one in a playlist or taken out of one, and one in a mix's pool are kept`() = runBlocking {
        val liked = f.song(title = "Liked Alone", likedAt = 5L)
        val inPlaylist = f.stub(title = "In A Playlist")
        f.member(f.playlist("Mine"), inPlaylist, position = 0)
        val takenOut = f.stub(title = "Taken Out")
        f.member(f.playlist("Stash Liked", type = PlaylistType.STASH_LIKED), takenOut, position = 0, removed = true)
        val inPool = f.stub(title = "In The Pool")
        f.discovered(inPool, DiscoveryQueueEntity.STATUS_DONE)
        val failedOnly = f.stub(title = "Failed Find")
        f.discovered(failedOnly, DiscoveryQueueEntity.STATUS_FAILED)

        cleanup.run()

        assertNotNull(f.row(liked)); assertNotNull(f.row(inPlaylist)); assertNotNull(f.row(inPool))
        assertNotNull("the user's removal stays with the song", f.row(takenOut))
        assertNull("a failed find is no mix's song", f.row(failedOnly))
    }

    @Test fun `explicit and clean are never merged`() = runBlocking {
        val clean = f.song(youtubeId = "cleanVideo1", file = "/music/clean.flac", explicit = false)
        val explicitStub = f.stub(explicit = true)
        val mix = f.playlist("Daily Discover", type = PlaylistType.STASH_MIX)
        f.member(mix, explicitStub, position = 0)

        val summary = cleanup.run()

        assertEquals(0, summary.merged)
        assertNotNull(f.row(clean)); assertNotNull(f.row(explicitStub))
        assertNull(f.link(mix, clean))
    }

    @Test fun `a row flagged as a wrong match is never merged`() = runBlocking {
        val wrong = f.song(youtubeId = "wrongVideo1", file = "/music/wrong.flac", flagged = true)
        val stub = f.stub()
        val mix = f.playlist("Daily Discover", type = PlaylistType.STASH_MIX)
        f.member(mix, stub, position = 0)

        cleanup.run()

        assertNotNull(f.row(wrong)); assertNotNull(f.row(stub))
        assertNull(f.link(mix, wrong))
    }

    @Test fun `another version or a clearly different length is not merged`() = runBlocking {
        f.song(youtubeId = "studioVideo", file = "/music/studio.flac")
        val live = f.stub(title = "Starting Over (Live)")
        val longer = f.stub(lengthMs = 480_000L)
        val mix = f.playlist("Daily Discover", type = PlaylistType.STASH_MIX)
        f.member(mix, live, position = 0); f.member(mix, longer, position = 1)

        assertEquals(0, cleanup.run().merged)
        assertNotNull(f.row(live)); assertNotNull(f.row(longer))
    }

    @Test fun `two files are left for Possible duplicates and no file is touched`() = runBlocking {
        val hand = f.song(youtubeId = "Ikn6Z2fxMs4", file = "/music/starting-over.flac")
        val stubWithFile = f.stub(file = "/music/starting-over.opus")

        val summary = cleanup.run()

        assertEquals(1, summary.leftForReview)
        assertEquals("/music/starting-over.flac", f.row(hand)!!.filePath)
        assertEquals("/music/starting-over.opus", f.row(stubWithFile)!!.filePath)
    }

    @Test fun `a stub holding the only file stays, and takes the other row's ids and playlists`() = runBlocking {
        val identified = f.song(youtubeId = "Ikn6Z2fxMs4", spotifyUri = "spotify:track:so")
        val liked = f.playlist("Liked Songs", type = PlaylistType.LIKED_SONGS)
        f.member(liked, identified, position = 0)
        val stubWithFile = f.stub(file = "/music/starting-over.flac")

        assertEquals(1, cleanup.run().merged)

        assertNull(f.row(identified))
        val row = f.row(stubWithFile)!!
        assertEquals("/music/starting-over.flac", row.filePath)
        assertEquals("Ikn6Z2fxMs4", row.youtubeId)
        assertEquals("spotify:track:so", row.spotifyUri)
        assertEquals(com.stash.core.model.MusicSource.SPOTIFY, row.source)
        assertNotNull(f.link(liked, stubWithFile))
        assertEquals(stubWithFile, db.trackDao().getByIdOrMerged(identified)!!.id)
    }

    @Test fun `a stub with a download waiting is left for later`() = runBlocking {
        f.song(youtubeId = "Ikn6Z2fxMs4", file = "/music/starting-over.flac")
        val stub = f.stub()
        f.download(stub, DownloadStatus.PENDING)

        assertEquals(1, cleanup.run().skipped)
        assertNotNull(f.row(stub))
    }

    @Test fun `the automatic pass is idempotent`() = runBlocking {
        f.song(youtubeId = "Ikn6Z2fxMs4", file = "/music/starting-over.flac")
        repeat(3) { f.stub() }
        f.stub(title = "Never Heard")
        val kept = f.stub(title = "In A Playlist")
        f.member(f.playlist("Mine"), kept, position = 0)
        f.song(title = "Two Uploads", youtubeId = "uploadOne01", file = "/music/two.flac")
        f.song(title = "Two Uploads", youtubeId = "uploadTwo01")

        val first = cleanup.run()
        val tables = snapshot()
        val second = cleanup.run()

        assertEquals(3, first.merged)
        assertEquals(1, first.removed)
        assertEquals(0, second.merged)
        assertEquals(0, second.removed)
        assertEquals("a second run changes nothing", tables, snapshot())
    }

    @Test fun `it runs once, marks itself done and leaves one notice`() = runBlocking {
        f.song(youtubeId = "Ikn6Z2fxMs4", file = "/music/starting-over.flac")
        repeat(2) { f.stub() }

        val first = cleanup.runIfPending()
        val second = cleanup.runIfPending()

        assertEquals(2, first!!.merged)
        assertNull("done: the second start does nothing", second)
        assertTrue(state.isDone())
        assertEquals(2, state.pendingNotice.first())
        state.noticeShown()
        assertNull(state.pendingNotice.first())
        assertEquals(2, state.lastRun.first()!!.merged)

        state.rearm()
        assertFalse("a restored backup runs it again", state.isDone())
    }

    private fun snapshot(): List<Int> = listOf(
        "SELECT COUNT(*) FROM tracks", "SELECT COUNT(*) FROM playlist_tracks", "SELECT COUNT(*) FROM track_aliases",
        "SELECT COUNT(*) FROM track_merge_log", "SELECT COUNT(*) FROM listening_events", "SELECT SUM(play_count) FROM tracks",
    ).map(f::count)
}
