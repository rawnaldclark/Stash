package com.stash.core.data.sync.workers

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.dao.RemoteSnapshotDao
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.RemotePlaylistSnapshotEntity
import com.stash.core.data.db.entity.RemoteTrackSnapshotEntity
import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.SyncPreferencesManager
import com.stash.core.data.sync.SyncStateManager
import com.stash.core.data.sync.TrackMatcher
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.SyncMode
import com.stash.data.spotify.model.SpotifyOwner
import com.stash.data.spotify.model.SpotifyPlaylistItem
import com.stash.data.spotify.model.SpotifyTracksRef
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
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
import java.time.Instant

/**
 * The diff's half of skipping unchanged Spotify playlists (see
 * [SpotifyMirrorStamp]): a playlist the fetch step didn't re-read must come out
 * of the diff exactly as it went in (never mirrored down to the zero tracks its
 * snapshot carries), and a playlist it did read completely must leave with a
 * stamp the next fetch trusts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DiffWorkerRevisionSkipTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: StashDatabase

    private val remoteSnapshotDao = mockk<RemoteSnapshotDao>()
    private val syncStateManager = mockk<SyncStateManager>(relaxed = true)
    private val syncPreferencesManager = mockk<SyncPreferencesManager>()
    private val blocklistGuard = mockk<BlocklistGuard>()
    private val streamingPreference = mockk<StreamingPreference>()

    private val revision = "AAAAAMv6SY4RNBP4lRibleC3UcWweCUH"
    private val lastSynced = Instant.parse("2026-10-10T08:00:00Z")

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        coEvery { blocklistGuard.isBlocked(any(), any(), any(), any()) } returns false
        coEvery { streamingPreference.current() } returns false
        every { syncPreferencesManager.spotifySyncMode } returns flowOf(SyncMode.REFRESH)
        every { syncPreferencesManager.youtubeSyncMode } returns flowOf(SyncMode.REFRESH)
        every { syncPreferencesManager.pinNewPlaylists } returns flowOf(false)
        every { syncPreferencesManager.mixStackSeeded } returns flowOf(true)
        // The run the snapshots belong to: a download the diff queues refers to it.
        runBlocking { assertEquals(1L, db.syncHistoryDao().insert(SyncHistoryEntity())) }
    }

    @After fun tearDown() = db.close()

    // ── Fixtures ─────────────────────────────────────────────────────────

    private fun listed(name: String = "Road Trip") = SpotifyPlaylistItem(
        id = "pl1",
        name = name,
        owner = SpotifyOwner(id = "testuser"),
        tracks = SpotifyTracksRef(total = 0),
        revisionId = revision,
    )

    /** A synced Spotify playlist holding two songs, last mirrored at [revision] and stamped then. */
    private suspend fun seedMirroredPlaylist(
        name: String = "Road Trip",
        active: Boolean = true,
        stamped: Boolean = true,
    ): Long {
        val playlistId = db.playlistDao().insert(
            PlaylistEntity(
                name = name, source = MusicSource.SPOTIFY, sourceId = "pl1",
                type = PlaylistType.CUSTOM, syncEnabled = true, isActive = active,
                lastSynced = lastSynced,
            ),
        )
        listOf("A", "B").forEachIndexed { i, title ->
            val trackId = db.trackDao().insert(
                TrackEntity(
                    title = title, artist = "Artist",
                    canonicalTitle = title.lowercase(), canonicalArtist = "artist",
                    spotifyUri = "spotify:track:$title",
                ),
            )
            db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = playlistId, trackId = trackId, position = i))
        }
        db.playlistDao().updateTrackCount(playlistId, 2)
        if (stamped) db.playlistDao().updateSnapshotId(playlistId, stampFor(playlistId))
        return playlistId
    }

    private suspend fun stampFor(playlistId: Long, mode: SyncMode = SyncMode.REFRESH) = SpotifyMirrorStamp(
        revision = revision,
        syncMode = mode,
        recordedAtMs = System.currentTimeMillis() - 60 * 60 * 1000,
        digest = db.playlistDao().mirrorDigest(playlistId)!!,
        appVersion = appVersionCode(context),
    ).encode()

    private fun stubSnapshots(snapshot: RemotePlaylistSnapshotEntity, tracks: List<RemoteTrackSnapshotEntity> = emptyList()) {
        coEvery { remoteSnapshotDao.getPlaylistSnapshotsBySyncId(1L) } returns listOf(snapshot)
        coEvery { remoteSnapshotDao.getTrackSnapshotsByPlaylistId(snapshot.id) } returns tracks
    }

    private suspend fun memberships(playlistId: Long) =
        db.playlistDao().getCrossRefsForPlaylist(playlistId)
            .filter { it.removedAt == null }
            .map { it.trackId to it.position }
            .sortedBy { it.second }

    // ── A skipped playlist ───────────────────────────────────────────────

    @Test fun `a playlist the fetch skipped is left exactly as the last full sync left it`() = runBlocking {
        val playlistId = seedMirroredPlaylist()
        val stamp = db.playlistDao().getSnapshotId(playlistId)!!
        val before = memberships(playlistId)
        stubSnapshots(unchangedSpotifyPlaylistSnapshot(listed(), stamp, syncId = 1L).copy(id = 1L))

        runDiff()

        val after = db.playlistDao().getById(playlistId)!!
        assertEquals("REFRESH must not mirror it down to the snapshot's zero tracks", before, memberships(playlistId))
        assertEquals(2, after.trackCount)
        assertEquals("not re-read, so not re-dated", lastSynced, after.lastSynced)
        assertEquals("the stamp still vouches for it", stamp, after.snapshotId)
        coVerify(exactly = 0) { remoteSnapshotDao.getTrackSnapshotsByPlaylistId(any()) }
    }

    @Test fun `a skipped playlist still moves the diff's progress`() = runBlocking {
        val playlistId = seedMirroredPlaylist()
        stubSnapshots(
            unchangedSpotifyPlaylistSnapshot(listed(), db.playlistDao().getSnapshotId(playlistId)!!, syncId = 1L).copy(id = 1L),
        )

        runDiff()

        verify { syncStateManager.onDiffing(1, 1) }
    }

    @Test fun `a skipped playlist the sync had hidden comes back, under the name Spotify now gives it`() = runBlocking {
        val playlistId = seedMirroredPlaylist(name = "Old Name", active = false)
        val before = memberships(playlistId)
        val stamp = db.playlistDao().getSnapshotId(playlistId)!!
        stubSnapshots(unchangedSpotifyPlaylistSnapshot(listed(name = "New Name"), stamp, syncId = 1L).copy(id = 1L))

        runDiff()

        val after = db.playlistDao().getById(playlistId)!!
        assertTrue("listed again, so shown again, as a full sync would", after.isActive)
        assertEquals("New Name", after.name)
        assertEquals(before, memberships(playlistId))
        assertEquals(stamp, after.snapshotId)
    }

    @Test fun `a skip whose stamp was forgotten in the meantime clears nothing and records nothing`() = runBlocking {
        // E.g. an unblock between the fetch and the diff: the stamps no longer
        // match, so the snapshot is diffed, as the incomplete fetch it is.
        val playlistId = seedMirroredPlaylist()
        val stamp = db.playlistDao().getSnapshotId(playlistId)!!
        val before = memberships(playlistId)
        db.playlistDao().forgetSpotifyMirrorStamps()
        stubSnapshots(unchangedSpotifyPlaylistSnapshot(listed(), stamp, syncId = 1L).copy(id = 1L))

        runDiff()

        assertEquals("nothing cleared", before, memberships(playlistId))
        assertNull("nothing recorded: the next sync reads it in full", db.playlistDao().getSnapshotId(playlistId))
    }

    @Test fun `a skip for a playlist deleted in the meantime leaves a row the next sync reads in full`() = runBlocking {
        // Stream-only diffs every playlist, switched on or not, so the made-again
        // row really goes through the diff rather than the switched-off skip.
        coEvery { streamingPreference.current() } returns true
        stubSnapshots(
            unchangedSpotifyPlaylistSnapshot(listed(), SpotifyMirrorStamp(revision, SyncMode.REFRESH, 1L, "0123456789abcdef0123456789abcdef", appVersionCode(context)).encode(), syncId = 1L)
                .copy(id = 1L),
        )

        runDiff()

        val row = db.playlistDao().findBySourceId("pl1")!!
        assertNull(row.snapshotId)
        assertTrue(memberships(row.id).isEmpty())
    }

    // ── Never stamped: Spotify's own playlists and rows filed as mixes ────

    @Test fun `a Spotify-generated playlist is never stamped`() = runBlocking {
        val owned = listed().copy(id = "37i9dQZEVXcDiscoverWeekly")
        db.playlistDao().insert(
            PlaylistEntity(name = "Discover Weekly", source = MusicSource.SPOTIFY, sourceId = owned.id, type = PlaylistType.CUSTOM, syncEnabled = true),
        )
        stubSnapshots(
            spotifyLibraryPlaylistSnapshot(owned, syncId = 1L, partial = false, snapshotId = revision).copy(id = 1L),
            trackSnapshots("A", "B"),
        )

        runDiff()

        assertNull(db.playlistDao().findBySourceId(owned.id)!!.snapshotId)
    }

    @Test fun `a row filed as a mix is never stamped, whatever its id`() = runBlocking {
        // An id outside Spotify's own namespace, so only the type keeps it from a
        // stamp. A home-feed mix snapshot never carries a revision; this one does,
        // to prove the type check alone holds.
        val mixId = "1aB2cD3eF4gH5iJ6kL7mN8"
        db.playlistDao().insert(
            PlaylistEntity(name = "Daily Mix 1", source = MusicSource.SPOTIFY, sourceId = mixId, type = PlaylistType.DAILY_MIX, syncEnabled = true),
        )
        stubSnapshots(
            spotifyLibraryPlaylistSnapshot(listed().copy(id = mixId), syncId = 1L, partial = false, snapshotId = revision)
                .copy(id = 1L, playlistType = PlaylistType.DAILY_MIX),
            trackSnapshots("A", "B"),
        )

        runDiff()

        val row = db.playlistDao().findBySourceId(mixId)!!
        assertEquals(PlaylistType.DAILY_MIX, row.type)
        assertNull(row.snapshotId)
        assertEquals(2, memberships(row.id).size)
    }

    // ── A playlist the fetch read ────────────────────────────────────────

    private fun trackSnapshots(vararg titles: String) = titles.mapIndexed { i, title ->
        RemoteTrackSnapshotEntity(
            id = i + 10L, syncId = 1L, snapshotPlaylistId = 1L,
            title = title, artist = "Artist", durationMs = 200_000,
            spotifyUri = "spotify:track:$title", position = i,
        )
    }

    @Test fun `a verified mirror is stamped, and the next fetch trusts that stamp`() = runBlocking {
        val playlistId = seedMirroredPlaylist(stamped = false)
        stubSnapshots(
            spotifyLibraryPlaylistSnapshot(listed(), syncId = 1L, partial = false, snapshotId = revision).copy(id = 1L),
            trackSnapshots("A", "B", "C"),
        )

        runDiff()

        val stored = db.playlistDao().getSnapshotId(playlistId)
        val stamp = SpotifyMirrorStamp.decode(stored)
        assertNotNull("a complete, verified mirror is stamped", stamp)
        assertEquals("the listing's revision, carried by the snapshot", revision, stamp!!.revision)
        assertEquals(SyncMode.REFRESH, stamp.syncMode)
        val digestNow = db.playlistDao().mirrorDigest(playlistId)
        assertEquals("the stamp describes what this mirror left", digestNow, stamp.digest)
        assertEquals(
            "the next fetch skips it while nothing changes",
            stored,
            stampToSkipOn(revision, stored, digestNow, SyncMode.REFRESH, System.currentTimeMillis(), appVersionCode(context)),
        )
    }

    @Test fun `an Accumulate mirror is stamped as one, so switching to Refresh reads it again`() = runBlocking {
        every { syncPreferencesManager.spotifySyncMode } returns flowOf(SyncMode.ACCUMULATE)
        val playlistId = seedMirroredPlaylist(stamped = false)
        stubSnapshots(
            spotifyLibraryPlaylistSnapshot(listed(), syncId = 1L, partial = false, snapshotId = revision).copy(id = 1L),
            trackSnapshots("A", "B"),
        )

        runDiff()

        val stored = db.playlistDao().getSnapshotId(playlistId)
        assertEquals(SyncMode.ACCUMULATE, SpotifyMirrorStamp.decode(stored)!!.syncMode)
        val digestNow = db.playlistDao().mirrorDigest(playlistId)
        assertNull(stampToSkipOn(revision, stored, digestNow, SyncMode.REFRESH, System.currentTimeMillis(), appVersionCode(context)))
    }

    @Test fun `a mirror with no verified revision is not stamped`() = runBlocking {
        // Stamped before: the old stamp must not outlive this mirror and vouch for it.
        val playlistId = seedMirroredPlaylist(stamped = true)
        stubSnapshots(
            spotifyLibraryPlaylistSnapshot(listed(), syncId = 1L, partial = false, snapshotId = null).copy(id = 1L),
            trackSnapshots("A", "B"),
        )

        runDiff()

        assertNull(db.playlistDao().getSnapshotId(playlistId))
    }

    @Test fun `a re-read playlist is diffed even when its stamp names the same revision`() = runBlocking {
        // The stamp went stale (a week old) or the rows changed, so the fetch
        // read the playlist again: its snapshot carries the bare revision, which
        // never equals a stamp, so the diff runs and re-stamps it.
        val playlistId = seedMirroredPlaylist()
        val oldStamp = db.playlistDao().getSnapshotId(playlistId)
        stubSnapshots(
            spotifyLibraryPlaylistSnapshot(listed(), syncId = 1L, partial = false, snapshotId = revision).copy(id = 1L),
            trackSnapshots("A", "B", "C"),
        )

        runDiff()

        assertEquals("the third song was linked", 3, memberships(playlistId).size)
        val newStamp = db.playlistDao().getSnapshotId(playlistId)
        assertTrue(newStamp != oldStamp)
        assertEquals(db.playlistDao().mirrorDigest(playlistId), SpotifyMirrorStamp.decode(newStamp)!!.digest)
    }

    // ── Builder ──────────────────────────────────────────────────────────

    /** Runs the diff and fails the test unless it succeeded: a failed diff changes nothing either. */
    private suspend fun runDiff() {
        val result = buildWorker().doWork()
        assertTrue("diff must succeed: $result", result is ListenableWorker.Result.Success)
    }

    private fun buildWorker(): DiffWorker = TestListenableWorkerBuilder<DiffWorker>(context)
        .setInputData(workDataOf(PlaylistFetchWorker.KEY_SYNC_ID to 1L))
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ) = DiffWorker(
                appContext, workerParameters,
                database = db,
                remoteSnapshotDao = remoteSnapshotDao,
                trackDao = db.trackDao(),
                playlistDao = db.playlistDao(),
                downloadQueueDao = db.downloadQueueDao(),
                syncHistoryDao = db.syncHistoryDao(),
                trackMatcher = TrackMatcher(),
                syncStateManager = syncStateManager,
                musicRepository = mockk<MusicRepository>(relaxed = true),
                syncPreferencesManager = syncPreferencesManager,
                blocklistGuard = blocklistGuard,
                streamingPreference = streamingPreference,
                syncUndoDao = db.syncUndoDao(),
                syncLog = com.stash.core.data.sync.SyncLog(),
                canonicalRecompute = mockk(relaxed = true),
            )
        })
        .build()
}
