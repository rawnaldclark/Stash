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

    private fun playlist(name: String, sourceId: String) = PlaylistEntity(
        name = name,
        source = MusicSource.SPOTIFY,
        sourceId = sourceId,
    )

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
}
