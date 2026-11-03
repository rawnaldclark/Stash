package com.stash.core.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.sync.workers.SpotifyMirrorStamp
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.StepStatus
import com.stash.core.model.SyncMode
import com.stash.data.spotify.SpotifyAlbumTrack
import com.stash.data.spotify.SpotifyAlbumTracklist
import com.stash.data.spotify.SpotifyApiClient
import com.stash.data.spotify.SpotifySavedAlbum
import com.stash.data.spotify.SpotifySavedAlbumsPage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The saved-albums step over a real (in-memory) database with Spotify faked: what it writes for the
 * diff, what it reads and what it leaves alone, and which albums it hides.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SpotifySavedAlbumsFetcherTest {

    private lateinit var db: StashDatabase
    private val spotify = mockk<SpotifyApiClient>()
    private val enabled = MutableStateFlow(true)
    private val prefs = mockk<SyncPreferencesManager> { every { syncSpotifySavedAlbums } returns enabled }
    private val syncLog = SyncLog()
    private lateinit var fetcher: SpotifySavedAlbumsFetcher

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        kotlinx.coroutines.runBlocking { db.syncHistoryDao().insert(SyncHistoryEntity()) }
        fetcher = SpotifySavedAlbumsFetcher(spotify, db, prefs, db.syncUndoDao(), syncLog)
    }

    @After fun tearDown() = db.close()

    // ── Fixtures ─────────────────────────────────────────────────────────

    private fun album(n: Int, addedAt: String = "2026-10-1${n % 10}T10:00:00Z") = SpotifySavedAlbum(
        id = "Alb$n".padEnd(22, 'x'),
        name = "Album $n",
        artists = listOf("Artist $n"),
        coverUrl = "https://i.scdn.co/image/cover$n",
        addedAt = addedAt,
        albumType = "ALBUM",
    )

    private fun key(n: Int) = "album:spotify:" + "Alb$n".padEnd(22, 'x')

    /** The listing as Spotify pages it: [pages] in order, the library counting [total]. */
    private fun listing(vararg pages: List<SpotifySavedAlbum>, total: Int = pages.sumOf { it.size }) {
        var offset = 0
        for (page in pages) {
            coEvery { spotify.getSavedAlbumsPage(any(), offset) } returns
                SpotifySavedAlbumsPage(page, page.map { it.id }, page.size, total, offset)
            offset += page.size
        }
        coEvery { spotify.getSavedAlbumsPage(any(), offset) } returns
            SpotifySavedAlbumsPage(emptyList(), emptyList(), 0, total, offset)
    }

    private fun songs(vararg titles: String, explicitAt: Int = -1, complete: Boolean = true) = SpotifyAlbumTracklist(
        tracks = titles.mapIndexed { i, title ->
            SpotifyAlbumTrack(
                id = "Trk$title".padEnd(22, 'x'), name = title, artists = listOf("Artist"),
                durationMs = 200_000L + i, explicit = i == explicitAt, discNumber = 1, trackNumber = i + 1,
            )
        },
        totalCount = titles.size,
        complete = complete,
    )

    private suspend fun snapshots(syncId: Long = 1L) = db.remoteSnapshotDao().getPlaylistSnapshotsBySyncId(syncId)

    private suspend fun row(
        sourceId: String,
        source: MusicSource = MusicSource.SPOTIFY,
        name: String = sourceId,
        userHidden: Boolean = false,
    ): Long = db.playlistDao().insert(
        PlaylistEntity(name = name, source = source, sourceId = sourceId, type = PlaylistType.CUSTOM, userHidden = userHidden),
    )

    /** A synced album with two songs, mirrored and stamped at [addedAt]'s revision, the stamp [ageMs] old. */
    private suspend fun mirroredAlbum(n: Int, addedAt: String, ageMs: Long = 0L): Long {
        val id = row(key(n), name = "Album $n")
        listOf("A", "B").forEachIndexed { i, title ->
            val track = db.trackDao().insert(
                TrackEntity(title = "$title$n", artist = "Artist", canonicalTitle = "$title$n".lowercase(), canonicalArtist = "artist"),
            )
            db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = id, trackId = track, position = i))
        }
        db.playlistDao().updateTrackCount(id, 2)
        val stamp = SpotifyMirrorStamp(
            revision = savedAlbumRevision(addedAt)!!,
            syncMode = SyncMode.REFRESH,
            recordedAtMs = System.currentTimeMillis() - ageMs,
            digest = db.playlistDao().mirrorDigest(id)!!,
            appVersion = 1L,
        ).encode()
        db.playlistDao().updateSnapshotId(id, stamp)
        return id
    }

    // ── Reading ──────────────────────────────────────────────────────────

    @Test fun `new albums are written in album order under their Spotify key, page after page`() = runTest {
        listing(listOf(album(1), album(2)), listOf(album(3)))
        coEvery { spotify.getAlbumTracks(any()) } returns songs("One", "Two", "Three", explicitAt = 1)

        val step = fetcher.fetch(syncId = 1L, syncMode = SyncMode.REFRESH)

        assertEquals(StepStatus.SUCCESS, step.status)
        assertEquals(3, step.itemCount)
        coVerify { spotify.getSavedAlbumsPage(any(), 0) }
        coVerify { spotify.getSavedAlbumsPage(any(), 2) }
        val written = snapshots()
        assertEquals(listOf(key(1), key(2), key(3)), written.map { it.sourcePlaylistId })
        val first = written.first()
        assertEquals(MusicSource.SPOTIFY, first.source)
        assertEquals(PlaylistType.CUSTOM, first.playlistType)
        assertEquals("Album 1", first.playlistName)
        assertFalse(first.partial)
        assertEquals("the save it mirrors, stamped once the diff mirrors it", "added:2026-10-11T10:00:00Z", first.snapshotId)
        val tracks = db.remoteSnapshotDao().getTrackSnapshotsByPlaylistId(first.id).sortedBy { it.position }
        assertEquals(listOf("One", "Two", "Three"), tracks.map { it.title })
        assertEquals(listOf(0, 1, 2), tracks.map { it.position })
        assertEquals(listOf(false, true, false), tracks.map { it.explicit })
        assertEquals("spotify:track:" + "TrkOne".padEnd(22, 'x'), tracks.first().spotifyUri)
        assertEquals("Album 1", tracks.first().album)
    }

    @Test fun `an album unchanged since its last mirror is passed on without reading its songs, however old the stamp`() = runTest {
        // A month old: an album's tracklist doesn't change, so its stamp doesn't age like a playlist's.
        val id = mirroredAlbum(1, addedAt = "2026-10-11T10:00:00Z", ageMs = 30L * 24 * 60 * 60 * 1000)
        listing(listOf(album(1)))

        val step = fetcher.fetch(syncId = 1L, syncMode = SyncMode.REFRESH)

        coVerify(exactly = 0) { spotify.getAlbumTracks(any()) }
        val snapshot = snapshots().single()
        assertTrue("partial, so a miss at the diff clears nothing", snapshot.partial)
        assertEquals(db.playlistDao().getSnapshotId(id), snapshot.snapshotId)
        assertTrue(db.remoteSnapshotDao().getTrackSnapshotsByPlaylistId(snapshot.id).isEmpty())
        assertEquals(StepStatus.SUCCESS, step.status)
        assertTrue(step.note!!.contains("1 unchanged"))
    }

    @Test fun `saved again on Spotify, an album is read again`() = runTest {
        mirroredAlbum(1, addedAt = "2026-10-11T10:00:00Z")
        listing(listOf(album(1, addedAt = "2026-11-01T09:00:00Z")))
        coEvery { spotify.getAlbumTracks(any()) } returns songs("A1", "B1")

        fetcher.fetch(syncId = 1L, syncMode = SyncMode.REFRESH)

        coVerify(exactly = 1) { spotify.getAlbumTracks("Alb1".padEnd(22, 'x')) }
        assertEquals("added:2026-11-01T09:00:00Z", snapshots().single().snapshotId)
    }

    @Test fun `an album whose rows changed here since its mirror is read again`() = runTest {
        val id = mirroredAlbum(1, addedAt = "2026-10-11T10:00:00Z")
        // A song of it deleted from the library: the full read puts it back.
        db.playlistDao().getCrossRefsForPlaylist(id).first().let { db.playlistDao().removeTrackFromPlaylist(id, it.trackId) }
        listing(listOf(album(1)))
        coEvery { spotify.getAlbumTracks(any()) } returns songs("A1", "B1")

        fetcher.fetch(syncId = 1L, syncMode = SyncMode.REFRESH)

        coVerify(exactly = 1) { spotify.getAlbumTracks(any()) }
    }

    @Test fun `a read short of Spotify's count is partial and never stamped`() = runTest {
        listing(listOf(album(1)))
        coEvery { spotify.getAlbumTracks(any()) } returns songs("One", complete = false)

        fetcher.fetch(syncId = 1L, syncMode = SyncMode.REFRESH)

        val snapshot = snapshots().single()
        assertTrue(snapshot.partial)
        assertNull(snapshot.snapshotId)
    }

    @Test fun `three failed reads in a row stop reading for this sync, and the rest wait`() = runTest {
        listing((1..5).map { album(it) })
        coEvery { spotify.getAlbumTracks(any()) } returns null

        val step = fetcher.fetch(syncId = 1L, syncMode = SyncMode.REFRESH)

        coVerify(exactly = SpotifySavedAlbumsFetcher.FAILURES_TO_STOP) { spotify.getAlbumTracks(any()) }
        assertTrue(snapshots().isEmpty())
        assertEquals(StepStatus.ERROR, step.status)
        assertTrue(step.errorMessage!!, step.errorMessage!!.contains("couldn't read 3 albums: Album 1, Album 2, Album 3"))
        assertTrue(step.note!!.contains("2 left for the next sync"))
    }

    // ── What it leaves alone ─────────────────────────────────────────────

    @Test fun `an album saved in Stash under the same key (a pasted link) is never read, written or hidden`() = runTest {
        val pasted = row(key(1), source = MusicSource.BOTH, name = "Album 1")
        listing(listOf(album(1)))

        fetcher.fetch(syncId = 1L, syncMode = SyncMode.REFRESH)

        coVerify(exactly = 0) { spotify.getAlbumTracks(any()) }
        assertTrue("one row per key: the sync takes nothing over", snapshots().isEmpty())

        // Un-saved on Spotify later: the listener's own save in Stash stays.
        listing(emptyList())
        fetcher.fetch(syncId = 1L, syncMode = SyncMode.REFRESH)
        assertTrue(db.playlistDao().getById(pasted)!!.isActive)
    }

    @Test fun `an album taken out of the library isn't read, and stays out`() = runTest {
        val out = row(key(1), name = "Album 1", userHidden = true)
        listing(listOf(album(1)))

        fetcher.fetch(syncId = 1L, syncMode = SyncMode.REFRESH)

        coVerify(exactly = 0) { spotify.getAlbumTracks(any()) }
        assertTrue(snapshots().isEmpty())
        assertTrue(db.playlistDao().getById(out)!!.userHidden)
    }

    @Test fun `switched off, nothing is read and nothing is hidden`() = runTest {
        enabled.value = false
        val synced = row(key(1))

        val step = fetcher.fetch(syncId = 1L, syncMode = SyncMode.REFRESH)

        assertEquals(StepStatus.SKIPPED, step.status)
        coVerify(exactly = 0) { spotify.getSavedAlbumsPage(any(), any()) }
        assertTrue(db.playlistDao().getById(synced)!!.isActive)
    }

    // ── Removal ──────────────────────────────────────────────────────────

    @Test fun `un-saved on Spotify, a synced album is hidden, and nothing else is`() = runTest {
        val gone = row(key(9))
        val kept = mirroredAlbum(1, addedAt = "2026-10-11T10:00:00Z")
        val pasted = row(key(8), source = MusicSource.BOTH)
        val youTube = row("MPREb_album", source = MusicSource.YOUTUBE)
        val stashSaved = row("album:qobuz:0886446224536", source = MusicSource.BOTH)
        val playlist = row("37iSpotifyPlaylistIdxx")
        listing(listOf(album(1)))

        val step = fetcher.fetch(syncId = 1L, syncMode = SyncMode.REFRESH)

        assertFalse(db.playlistDao().getById(gone)!!.isActive)
        for (id in listOf(kept, pasted, youTube, stashSaved, playlist)) {
            assertTrue("row $id stays", db.playlistDao().getById(id)!!.isActive)
        }
        assertNotNull("Undo last sync brings it back", db.syncUndoDao().latestPointNow())
        assertTrue(step.note!!.contains("1 un-saved on Spotify, hidden"))
        assertTrue(syncLog.lines.value.any { it.text.contains("un-saved on Spotify") })
    }

    @Test fun `Accumulate never hides an album, as it never hides a playlist`() = runTest {
        val gone = row(key(9))
        listing(listOf(album(1)))
        coEvery { spotify.getAlbumTracks(any()) } returns songs("One")

        fetcher.fetch(syncId = 1L, syncMode = SyncMode.ACCUMULATE)

        assertTrue(db.playlistDao().getById(gone)!!.isActive)
    }

    @Test fun `a listing that failed part way hides nothing`() = runTest {
        val gone = row(key(9))
        coEvery { spotify.getSavedAlbumsPage(any(), 0) } returns
            SpotifySavedAlbumsPage(listOf(album(1)), listOf(album(1).id), 1, 2, 0)
        coEvery { spotify.getSavedAlbumsPage(any(), 1) } returns SpotifySavedAlbumsPage.EMPTY
        coEvery { spotify.getAlbumTracks(any()) } returns songs("One")

        val step = fetcher.fetch(syncId = 1L, syncMode = SyncMode.REFRESH)

        assertTrue(db.playlistDao().getById(gone)!!.isActive)
        assertEquals(StepStatus.ERROR, step.status)
        assertTrue(step.errorMessage!!.contains("listing incomplete"))
    }

    @Test fun `a listing short of the library's own count hides nothing`() = runTest {
        val gone = row(key(9))
        listing(listOf(album(1)), total = 2)
        coEvery { spotify.getAlbumTracks(any()) } returns songs("One")

        fetcher.fetch(syncId = 1L, syncMode = SyncMode.REFRESH)

        assertTrue(db.playlistDao().getById(gone)!!.isActive)
    }

    @Test fun `a library that changed during the walk hides nothing`() = runTest {
        val gone = row(key(9))
        coEvery { spotify.getSavedAlbumsPage(any(), 0) } returns
            SpotifySavedAlbumsPage(listOf(album(1)), listOf(album(1).id), 1, 2, 0)
        coEvery { spotify.getSavedAlbumsPage(any(), 1) } returns
            SpotifySavedAlbumsPage(listOf(album(2)), listOf(album(2).id), 1, 3, 1)
        coEvery { spotify.getSavedAlbumsPage(any(), 2) } returns
            SpotifySavedAlbumsPage(listOf(album(3)), listOf(album(3).id), 1, 3, 2)
        coEvery { spotify.getAlbumTracks(any()) } returns songs("One")

        fetcher.fetch(syncId = 1L, syncMode = SyncMode.REFRESH)

        assertTrue(db.playlistDao().getById(gone)!!.isActive)
    }

    @Test fun `an album listed but not readable (a pre-release) still counts as saved`() = runTest {
        val preRelease = row(key(2))
        coEvery { spotify.getSavedAlbumsPage(any(), 0) } returns
            SpotifySavedAlbumsPage(listOf(album(1)), listOf(album(1).id, album(2).id), 2, 2, 0)
        coEvery { spotify.getAlbumTracks(any()) } returns songs("One")

        fetcher.fetch(syncId = 1L, syncMode = SyncMode.REFRESH)

        assertTrue(db.playlistDao().getById(preRelease)!!.isActive)
        coVerify(exactly = 1) { spotify.getAlbumTracks(any()) }
    }

    @Test fun `a failed listing is an error and hides nothing`() = runTest {
        val gone = row(key(9))
        coEvery { spotify.getSavedAlbumsPage(any(), any()) } returns SpotifySavedAlbumsPage.EMPTY

        val step = fetcher.fetch(syncId = 1L, syncMode = SyncMode.REFRESH)

        assertEquals(StepStatus.ERROR, step.status)
        assertTrue(db.playlistDao().getById(gone)!!.isActive)
    }

    // ── The skip rule itself ─────────────────────────────────────────────

    @Test fun `the album stamp holds only for the same save and the same rows`() {
        val stamp = SpotifyMirrorStamp("added:2026-10-11T10:00:00Z", SyncMode.REFRESH, 1L, "d1", 1L).encode()

        assertEquals(stamp, albumStampToSkipOn("added:2026-10-11T10:00:00Z", stamp, "d1"))
        assertNull("saved again", albumStampToSkipOn("added:2026-11-01T09:00:00Z", stamp, "d1"))
        assertNull("rows changed", albumStampToSkipOn("added:2026-10-11T10:00:00Z", stamp, "d2"))
        assertNull("no fingerprint", albumStampToSkipOn("added:2026-10-11T10:00:00Z", stamp, null))
        assertNull("no stamp", albumStampToSkipOn("added:2026-10-11T10:00:00Z", null, "d1"))
        assertNull("no save date", savedAlbumRevision(null))
    }
}
