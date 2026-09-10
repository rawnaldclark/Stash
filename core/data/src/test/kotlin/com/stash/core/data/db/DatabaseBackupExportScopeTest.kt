package com.stash.core.data.db

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

/**
 * Export used to be all-or-nothing: the whole database plus every settings
 * file, 12.7 MB on a real library. Most of that is machinery — sync undo
 * history, remote snapshots, response caches — that nobody wants to carry
 * to a new phone. These scopes let the export say what it is for, and
 * "likes only" is the small one: the tracks you marked, the liked playlists
 * they live in, and nothing else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DatabaseBackupExportScopeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: StashDatabase
    private lateinit var manager: DatabaseBackupManager
    private lateinit var out: File

    @Before fun setUp() {
        context.getDatabasePath(StashDatabase.DATABASE_NAME).parentFile?.mkdirs()
        db = Room.databaseBuilder(context, StashDatabase::class.java, StashDatabase.DATABASE_NAME)
            .allowMainThreadQueries()
            .build()
        manager = DatabaseBackupManager(context, db)
        out = File(context.cacheDir, "export-scope-${System.nanoTime()}.zip")
    }

    @After fun tearDown() {
        db.close()
        out.delete()
        context.getDatabasePath(StashDatabase.DATABASE_NAME).delete()
    }

    private suspend fun seed() {
        val liked = db.trackDao().insert(
            TrackEntity(
                title = "Marked", artist = "A", album = "Al", durationMs = 1,
                source = MusicSource.SPOTIFY, spotifyUri = "spotify:track:marked", stashLikedAt = 42L,
            ),
        )
        val inLikedPlaylist = db.trackDao().insert(
            TrackEntity(title = "In Liked Songs", artist = "B", album = "Al", durationMs = 1, source = MusicSource.SPOTIFY),
        )
        val unrelated = db.trackDao().insert(
            TrackEntity(title = "Just a mix track", artist = "C", album = "Al", durationMs = 1, source = MusicSource.SPOTIFY),
        )
        val likedSongs = db.playlistDao().insert(
            PlaylistEntity(name = "Liked Songs", source = MusicSource.SPOTIFY, sourceId = "liked", type = PlaylistType.LIKED_SONGS, isActive = true),
        )
        val mix = db.playlistDao().insert(
            PlaylistEntity(name = "Daily Discover", source = MusicSource.SPOTIFY, sourceId = "mix", type = PlaylistType.DAILY_MIX, isActive = true),
        )
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = likedSongs, trackId = inLikedPlaylist, position = 0))
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = mix, trackId = unrelated, position = 0))
    }

    private fun openExported(): Pair<ZipFile, StashDatabase> {
        val zip = ZipFile(out)
        val staged = File(context.cacheDir, "staged-${System.nanoTime()}.db")
        zip.getInputStream(zip.getEntry("stash.db")).use { input ->
            staged.outputStream().use { input.copyTo(it) }
        }
        val opened = Room.databaseBuilder(context, StashDatabase::class.java, staged.absolutePath)
            .allowMainThreadQueries()
            .build()
        return zip to opened
    }

    @Test fun `likes only keeps the marked tracks and the liked playlists, and drops the rest`() = runTest {
        seed()
        manager.exportDatabase(Uri.fromFile(out), BackupExportScope.LIKES_ONLY).getOrThrow()

        val (zip, exported) = openExported()
        try {
            assertTrue("settings must not ride along", zip.entries().toList().none { it.name.startsWith("datastore/") })
            val titles = exported.trackDao().getAllForIntegrityScan().map { it.title }.sorted()
            assertEquals(listOf("In Liked Songs", "Marked"), titles)
            val playlists = exported.playlistDao().getAllForBackupMerge().map { it.name }
            assertEquals(listOf("Liked Songs"), playlists)
        } finally {
            exported.close(); zip.close()
        }
    }

    @Test fun `library only carries the whole library but no settings`() = runTest {
        seed()
        manager.exportDatabase(Uri.fromFile(out), BackupExportScope.LIBRARY_ONLY).getOrThrow()

        val (zip, exported) = openExported()
        try {
            assertTrue(zip.entries().toList().none { it.name.startsWith("datastore/") })
            assertEquals(3, exported.trackDao().getAllForIntegrityScan().size)
            assertEquals(2, exported.playlistDao().getAllForBackupMerge().size)
        } finally {
            exported.close(); zip.close()
        }
    }

    @Test fun `settings only carries no database at all`() = runTest {
        seed()
        manager.exportDatabase(Uri.fromFile(out), BackupExportScope.SETTINGS_ONLY).getOrThrow()

        ZipFile(out).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertTrue("manifest is always present", "manifest.json" in names)
            assertTrue("no library in a settings-only archive", names.none { it == "stash.db" })
        }
    }

    @Test fun `the manifest records which scope produced the archive`() = runTest {
        seed()
        manager.exportDatabase(Uri.fromFile(out), BackupExportScope.LIKES_ONLY).getOrThrow()
        ZipFile(out).use { zip ->
            val manifest = zip.getInputStream(zip.getEntry("manifest.json")).readBytes().decodeToString()
            assertTrue("scope should be recorded, was: $manifest", "LIKES_ONLY" in manifest)
        }
    }

    @Test fun `merging likes onto songs the library already has applies them`() = runTest {
        seed()
        manager.exportDatabase(Uri.fromFile(out), BackupExportScope.LIKES_ONLY).getOrThrow()

        // A second library that already holds the same song, unliked — the
        // realistic case: you synced Spotify on the new phone before importing.
        val other = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val existing = other.trackDao().insert(
                TrackEntity(
                    title = "Marked", artist = "A", album = "Al", durationMs = 1,
                    source = MusicSource.SPOTIFY, spotifyUri = "spotify:track:marked",
                ),
            )
            assertEquals(null, other.trackDao().getById(existing)!!.stashLikedAt)

            val result = DatabaseBackupManager(context, other)
                .importDatabase(Uri.fromFile(out), BackupImportScope.LIBRARY_MERGE).getOrThrow()

            assertEquals(42L, other.trackDao().getById(existing)!!.stashLikedAt)
            assertEquals(1, result.likedTracks)
        } finally {
            other.close()
        }
    }
}
