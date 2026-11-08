package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * End-to-end cover for "Undo last sync": capture a restore point, apply the two
 * destructive things a sync actually does, then undo and prove the library is
 * back.
 *
 * The scenario is the reported bug — an ACCUMULATE sync that hid YouTube
 * playlists and cleared membership — so these tests fail if undo stops covering
 * the case it was built for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SyncUndoDaoTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: SyncUndoDao
    private lateinit var playlistDao: PlaylistDao

    private val syncId = 42L

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.syncUndoDao()
        playlistDao = db.playlistDao()
    }

    @After fun tearDown() { db.close() }

    private suspend fun seedTrack(id: Long): Long =
        db.trackDao().insert(
            TrackEntity(id = id, title = "Track $id", artist = "Artist", album = "Album")
        )

    private suspend fun seedPlaylist(name: String, source: MusicSource, sourceId: String): Long =
        playlistDao.insert(
            PlaylistEntity(
                name = name,
                source = source,
                sourceId = sourceId,
                type = PlaylistType.CUSTOM,
                isActive = true,
                syncEnabled = true,
            )
        )

    private suspend fun link(playlistId: Long, trackId: Long, pos: Int, local: Boolean = false) =
        playlistDao.insertCrossRef(
            PlaylistTrackCrossRef(
                playlistId = playlistId,
                trackId = trackId,
                position = pos,
                locallyAdded = local,
            )
        )


    /** Track ids currently linked to [playlistId], in stored position order. */
    private suspend fun trackIdsIn(playlistId: Long): List<Long> =
        playlistDao.getCrossRefsForPlaylist(playlistId)
            .sortedBy { it.position }
            .map { it.trackId }

    @Test
    fun `undo restores a playlist that a sync hid`() = runTest {
        val pid = seedPlaylist("srab", MusicSource.YOUTUBE, "yt-srab")
        dao.capture(syncId, nowEpochMs = 1_000L)

        // What the sync did: soft-hide it.
        playlistDao.deactivateMissingForSource(MusicSource.YOUTUBE, currentSourceIds = listOf("something-else"))
        assertThat(playlistDao.getById(pid)!!.isActive).isFalse()

        dao.restore(syncId)

        assertThat(playlistDao.getById(pid)!!.isActive).isTrue()
    }

    @Test
    fun `undo refills a playlist that REFRESH emptied, preserving order`() = runTest {
        val pid = seedPlaylist("Mix", MusicSource.YOUTUBE, "yt-mix")
        val t1 = seedTrack(1); val t2 = seedTrack(2); val t3 = seedTrack(3)
        link(pid, t1, 0); link(pid, t2, 1); link(pid, t3, 2)

        dao.capture(syncId, nowEpochMs = 1_000L)

        // What REFRESH did: wipe the sync-added membership.
        playlistDao.clearSyncedPlaylistTracks(pid)
        assertThat(trackIdsIn(pid)).isEmpty()

        val restored = dao.restore(syncId)

        assertThat(restored).isEqualTo(3)
        assertThat(trackIdsIn(pid)).containsExactly(t1, t2, t3).inOrder()
    }

    @Test
    fun `undo keeps tracks the user added after the sync`() = runTest {
        val pid = seedPlaylist("Mix", MusicSource.YOUTUBE, "yt-mix")
        val synced = seedTrack(1)
        link(pid, synced, 0)
        dao.capture(syncId, nowEpochMs = 1_000L)

        playlistDao.clearSyncedPlaylistTracks(pid)
        // The user hand-added a track AFTER the sync — undo must not destroy it.
        val mine = seedTrack(99)
        link(pid, mine, 5, local = true)

        dao.restore(syncId)

        assertThat(trackIdsIn(pid)).containsExactly(synced, mine)
    }

    @Test
    fun `a point is consumed so the same undo cannot be applied twice`() = runTest {
        seedPlaylist("P", MusicSource.YOUTUBE, "yt-p")
        dao.capture(syncId, nowEpochMs = 1_000L)
        assertThat(dao.latestPointNow()).isNotNull()

        dao.restore(syncId)

        assertThat(dao.latestPointNow()).isNull()
    }

    @Test
    fun `only the newest points are kept`() = runTest {
        seedPlaylist("P", MusicSource.YOUTUBE, "yt-p")
        repeat(5) { i -> dao.capture(syncId = i.toLong(), nowEpochMs = (i * 1_000).toLong()) }

        // Newest MAX_UNDO_POINTS survive; the oldest are pruned.
        assertThat(dao.latestPointNow()!!.syncId).isEqualTo(4L)
        assertThat(dao.pointIdsBeyond(SyncUndoDao.MAX_UNDO_POINTS)).isEmpty()
    }

    @Test
    fun `a point records what it captured`() = runTest {
        val pid = seedPlaylist("P", MusicSource.YOUTUBE, "yt-p")
        link(pid, seedTrack(1), 0); link(pid, seedTrack(2), 1)

        dao.capture(syncId, nowEpochMs = 7_000L)

        val point = dao.latestPointNow()!!
        assertThat(point.syncId).isEqualTo(syncId)
        assertThat(point.createdAtEpochMs).isEqualTo(7_000L)
        assertThat(point.playlistCount).isEqualTo(1)
        assertThat(point.membershipCount).isEqualTo(2)
    }

    // ── 2026-10-11 follow-ups: full undo ────────────────────────────────────

    /** A playlist as a sync creates one: Download switch off, added [addedAtMs]. */
    private suspend fun syncCreated(name: String, source: MusicSource, addedAtMs: Long): Long =
        playlistDao.insert(
            PlaylistEntity(
                name = name,
                source = source,
                sourceId = "$source-$name",
                type = PlaylistType.CUSTOM,
                isActive = true,
                syncEnabled = false,
                dateAdded = Instant.ofEpochMilli(addedAtMs),
            )
        )

    @Test
    fun `undo brings back a Spotify playlist the fetch hid before the diff ran`() = runTest {
        val pid = seedPlaylist("Road trip", MusicSource.SPOTIFY, "sp-road")
        // The fetch step: point first, then hide what the account no longer lists.
        assertThat(dao.captureOnce(syncId, nowEpochMs = 1_000L)).isTrue()
        com.stash.core.data.sync.hideWhatNoSpotifyAccountListed(
            playlistDao, dao, com.stash.core.data.sync.SyncLog(), syncId, com.stash.core.model.SyncMode.REFRESH,
            com.stash.core.data.sync.SpotifySyncListings().apply {
                playlists += com.stash.core.data.sync.SpotifyAccountListing(null).apply {
                    ids += "sp-other"
                    listedAll = true
                    complete = true
                }
            },
        )
        assertThat(playlistDao.getById(pid)!!.isActive).isFalse()
        // The diff step keeps that point rather than snapshotting the playlist already hidden.
        assertThat(dao.captureOnce(syncId, nowEpochMs = 2_000L)).isFalse()
        assertThat(dao.latestPointNow()!!.createdAtEpochMs).isEqualTo(1_000L)

        dao.restore(syncId)

        assertThat(playlistDao.getById(pid)!!.isActive).isTrue()
    }

    @Test
    fun `undo hides the playlists the sync added, not ones the user has since made their own`() = runTest {
        val before = seedPlaylist("Already here", MusicSource.YOUTUBE, "yt-here")
        val now = System.currentTimeMillis()
        dao.capture(syncId, nowEpochMs = now)
        val added = syncCreated("New mix", MusicSource.YOUTUBE, addedAtMs = now + 1)
        val kept = syncCreated("Switched on", MusicSource.SPOTIFY, addedAtMs = now + 1)
        playlistDao.setSyncEnabled(kept, true) // the user turned its Download switch on after the sync
        val mine = playlistDao.insert(
            PlaylistEntity(name = "Mine", source = MusicSource.BOTH, sourceId = "custom_x", isActive = true, syncEnabled = true),
        )

        dao.restore(syncId)

        assertThat(playlistDao.getById(added)!!.isActive).isFalse()
        assertThat(playlistDao.getById(kept)!!.isActive).isTrue()
        assertThat(playlistDao.getById(mine)!!.isActive).isTrue()
        assertThat(playlistDao.getById(before)!!.isActive).isTrue()
    }

    @Test
    fun `undo keeps a Download switch the user flipped after the sync`() = runTest {
        val on = seedPlaylist("On", MusicSource.SPOTIFY, "sp-on")
        val off = syncCreated("Off", MusicSource.YOUTUBE, addedAtMs = 0L)
        dao.capture(syncId, nowEpochMs = 1_000L)
        playlistDao.setSyncEnabled(on, false)
        playlistDao.setSyncEnabled(off, true)

        dao.restore(syncId)

        assertThat(playlistDao.getById(on)!!.syncEnabled).isFalse()
        assertThat(playlistDao.getById(off)!!.syncEnabled).isTrue()
    }

    @Test
    fun `undo keeps a track the user removed after the sync`() = runTest {
        val pid = seedPlaylist("Mix", MusicSource.YOUTUBE, "yt-mix")
        val t1 = seedTrack(1); val t2 = seedTrack(2)
        link(pid, t1, 0); link(pid, t2, 1)
        dao.capture(syncId, nowEpochMs = 1_000L)

        playlistDao.softDeleteTrackFromPlaylist(pid, t2)
        dao.restore(syncId)

        val rows = playlistDao.getCrossRefsForPlaylist(pid).associateBy { it.trackId }
        assertThat(rows.getValue(t1).removedAt).isNull()
        assertThat(rows.getValue(t2).removedAt).isNotNull()
    }

    @Test
    fun `after an undo the sync before is not offered`() = runTest {
        seedPlaylist("P", MusicSource.YOUTUBE, "yt-p")
        dao.capture(syncId = 1L, nowEpochMs = 1_000L)
        dao.capture(syncId = 2L, nowEpochMs = 2_000L)

        dao.restore(2L)

        assertThat(dao.latestPointNow()).isNull()
        assertThat(dao.restore(1L)).isEqualTo(0)
    }

    @Test
    fun `counts only the playlists the sync changed`() = runTest {
        val t1 = seedTrack(1); val t2 = seedTrack(2); val t3 = seedTrack(3)
        val hidden = seedPlaylist("Hidden", MusicSource.YOUTUBE, "yt-hidden")
        val refreshed = seedPlaylist("Refreshed", MusicSource.SPOTIFY, "sp-refreshed")
        val reordered = seedPlaylist("Reordered", MusicSource.SPOTIFY, "sp-reordered")
        val untouched = seedPlaylist("Untouched", MusicSource.YOUTUBE, "yt-untouched")
        val stashMix = playlistDao.insert(
            PlaylistEntity(name = "Daily Discover", source = MusicSource.BOTH, sourceId = "stash_mix_1", type = PlaylistType.STASH_MIX, isActive = true),
        )
        link(refreshed, t1, 0)
        link(reordered, t1, 0); link(reordered, t2, 1)
        link(untouched, t3, 0)
        link(stashMix, t1, 0)
        val now = System.currentTimeMillis()
        dao.capture(syncId, nowEpochMs = now)

        // What the sync did: hid one, refilled one with another song, re-ordered one, added one.
        playlistDao.deactivateMissingForSource(MusicSource.YOUTUBE, currentSourceIds = listOf("yt-untouched"))
        playlistDao.clearSyncedPlaylistTracks(refreshed)
        link(refreshed, t2, 0)
        playlistDao.clearSyncedPlaylistTracks(reordered)
        link(reordered, t2, 0); link(reordered, t1, 1)
        syncCreated("New", MusicSource.YOUTUBE, addedAtMs = now + 1)
        // Not the sync: a Stash mix refreshing on its own schedule.
        playlistDao.clearSyncedPlaylistTracks(stashMix)
        link(stashMix, t3, 0)

        // Hidden, refreshed, new. Not the re-order, the untouched one, or the Stash mix.
        assertThat(dao.countChangedPlaylists(syncId, now)).isEqualTo(3)
        assertThat(playlistDao.getById(hidden)!!.isActive).isFalse()
    }

    @Test
    fun `a sync that changed nothing counts zero`() = runTest {
        val pid = seedPlaylist("P", MusicSource.SPOTIFY, "sp-p")
        link(pid, seedTrack(1), 0)
        dao.capture(syncId, nowEpochMs = System.currentTimeMillis())

        assertThat(dao.countChangedPlaylists(syncId, dao.latestPointNow()!!.createdAtEpochMs)).isEqualTo(0)
    }

    @Test
    fun `undo makes the next sync re-read the playlists it put back`() = runTest {
        val reread = seedPlaylist("Re-read", MusicSource.SPOTIFY, "sp-reread")
        val skipped = seedPlaylist("Skipped", MusicSource.SPOTIFY, "sp-skipped")
        playlistDao.updateSnapshotId(reread, "v1")
        playlistDao.updateSnapshotId(skipped, "v1")
        playlistDao.updateLastSynced(skipped, 500L)
        dao.capture(syncId, nowEpochMs = 1_000L)
        // The sync re-read one playlist (new version id); the other was unchanged and skipped.
        playlistDao.updateSnapshotId(reread, "v2")
        playlistDao.updateLastSynced(reread, 1_500L)

        dao.restore(syncId)

        // Its tracks are back to v1's, so "v2, unchanged" must not make the next sync skip it.
        assertThat(playlistDao.getById(reread)!!.snapshotId).isNull()
        assertThat(playlistDao.getById(skipped)!!.snapshotId).isEqualTo("v1")
    }

    @Test
    fun `undo leaves a Stash mix's tracks as they are now`() = runTest {
        val mix = playlistDao.insert(
            PlaylistEntity(name = "Daily Discover", source = MusicSource.BOTH, sourceId = "stash_mix_1", type = PlaylistType.STASH_MIX, isActive = true),
        )
        val old = seedTrack(1); val fresh = seedTrack(2)
        link(mix, old, 0)
        dao.capture(syncId, nowEpochMs = 1_000L)
        // The mix refreshed on its own after the sync.
        playlistDao.clearSyncedPlaylistTracks(mix)
        link(mix, fresh, 0)

        dao.restore(syncId)

        assertThat(trackIdsIn(mix)).containsExactly(fresh)
    }
}
