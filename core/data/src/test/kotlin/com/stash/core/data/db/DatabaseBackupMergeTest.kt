package com.stash.core.data.db

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests for [DatabaseBackupManager.importDatabase]'s LIBRARY_MERGE scope —
 * the issue #235 feature that adds a backup's songs/playlists INTO the
 * current library without deleting or replacing anything.
 *
 * Uses a REAL in-memory Room DB as the live library and a real on-disk Room
 * DB zipped into a backup archive, so the whole pipeline (manifest gate →
 * staging → integrity check → throwaway migrated instance → transactional
 * copy) executes exactly as it will on device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DatabaseBackupMergeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var tmpDir: File
    private lateinit var live: StashDatabase
    private lateinit var manager: DatabaseBackupManager

    @Before fun setUp() {
        tmpDir = File(context.cacheDir, "backup-merge-test-${System.nanoTime()}").apply { mkdirs() }
        live = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        manager = DatabaseBackupManager(context, live)
    }

    @After fun tearDown() {
        live.close()
        tmpDir.deleteRecursively()
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    private fun track(
        title: String,
        artist: String = "Artist",
        spotifyUri: String? = null,
        youtubeId: String? = null,
    ) = TrackEntity(
        title = title,
        artist = artist,
        spotifyUri = spotifyUri,
        youtubeId = youtubeId,
        canonicalTitle = title.lowercase(),
        canonicalArtist = artist.lowercase(),
    )

    /**
     * [lastSynced] is the discriminator the merge uses for membership
     * ownership: DiffWorker is its only writer, so non-null means a sync
     * run has mirrored this playlist and its `locally_added = 0` rows are
     * genuinely sync-owned.
     */
    private fun playlist(name: String, sourceId: String, lastSynced: Instant? = null) = PlaylistEntity(
        name = name,
        source = MusicSource.SPOTIFY,
        sourceId = sourceId,
        lastSynced = lastSynced,
    )

    /** `locally_added` of the membership joining [playlistId] to [title]. */
    private suspend fun flagFor(playlistId: Long, title: String): Boolean {
        val trackId = live.trackDao().getAllForIntegrityScan().first { it.title == title }.id
        return live.playlistDao().getCrossRefsForPlaylist(playlistId)
            .first { it.trackId == trackId }
            .locallyAdded
    }

    private suspend fun addMember(playlistId: Long, trackId: Long, position: Int) {
        live.playlistDao().insertCrossRef(
            PlaylistTrackCrossRef(
                playlistId = playlistId,
                trackId = trackId,
                position = position,
            )
        )
    }

    /** Populates an on-disk Room DB through [populate], closes it, and zips
     *  it up as a valid Stash backup (manifest + stash.db). */
    private suspend fun buildBackupZip(
        populate: suspend (StashDatabase) -> Unit,
    ): Uri {
        val dbFile = File(tmpDir, "backup-source.db")
        val backupDb = Room.databaseBuilder(context, StashDatabase::class.java, dbFile.absolutePath)
            // TRUNCATE — Robolectric's legacy SQLite cannot open file-backed
            // databases in WAL mode (see DatabaseBackupManager.mergeLibraryFrom).
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .allowMainThreadQueries()
            .build()
        backupDb.openHelper.writableDatabase // force schema creation
        populate(backupDb)
        val schemaVersion = backupDb.openHelper.readableDatabase.version
        backupDb.close()

        val zipFile = File(tmpDir, "backup.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(
                """{"dbSchemaVersion":$schemaVersion,"exportTimestamp":1,"appVersionName":"test"}"""
                    .toByteArray(),
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("stash.db"))
            FileInputStream(dbFile).use { it.copyTo(zip) }
            zip.closeEntry()
        }
        return Uri.fromFile(zipFile)
    }

    // ── Tests ────────────────────────────────────────────────────────────

    @Test
    fun `merge adds only missing tracks and unions memberships without touching settings`() =
        runTest {
            // Live: shared song S + live-only song X; playlist P1 holding S.
            val liveShared = live.trackDao().insert(track("Same Song", spotifyUri = "spotify:track:same"))
            live.trackDao().insert(track("Only Live", youtubeId = "yt-live"))
            val p1Live = live.playlistDao().insert(playlist("Road Trip", "sp-p1"))
            addMember(p1Live, liveShared, position = 0)

            // Backup: same S, backup-only song Y; P1 (renamed remotely!) now
            // holds [S, Y]; brand-new playlist P2 holding Y.
            val uri = buildBackupZip { backup ->
                val bShared = backup.trackDao().insert(track("Same Song", spotifyUri = "spotify:track:same"))
                val bY = backup.trackDao().insert(track("Only Backup", spotifyUri = "spotify:track:y"))
                val bP1 = backup.playlistDao().insert(playlist("Road Trip Renamed", "sp-p1"))
                backup.playlistDao().insertCrossRef(
                    PlaylistTrackCrossRef(playlistId = bP1, trackId = bShared, position = 0)
                )
                backup.playlistDao().insertCrossRef(
                    PlaylistTrackCrossRef(playlistId = bP1, trackId = bY, position = 1)
                )
                val bP2 = backup.playlistDao().insert(playlist("Fresh Finds", "custom-123"))
                backup.playlistDao().insertCrossRef(
                    PlaylistTrackCrossRef(playlistId = bP2, trackId = bY, position = 0)
                )
            }

            val result = manager.importDatabase(uri, BackupImportScope.LIBRARY_MERGE)

            assertTrue("merge failed: ${result.exceptionOrNull()}", result.isSuccess)
            val summary = result.getOrThrow()
            assertFalse("merge must not require a restart", summary.requiresRestart)
            assertNull(summary.restoredTreeUri)
            assertEquals(1, summary.addedTracks)
            assertEquals(1, summary.addedPlaylists)
            assertEquals(2, summary.mergedMemberships)

            // Library union: X + S + Y, with S NOT duplicated.
            val allTracks = live.trackDao().getAllForIntegrityScan()
            assertEquals(3, allTracks.size)
            assertEquals(
                listOf("Same Song"),
                allTracks.filter { it.spotifyUri == "spotify:track:same" }.map { it.title },
            )

            // Existing playlist keeps its live name; gains only Y, appended after S.
            val p1After = live.playlistDao().getById(p1Live)!!
            assertEquals("Road Trip", p1After.name)
            val ordered = live.playlistDao().getOrderedTrackIdsForPlaylist(p1Live)
            assertEquals(2, ordered.size)
            assertEquals(liveShared, ordered.first())
            assertEquals(
                "spotify:track:y",
                live.trackDao().getById(ordered.last())!!.spotifyUri,
            )
            assertEquals(2, p1After.trackCount)

            // New playlist arrives with its member and an accurate count…
            val p2After = live.playlistDao().findBySourceId("custom-123")!!
            val p2Members = live.playlistDao().getOrderedTrackIdsForPlaylist(p2After.id)
            assertEquals(listOf<String>("spotify:track:y"), p2Members.map {
                live.trackDao().getById(it)!!.spotifyUri
            })
            assertEquals(1, p2After.trackCount)
            // …but NEVER inherits download consent from a backup.
            assertFalse(p2After.syncEnabled)
        }

    @Test
    fun `merge never duplicates active members and preserves their order`() = runTest {
        val a = live.trackDao().insert(track("A", spotifyUri = "spotify:track:a"))
        val b = live.trackDao().insert(track("B", spotifyUri = "spotify:track:b"))
        val pid = live.playlistDao().insert(playlist("Mix", "mix-1"))
        addMember(pid, a, position = 0)
        addMember(pid, b, position = 1)

        // Backup has the SAME playlist but reversed order plus one new track.
        val uri = buildBackupZip { backup ->
            val bA = backup.trackDao().insert(track("A", spotifyUri = "spotify:track:a"))
            val bB = backup.trackDao().insert(track("B", spotifyUri = "spotify:track:b"))
            val bC = backup.trackDao().insert(track("C", spotifyUri = "spotify:track:c"))
            val bPid = backup.playlistDao().insert(playlist("Mix", "mix-1"))
            backup.playlistDao().insertCrossRef(PlaylistTrackCrossRef(bPid, bB, 0))
            backup.playlistDao().insertCrossRef(PlaylistTrackCrossRef(bPid, bA, 1))
            backup.playlistDao().insertCrossRef(PlaylistTrackCrossRef(bPid, bC, 2))
        }

        val result = manager.importDatabase(uri, BackupImportScope.LIBRARY_MERGE)

        assertTrue(result.isSuccess)
        val summary = result.getOrThrow()
        // Only C is new; A and B are already active members and must not be
        // re-added or reordered.
        assertEquals(1, summary.addedTracks)
        assertEquals(0, summary.addedPlaylists)
        assertEquals(1, summary.mergedMemberships)

        val tracks = live.trackDao().getAllForIntegrityScan()
        assertEquals(3, tracks.size)
        val ordered = live.playlistDao().getOrderedTrackIdsForPlaylist(pid)
        assertEquals(listOf(a, b), ordered.dropLast(1))
        assertEquals(
            "spotify:track:c",
            live.trackDao().getById(ordered.last())!!.spotifyUri,
        )
    }

    @Test
    fun `merge reactivates a soft-removed membership instead of duplicating`() = runTest {
        val a = live.trackDao().insert(track("A", spotifyUri = "spotify:track:a"))
        val b = live.trackDao().insert(track("B", spotifyUri = "spotify:track:b"))
        val pid = live.playlistDao().insert(playlist("List", "list-1"))
        addMember(pid, a, position = 0)
        addMember(pid, b, position = 1)
        live.playlistDao().softDeleteTrackFromPlaylist(pid, b) // user removed B here

        val uri = buildBackupZip { backup ->
            val bB = backup.trackDao().insert(track("B", spotifyUri = "spotify:track:b"))
            val bPid = backup.playlistDao().insert(playlist("List", "list-1"))
            backup.playlistDao().insertCrossRef(PlaylistTrackCrossRef(bPid, bB, 0))
        }

        val result = manager.importDatabase(uri, BackupImportScope.LIBRARY_MERGE)

        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrThrow().addedTracks)
        assertEquals(1, result.getOrThrow().mergedMemberships)

        // B is back — the backup's content was added, per #235 semantics.
        val ordered = live.playlistDao().getOrderedTrackIdsForPlaylist(pid)
        assertEquals(listOf(a, b), ordered)
        assertEquals(2, live.playlistDao().getById(pid)!!.trackCount)
    }

    @Test
    fun `a corrupt backup fails the merge and leaves the live library untouched`() = runTest {
        val a = live.trackDao().insert(track("Precious", spotifyUri = "spotify:track:p"))

        val zipFile = File(tmpDir, "corrupt.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"dbSchemaVersion":42,"exportTimestamp":1}""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("stash.db"))
            // Valid-looking header followed by junk — the shape the integrity
            // gate exists to reject deterministically.
            zip.write(byteArrayOf(0x53, 0x51, 0x4C, 0x69, 0x74, 0x65, 0x00, 0x01) + ByteArray(64))
            zip.closeEntry()
        }

        val result = manager.importDatabase(Uri.fromFile(zipFile), BackupImportScope.LIBRARY_MERGE)

        assertTrue(result.isFailure)
        // Live library byte-for-byte intact.
        val tracks = live.trackDao().getAllForIntegrityScan()
        assertEquals(listOf("Precious"), tracks.map { it.title })
        assertEquals(a, tracks.single().id)
        // No staging residue left behind.
        val dbPath = context.getDatabasePath(StashDatabase.DATABASE_NAME).parentFile
        assertTrue(
            File(dbPath, "${StashDatabase.DATABASE_NAME}.import-tmp").exists().not(),
        )
    }

    @Test
    fun `merge ignores a membership the backup itself soft-removed`() = runTest {
        val a = live.trackDao().insert(track("A", spotifyUri = "spotify:track:a"))
        val pid = live.playlistDao().insert(playlist("List", "list-1"))
        addMember(pid, a, position = 0)

        // The backup holds A and B, but B was REMOVED from the playlist there.
        val uri = buildBackupZip { backup ->
            val bA = backup.trackDao().insert(track("A", spotifyUri = "spotify:track:a"))
            val bB = backup.trackDao().insert(track("B", spotifyUri = "spotify:track:b"))
            val bPid = backup.playlistDao().insert(playlist("List", "list-1"))
            backup.playlistDao().insertCrossRef(PlaylistTrackCrossRef(bPid, bA, 0))
            backup.playlistDao().insertCrossRef(PlaylistTrackCrossRef(bPid, bB, 1))
            backup.playlistDao().softDeleteTrackFromPlaylist(bPid, bB)
        }

        val result = manager.importDatabase(uri, BackupImportScope.LIBRARY_MERGE)

        assertTrue(result.isSuccess)
        val summary = result.getOrThrow()
        // B's library row still arrives — merge adds tracks — but a membership
        // the backup no longer holds must not be resurrected as an active one.
        assertEquals(1, summary.addedTracks)
        assertEquals(0, summary.mergedMemberships)
        assertEquals(listOf(a), live.playlistDao().getOrderedTrackIdsForPlaylist(pid))
    }

    @Test
    fun `merged tracks never inherit the backup's downloaded state`() = runTest {
        // A backup ZIP carries metadata, never audio — and merge skips the
        // restart that would run the disk-truth sweep. Inheriting
        // is_downloaded would leave a row pointing at a file that isn't here.
        val uri = buildBackupZip { backup ->
            backup.trackDao().insert(
                track("Elsewhere", spotifyUri = "spotify:track:e").copy(
                    isDownloaded = true,
                    filePath = "/data/user/0/other.install/files/music/elsewhere.flac",
                    fileSizeBytes = 40_000_000,
                )
            )
        }

        val result = manager.importDatabase(uri, BackupImportScope.LIBRARY_MERGE)

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().addedTracks)
        val merged = live.trackDao().getAllForIntegrityScan().single()
        assertFalse(merged.isDownloaded)
        assertNull(merged.filePath)
        assertEquals(0L, merged.fileSizeBytes)
    }

    @Test
    fun `merged tracks keep no state describing a file they no longer have`() = runTest {
        // These columns all describe the audio FILE, not the catalog entry:
        // qualityKbps / sampleRateHz / bitsPerSample are read off it,
        // not from the source catalog. A merged row lands not-downloaded, so
        // keeping them lets a quality badge describe audio that isn't there
        // until adoption re-stamps the row.
        val uri = buildBackupZip { backup ->
            backup.trackDao().insert(
                track("Elsewhere", spotifyUri = "spotify:track:e").copy(
                    isDownloaded = true,
                    filePath = "/data/user/0/other.install/files/music/elsewhere.flac",
                    fileSizeBytes = 40_000_000,
                    qualityKbps = 1411,
                    sampleRateHz = 96_000,
                    bitsPerSample = 24,
                    albumArtPath = "/data/user/0/other.install/files/art/elsewhere.jpg",
                    metadataEmbeddedAt = 1_700_000_000_000L,
                )
            )
        }

        val result = manager.importDatabase(uri, BackupImportScope.LIBRARY_MERGE)

        assertTrue(result.isSuccess)
        val merged = live.trackDao().getAllForIntegrityScan().single()
        assertEquals(0, merged.qualityKbps)
        assertNull(merged.sampleRateHz)
        assertNull(merged.bitsPerSample)
        assertNull(merged.albumArtPath)
        // Not just untidy: getTracksNeedingEmbed and the Home banner count
        // both gate on `metadata_embedded_at IS NULL`, so a stamp carried in
        // from the backup means the file adoption creates later NEVER gets
        // the v0.9.35 tag set — and there is no stale-stamp sweep to heal it.
        assertNull(merged.metadataEmbeddedAt)
    }

    @Test
    fun `a sync-owned membership stays sync-owned so REFRESH can still drop it`() = runTest {
        // locally_added = 1 makes a membership immune to
        // PlaylistDao.clearSyncedPlaylistTracks AND to
        // SyncUndoDao.clearSyncedMembershipForRestore. Forcing it on every
        // merged row pinned a stale backup's tracks into a synced playlist
        // forever, so a sync-owned row keeps the backup's honest 0.
        val pid = live.playlistDao().insert(playlist("List", "list-1", lastSynced = Instant.now()))
        val uri = buildBackupZip { backup ->
            val synced = backup.trackDao().insert(track("Synced", spotifyUri = "spotify:track:s"))
            val byHand = backup.trackDao().insert(track("ByHand", spotifyUri = "spotify:track:h"))
            val bPid = backup.playlistDao().insert(playlist("List", "list-1"))
            backup.playlistDao().insertCrossRef(
                PlaylistTrackCrossRef(bPid, synced, position = 0, locallyAdded = false),
            )
            backup.playlistDao().insertCrossRef(
                PlaylistTrackCrossRef(bPid, byHand, position = 1, locallyAdded = true),
            )
        }

        val result = manager.importDatabase(uri, BackupImportScope.LIBRARY_MERGE)

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrThrow().mergedMemberships)
        assertFalse(flagFor(pid, "Synced"))
        assertTrue(flagFor(pid, "ByHand"))
    }

    @Test
    fun `the backup's own sync history settles ownership when the live row has none`() = runTest {
        // Sync enabled here but never run, while the backup's copy of the
        // same playlist HAS synced. The backup's 0 rows were written by that
        // sync, so they are genuinely sync-owned — forcing them to 1 because
        // the LIVE row has no last_synced yet would pin them past the next
        // REFRESH, which is the bug this whole thread started on.
        val pid = live.playlistDao().insert(playlist("List", "list-1", lastSynced = null))
        val uri = buildBackupZip { backup ->
            val t = backup.trackDao().insert(track("Remote", spotifyUri = "spotify:track:r"))
            val bPid = backup.playlistDao().insert(
                playlist("List", "list-1", lastSynced = Instant.now()),
            )
            backup.playlistDao().insertCrossRef(
                PlaylistTrackCrossRef(bPid, t, position = 0, locallyAdded = false),
            )
        }

        val result = manager.importDatabase(uri, BackupImportScope.LIBRARY_MERGE)

        assertTrue(result.isSuccess)
        assertFalse(flagFor(pid, "Remote"))
    }

    @Test
    fun `a playlist no sync has mirrored takes every membership as user-added`() = runTest {
        // No sync writes memberships into a playlist it does not mirror, so
        // every live add path stamps locally_added = 1 there and a 0 is a
        // state nothing else in the schema can produce. A backup predating
        // the column (MIGRATION_22_23 added it DEFAULT 0) reads 0 even for
        // rows the user added by hand, and importing that verbatim would
        // expose them to SyncUndoDao.clearSyncedMembershipForRestore, whose
        // DELETE is playlist-type-blind. last_synced is the discriminator:
        // DiffWorker is its only writer, so null means no sync has ever
        // touched this playlist.
        val pid = live.playlistDao().insert(playlist("Mine", "custom_abc", lastSynced = null))
        val uri = buildBackupZip { backup ->
            val t = backup.trackDao().insert(track("Mine", spotifyUri = "spotify:track:m"))
            val bPid = backup.playlistDao().insert(playlist("Mine", "custom_abc"))
            backup.playlistDao().insertCrossRef(
                PlaylistTrackCrossRef(bPid, t, position = 0, locallyAdded = false),
            )
        }

        val result = manager.importDatabase(uri, BackupImportScope.LIBRARY_MERGE)

        assertTrue(result.isSuccess)
        assertTrue(flagFor(pid, "Mine"))
    }

    @Test
    fun `reactivating a membership never downgrades the live row's provenance`() = runTest {
        // The live row is the user's own hand-add, soft-deleted. The backup
        // holds the same membership as sync-added. Reactivation must not let
        // the backup's 0 overwrite the live 1 — that would hand a row the
        // user created to the next REFRESH or sync-undo.
        val pid = live.playlistDao().insert(playlist("List", "list-1", lastSynced = Instant.now()))
        val tid = live.trackDao().insert(track("Song", spotifyUri = "spotify:track:x"))
        live.playlistDao().insertCrossRef(
            PlaylistTrackCrossRef(pid, tid, position = 0, locallyAdded = true),
        )
        live.playlistDao().softDeleteTrackFromPlaylist(pid, tid)
        val uri = buildBackupZip { backup ->
            val bt = backup.trackDao().insert(track("Song", spotifyUri = "spotify:track:x"))
            val bPid = backup.playlistDao().insert(playlist("List", "list-1"))
            backup.playlistDao().insertCrossRef(
                PlaylistTrackCrossRef(bPid, bt, position = 0, locallyAdded = false),
            )
        }

        val result = manager.importDatabase(uri, BackupImportScope.LIBRARY_MERGE)

        assertTrue(result.isSuccess)
        assertTrue(flagFor(pid, "Song"))
    }

    @Test
    fun `a settings-only import of a backup that carries no settings fails`() = runTest {
        // buildBackupZip writes manifest.json + stash.db and no datastore/
        // entries at all. Reporting success here would tell the user their
        // preferences were restored when nothing was written — the DB path
        // already refuses the mirror case ("contains no database").
        live.trackDao().insert(track("Keep me", spotifyUri = "spotify:track:k"))
        val uri = buildBackupZip { backup -> backup.trackDao().insert(track("A")) }

        val result = manager.importDatabase(uri, BackupImportScope.SETTINGS_REPLACE)

        assertTrue(result.isFailure)
        assertEquals(1, live.trackDao().getAllForIntegrityScan().size)
    }
}
