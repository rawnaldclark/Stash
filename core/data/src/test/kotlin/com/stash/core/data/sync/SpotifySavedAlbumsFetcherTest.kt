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
    private fun listing(
        vararg pages: List<SpotifySavedAlbum>,
        total: Int = pages.sumOf { it.size },
        order: String? = SpotifyApiClient.SAVED_ALBUMS_ORDER,
    ) {
        var offset = 0
        for (page in pages) {
            coEvery { spotify.getSavedAlbumsPage(any(), offset, any()) } returns
                SpotifySavedAlbumsPage(page, page.map { it.id }, page.size, total, offset, sortOrder = order)
            offset += page.size
        }
        coEvery { spotify.getSavedAlbumsPage(any(), offset, any()) } returns
            SpotifySavedAlbumsPage(emptyList(), emptyList(), 0, total, offset, sortOrder = order)
    }

    private fun songs(
        vararg titles: String,
        explicitAt: Int = -1,
        complete: Boolean = true,
        walkedAll: Boolean = complete,
        pageFailed: Boolean = false,
        pageCapHit: Boolean = false,
        totalCount: Int = titles.size,
    ) = SpotifyAlbumTracklist(
        tracks = titles.mapIndexed { i, title ->
            SpotifyAlbumTrack(
                id = "Trk$title".padEnd(22, 'x'), name = title, artists = listOf("Artist"),
                durationMs = 200_000L + i, explicit = i == explicitAt, discNumber = 1, trackNumber = i + 1,
            )
        },
        totalCount = totalCount,
        complete = complete,
        walkedAll = walkedAll,
        pageFailed = pageFailed,
        pageCapHit = pageCapHit,
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

    /** A synced album with two songs, mirrored (read in full) and stamped at [addedAt]'s revision [ageMs] ago. */
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
        db.playlistDao().updateLastSynced(id, System.currentTimeMillis() - ageMs)
        return id
    }

    private val day = 24L * 60 * 60 * 1000

    // ── Reading ──────────────────────────────────────────────────────────

    @Test fun `new albums are written in album order under their Spotify key, page after page`() = runTest {
        listing(listOf(album(1), album(2)), listOf(album(3)))
        coEvery { spotify.getAlbumTracks(any()) } returns songs("One", "Two", "Three", explicitAt = 1)

        val step = fetcher.fetch(syncId = 1L, syncMode = SyncMode.REFRESH)

        assertEquals(StepStatus.SUCCESS, step.status)
        assertEquals(3, step.itemCount)
        coVerify { spotify.getSavedAlbumsPage(any(), 0, any()) }
        coVerify { spotify.getSavedAlbumsPage(any(), 2, any()) }
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

    @Test fun `an album unchanged since its last mirror is passed on without reading its songs, a month after it was read`() = runTest {
        // A month old: an album's stamp doesn't end after a week as a playlist's does (90 days, see below).
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

    @Test fun `a whole walk with an unreadable song is partial and never stamped`() = runTest {
        listing(listOf(album(1)))
        coEvery { spotify.getAlbumTracks(any()) } returns songs("One", complete = false, walkedAll = true)

        fetcher.fetch(syncId = 1L, syncMode = SyncMode.REFRESH)

        val snapshot = snapshots().single()
        assertTrue(snapshot.partial)
        assertNull(snapshot.snapshotId)
    }

    @Test fun `a new album cut short by a failed page is written in part, unstamped, and is no error`() = runTest {
        listing(listOf(album(1)))
        coEvery { spotify.getAlbumTracks(any()) } returns
            songs("One", totalCount = 2, complete = false, walkedAll = false, pageFailed = true)

        val step = fetcher.fetch(syncId = 1L, syncMode = SyncMode.REFRESH)

        val snapshot = snapshots().single()
        assertTrue("the diff only adds", snapshot.partial)
        assertNull("never vouched for, so read again next sync", snapshot.snapshotId)
        assertEquals(0, step.unreadPlaylistCount)
        assertEquals(StepStatus.SUCCESS, step.status)
        assertTrue(step.note!!, step.note!!.contains("1 read in part"))
    }

    @Test fun `a page failing over an album already here keeps its last tracklist, and never stops the reading`() = runTest {
        mirroredAlbum(1, addedAt = "2026-10-11T10:00:00Z")
        mirroredAlbum(2, addedAt = "2026-10-12T10:00:00Z")
        mirroredAlbum(3, addedAt = "2026-10-13T10:00:00Z")
        // All three saved again, so read again: each read fails part way. Then a new album.
        listing(
            listOf(
                album(1, addedAt = "2026-11-01T09:00:00Z"), album(2, addedAt = "2026-11-01T09:00:00Z"),
                album(3, addedAt = "2026-11-01T09:00:00Z"), album(4),
            ),
        )
        coEvery { spotify.getAlbumTracks(any()) } returns songs("A", complete = false, walkedAll = false, pageFailed = true)
        coEvery { spotify.getAlbumTracks(album(4).id) } returns songs("One")

        val step = fetcher.fetch(syncId = 1L, syncMode = SyncMode.REFRESH)

        assertEquals("nothing for the diff to write over the three", listOf(key(4)), snapshots().map { it.sourcePlaylistId })
        assertEquals(3, step.unreadPlaylistCount)
        coVerify(exactly = 1) { spotify.getAlbumTracks(album(4).id) }
    }

    @Test fun `albums longer than the walk are written in part every sync, never an error, and never stop the reading`() = runTest {
        // Saved again since its mirror, so read: it stops at the cap too.
        mirroredAlbum(5, addedAt = "2026-10-01T10:00:00Z")
        listing((1..5).map { album(it) })
        coEvery { spotify.getAlbumTracks(any()) } returns songs(
            "One", "Two", totalCount = 1_200, complete = false, walkedAll = false, pageCapHit = true,
        )

        val step = fetcher.fetch(syncId = 1L, syncMode = SyncMode.REFRESH)

        coVerify(exactly = 5) { spotify.getAlbumTracks(any()) }
        val written = snapshots()
        assertEquals((1..5).map { key(it) }.toSet(), written.map { it.sourcePlaylistId }.toSet())
        assertTrue(written.all { it.partial && it.snapshotId == null })
        assertEquals(StepStatus.SUCCESS, step.status)
        assertEquals(0, step.unreadPlaylistCount)
        assertTrue(step.note!!, step.note!!.contains("5 read in part"))
    }

    @Test fun `Spotify listing fewer songs than it counts over an album already here is written in part`() = runTest {
        mirroredAlbum(1, addedAt = "2026-10-01T10:00:00Z")
        listing(listOf(album(1)))
        coEvery { spotify.getAlbumTracks(any()) } returns songs("A1", totalCount = 2, complete = false, walkedAll = false)

        val step = fetcher.fetch(syncId = 1L, syncMode = SyncMode.REFRESH)

        assertTrue(snapshots().single().partial)
        assertEquals(StepStatus.SUCCESS, step.status)
    }

    @Test fun `at most 200 albums are read a sync, the rest wait in the library's order`() = runTest {
        val many = (1..SpotifySavedAlbumsFetcher.MAX_READS_PER_SYNC + 5).map { album(it) }
        listing(many.take(50), many.drop(50).take(50), many.drop(100).take(50), many.drop(150).take(50), many.drop(200))
        coEvery { spotify.getAlbumTracks(any()) } returns songs("One")

        val step = fetcher.fetch(syncId = 1L, syncMode = SyncMode.REFRESH)

        coVerify(exactly = SpotifySavedAlbumsFetcher.MAX_READS_PER_SYNC) { spotify.getAlbumTracks(any()) }
        // The first 200 the listing gave (snapshots come back by name, so compared as a set).
        val written = snapshots().map { it.sourcePlaylistId }.toSet()
        assertEquals(many.take(SpotifySavedAlbumsFetcher.MAX_READS_PER_SYNC).map { "album:spotify:${it.id}" }.toSet(), written)
        assertTrue(step.note!!, step.note!!.contains("5 left for the next sync"))
        assertEquals(StepStatus.SUCCESS, step.status)
    }

    @Test fun `songs last read over 90 days ago are read again, the rest aren't`() = runTest {
        mirroredAlbum(1, addedAt = "2026-10-11T10:00:00Z", ageMs = 100 * day)
        mirroredAlbum(2, addedAt = "2026-10-12T10:00:00Z", ageMs = 10 * day)
        listing(listOf(album(1), album(2)))
        coEvery { spotify.getAlbumTracks(any()) } returns songs("A1", "B1")

        val step = fetcher.fetch(syncId = 1L, syncMode = SyncMode.REFRESH)

        coVerify(exactly = 1) { spotify.getAlbumTracks("Alb1".padEnd(22, 'x')) }
        coVerify(exactly = 0) { spotify.getAlbumTracks("Alb2".padEnd(22, 'x')) }
        assertTrue(step.note!!, step.note!!.contains("1 re-read after 90 days"))
        assertTrue(step.note!!, step.note!!.contains("1 unchanged"))
    }

    @Test fun `an album no longer listed never holds a re-read place`() = runTest {
        // Un-saved on Spotify in Accumulate: still here, stamped and read longest ago, never listed.
        repeat(SpotifySavedAlbumsFetcher.REFRESHES_PER_SYNC) {
            mirroredAlbum(100 + it, addedAt = "2026-01-01T10:00:00Z", ageMs = 400 * day)
        }
        mirroredAlbum(1, addedAt = "2026-10-11T10:00:00Z", ageMs = 100 * day)
        listing(listOf(album(1)))
        coEvery { spotify.getAlbumTracks(any()) } returns songs("A1", "B1")

        val step = fetcher.fetch(syncId = 1L, syncMode = SyncMode.ACCUMULATE)

        coVerify(exactly = 1) { spotify.getAlbumTracks(album(1).id) }
        assertTrue(step.note!!, step.note!!.contains("1 re-read after 90 days"))
    }

    @Test fun `at most 100 albums are re-read a sync for their age, in the listing's order`() = runTest {
        val n = SpotifySavedAlbumsFetcher.REFRESHES_PER_SYNC + 2
        for (i in 1..n) mirroredAlbum(i, addedAt = "2026-10-01T10:00:00Z", ageMs = 100 * day)
        val albums = (1..n).map { album(it, addedAt = "2026-10-01T10:00:00Z") }
        listing(*albums.chunked(SpotifySavedAlbumsFetcher.PAGE_SIZE).toTypedArray())
        coEvery { spotify.getAlbumTracks(any()) } returns songs("A", "B")

        val step = fetcher.fetch(syncId = 1L, syncMode = SyncMode.REFRESH)

        coVerify(exactly = SpotifySavedAlbumsFetcher.REFRESHES_PER_SYNC) { spotify.getAlbumTracks(any()) }
        coVerify(exactly = 0) { spotify.getAlbumTracks(album(n).id) }
        assertTrue(step.note!!, step.note!!.contains("${SpotifySavedAlbumsFetcher.REFRESHES_PER_SYNC} re-read after 90 days"))
        assertTrue(step.note!!, step.note!!.contains("2 unchanged"))
    }

    @Test fun `the listing is asked newest save first, and the walk keeps the order Spotify gave`() = runTest {
        // Spotify refused ours and answered in its default order: the next page is asked in that one.
        listing(listOf(album(1)), listOf(album(2)), order = "Recents")
        coEvery { spotify.getAlbumTracks(any()) } returns songs("One")

        fetcher.fetch(syncId = 1L, syncMode = SyncMode.REFRESH)

        coVerify { spotify.getSavedAlbumsPage(any(), 0, SpotifyApiClient.SAVED_ALBUMS_ORDER) }
        coVerify { spotify.getSavedAlbumsPage(any(), 1, "Recents") }
    }

    @Test fun `a page in another order is another listing, and hides nothing`() = runTest {
        val gone = row(key(9))
        coEvery { spotify.getSavedAlbumsPage(any(), 0, any()) } returns
            SpotifySavedAlbumsPage(listOf(album(1)), listOf(album(1).id), 1, 2, 0, sortOrder = "Recently Added")
        coEvery { spotify.getSavedAlbumsPage(any(), 1, any()) } returns
            SpotifySavedAlbumsPage(listOf(album(2)), listOf(album(2).id), 1, 2, 1, sortOrder = "Recents")
        coEvery { spotify.getAlbumTracks(any()) } returns songs("One")

        fetcher.fetch(syncId = 1L, syncMode = SyncMode.REFRESH)

        assertTrue(db.playlistDao().getById(gone)!!.isActive)
    }

    @Test fun `a whole walk not by date saved is no error, and hides nothing`() = runTest {
        val gone = row(key(9))
        coEvery { spotify.getAlbumTracks(any()) } returns songs("One")
        // Recents moves an album up each time it's played: one can slip between two pages.
        for (order in listOf("Recents", null)) {
            listing(listOf(album(1)), order = order)

            val step = fetcher.fetch(syncId = 1L, syncMode = SyncMode.REFRESH)

            assertTrue("order $order", db.playlistDao().getById(gone)!!.isActive)
            assertEquals(StepStatus.SUCCESS, step.status)
            assertTrue(step.note!!, step.note!!.contains("not by date saved"))
        }
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

    @Test fun `a pasted album the listener removed stays out, never read, written or brought back`() = runTest {
        // Remove hides a pasted Spotify album (SavedAlbums.removeHides) rather than freeing its key.
        val removed = row(key(1), source = MusicSource.BOTH, name = "Album 1")
        db.playlistDao().setUserHidden(removed, true)
        listing(listOf(album(1)))

        fetcher.fetch(syncId = 1L, syncMode = SyncMode.REFRESH)

        coVerify(exactly = 0) { spotify.getAlbumTracks(any()) }
        assertTrue(snapshots().isEmpty())
        val after = db.playlistDao().getById(removed)!!
        assertTrue(after.userHidden)
        assertFalse(after.isActive)
        assertEquals(MusicSource.BOTH, after.source)
    }

    @Test fun `a pasted album removed, then saved on Spotify after that, is the sync's again`() = runTest {
        val removed = row(key(1), source = MusicSource.BOTH, name = "Album 1")
        db.playlistDao().setUserHidden(removed, true)
        db.playlistDao().markPastedSpotifyAlbumRemoved(removed, addedAtMs("2026-10-20T10:00:00Z")!!)
        listing(listOf(album(1, addedAt = "2026-11-01T09:00:00Z")))
        coEvery { spotify.getAlbumTracks(any()) } returns songs("One", "Two")

        val step = fetcher.fetch(syncId = 1L, syncMode = SyncMode.REFRESH)

        val back = db.playlistDao().getById(removed)!!
        assertEquals(MusicSource.SPOTIFY, back.source)
        assertFalse(back.userHidden)
        assertNull("the removal note goes", back.snapshotId)
        assertEquals("read, for the diff to bring back", listOf(key(1)), snapshots().map { it.sourcePlaylistId })
        assertTrue(step.note!!, step.note!!.contains("1 you'd removed, saved on Spotify again"))
    }

    @Test fun `a pasted album removed after its Spotify save stays out`() = runTest {
        val removed = row(key(1), source = MusicSource.BOTH, name = "Album 1")
        db.playlistDao().setUserHidden(removed, true)
        val removedAt = addedAtMs("2026-10-20T10:00:00Z")!!
        db.playlistDao().markPastedSpotifyAlbumRemoved(removed, removedAt)
        listing(listOf(album(1, addedAt = "2026-10-11T10:00:00Z")))

        fetcher.fetch(syncId = 1L, syncMode = SyncMode.REFRESH)

        coVerify(exactly = 0) { spotify.getAlbumTracks(any()) }
        val after = db.playlistDao().getById(removed)!!
        assertEquals(MusicSource.BOTH, after.source)
        assertTrue(after.userHidden)
        assertEquals("removed:$removedAt", after.snapshotId)
    }

    @Test fun `the removal note and Spotify's save time read back, and nothing else passes for them`() {
        assertEquals(1234L, removedAtOf("removed:1234"))
        assertNull(removedAtOf(null))
        assertNull(removedAtOf("v2|added:x|REFRESH|1|1|abc"))
        assertNull(removedAtOf("removed:"))
        assertEquals(1_704_164_645_000L, addedAtMs("2024-01-02T03:04:05Z"))
        assertNull(addedAtMs("yesterday"))
        assertNull(addedAtMs(null))
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
        coVerify(exactly = 0) { spotify.getSavedAlbumsPage(any(), any(), any()) }
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
        coEvery { spotify.getSavedAlbumsPage(any(), 0, any()) } returns
            SpotifySavedAlbumsPage(listOf(album(1)), listOf(album(1).id), 1, 2, 0, sortOrder = SpotifyApiClient.SAVED_ALBUMS_ORDER)
        coEvery { spotify.getSavedAlbumsPage(any(), 1, any()) } returns SpotifySavedAlbumsPage.EMPTY
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
        coEvery { spotify.getSavedAlbumsPage(any(), 0, any()) } returns
            SpotifySavedAlbumsPage(listOf(album(1)), listOf(album(1).id), 1, 2, 0, sortOrder = SpotifyApiClient.SAVED_ALBUMS_ORDER)
        coEvery { spotify.getSavedAlbumsPage(any(), 1, any()) } returns
            SpotifySavedAlbumsPage(listOf(album(2)), listOf(album(2).id), 1, 3, 1, sortOrder = SpotifyApiClient.SAVED_ALBUMS_ORDER)
        coEvery { spotify.getSavedAlbumsPage(any(), 2, any()) } returns
            SpotifySavedAlbumsPage(listOf(album(3)), listOf(album(3).id), 1, 3, 2, sortOrder = SpotifyApiClient.SAVED_ALBUMS_ORDER)
        coEvery { spotify.getAlbumTracks(any()) } returns songs("One")

        fetcher.fetch(syncId = 1L, syncMode = SyncMode.REFRESH)

        assertTrue(db.playlistDao().getById(gone)!!.isActive)
    }

    @Test fun `an album listed but not readable (a pre-release) still counts as saved`() = runTest {
        val preRelease = row(key(2))
        coEvery { spotify.getSavedAlbumsPage(any(), 0, any()) } returns
            SpotifySavedAlbumsPage(listOf(album(1)), listOf(album(1).id, album(2).id), 2, 2, 0, sortOrder = SpotifyApiClient.SAVED_ALBUMS_ORDER)
        coEvery { spotify.getAlbumTracks(any()) } returns songs("One")

        fetcher.fetch(syncId = 1L, syncMode = SyncMode.REFRESH)

        assertTrue(db.playlistDao().getById(preRelease)!!.isActive)
        coVerify(exactly = 1) { spotify.getAlbumTracks(any()) }
    }

    @Test fun `a failed listing is an error and hides nothing`() = runTest {
        val gone = row(key(9))
        coEvery { spotify.getSavedAlbumsPage(any(), any(), any()) } returns SpotifySavedAlbumsPage.EMPTY

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
