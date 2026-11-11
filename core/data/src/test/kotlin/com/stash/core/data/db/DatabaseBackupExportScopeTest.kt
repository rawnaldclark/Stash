package com.stash.core.data.db

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

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

    /**
     * Export no longer zips the live file: it stages a snapshot (main file +
     * WAL, copied under a write lock), folds the WAL into it, and verifies it.
     * The archived file must therefore stand alone — it is opened here with no
     * WAL beside it — and the staging copy must not be left in the cache.
     */
    @Test fun `the archived database is a self-contained verified snapshot and staging is cleaned up`() = runTest {
        seed()
        // A write after the last checkpoint: it lives in the live WAL until
        // something folds it, so a main-file-only copy would miss it.
        db.trackDao().insert(
            TrackEntity(title = "Late write", artist = "D", album = "Al", durationMs = 1, source = MusicSource.SPOTIFY),
        )
        manager.exportDatabase(Uri.fromFile(out), BackupExportScope.LIBRARY_ONLY).getOrThrow()

        assertTrue(
            "staging snapshot must be deleted",
            context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("export-") && it.name.contains(".db") },
        )
        val (zip, exported) = openExported()
        try {
            assertTrue(exported.trackDao().getAllForIntegrityScan().any { it.title == "Late write" })
            assertEquals(4, exported.trackDao().getAllForIntegrityScan().size)
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

    @Test fun `settings leave out the Community key and DataStore's leftover copy of it`() = runTest {
        val theme = context.preferencesDataStoreFile("theme_preferences")
        val key = context.preferencesDataStoreFile("community_preference")
        val leftover = File(key.path + ".tmp") // DataStore's scratch file, left behind if a write is cut short
        theme.parentFile?.mkdirs()
        theme.writeText("theme")
        key.writeText("key")
        leftover.writeText("key")
        try {
            manager.exportDatabase(Uri.fromFile(out), BackupExportScope.SETTINGS_ONLY).getOrThrow()

            ZipFile(out).use { zip ->
                val names = zip.entries().toList().map { it.name }
                assertTrue("other settings still ride along", "datastore/theme_preferences.preferences_pb" in names)
                assertTrue("the Community key must not", "datastore/community_preference.preferences_pb" !in names)
                assertTrue("nor its leftover copy", "datastore/community_preference.preferences_pb.tmp" !in names)
            }
        } finally {
            theme.delete()
            key.delete()
            leftover.delete()
        }
    }

    @Test fun `settings leave out the supporter token but keep the install id and the day's lossless count`() = runTest {
        val supporter = context.preferencesDataStoreFile("supporter")
        val leftover = File(supporter.path + ".tmp")
        val config = context.preferencesDataStoreFile("lossless_relay_config")
        val quota = context.preferencesDataStoreFile("lossless_quota")
        supporter.parentFile?.mkdirs()
        for (f in listOf(supporter, leftover, config, quota)) f.writeText(f.name)
        try {
            manager.exportDatabase(Uri.fromFile(out), BackupExportScope.SETTINGS_ONLY).getOrThrow()

            ZipFile(out).use { zip ->
                val names = zip.entries().toList().map { it.name }
                assertTrue("the supporter token must not", "datastore/supporter.preferences_pb" !in names)
                assertTrue("nor its leftover copy", "datastore/supporter.preferences_pb.tmp" !in names)
                assertTrue("the install id rides along", "datastore/lossless_relay_config.preferences_pb" in names)
                assertTrue("so does the day's count", "datastore/lossless_quota.preferences_pb" in names)
            }
        } finally {
            for (f in listOf(supporter, leftover, config, quota)) f.delete()
        }
    }

    @Test fun `settings leave out the download pause, which belongs to this phone's queue`() = runTest {
        // Restored onto a new phone, a pause or an out-of-space hold would stop every download
        // there with nothing on screen saying why.
        val queueState = context.preferencesDataStoreFile("download_queue_state")
        val theme = context.preferencesDataStoreFile("theme_preferences")
        queueState.parentFile?.mkdirs()
        for (f in listOf(queueState, theme)) f.writeText(f.name)
        try {
            manager.exportDatabase(Uri.fromFile(out), BackupExportScope.SETTINGS_ONLY).getOrThrow()

            ZipFile(out).use { zip ->
                val names = zip.entries().toList().map { it.name }
                assertTrue("the pause must not", "datastore/download_queue_state.preferences_pb" !in names)
                assertTrue("other settings still do", "datastore/theme_preferences.preferences_pb" in names)
            }
        } finally {
            for (f in listOf(queueState, theme)) f.delete()
        }
    }

    @Test fun `a settings restore ignores a supporter token in an older archive`() = runTest {
        val zipFile = File(context.cacheDir, "old-settings-${System.nanoTime()}.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"dbSchemaVersion":1,"exportTimestamp":1,"scope":"SETTINGS_ONLY"}""".toByteArray())
            zip.closeEntry()
            for (name in listOf("theme_preferences.preferences_pb", "supporter.preferences_pb", "supporter.preferences_pb.tmp")) {
                zip.putNextEntry(ZipEntry("datastore/$name"))
                zip.write(name.toByteArray())
                zip.closeEntry()
            }
        }
        val theme = context.preferencesDataStoreFile("theme_preferences")
        val supporter = context.preferencesDataStoreFile("supporter")
        val leftover = File(supporter.path + ".tmp")
        theme.parentFile?.mkdirs()
        theme.delete()
        supporter.delete()
        try {
            val result = manager.importDatabase(Uri.fromFile(zipFile), BackupImportScope.SETTINGS_REPLACE)

            assertTrue("restore failed: ${result.exceptionOrNull()}", result.isSuccess)
            assertEquals("theme_preferences.preferences_pb", theme.readText())
            assertFalse("the archive's supporter token is not installed", supporter.exists())
            assertFalse("nor its leftover copy", leftover.exists())
        } finally {
            theme.delete()
            supporter.delete()
            leftover.delete()
            zipFile.delete()
        }
    }

    // ---------------------------------------------------------------- add-ons phase 4 §2.10: no login in a backup

    /** A real preferences file, as DataStore writes it, holding the old Custom Tidal server and other lossless settings. */
    private fun losslessPrefsBytes(withLogin: Boolean): ByteArray {
        val f = File(context.cacheDir, "lossless-src-${System.nanoTime()}.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            runBlocking {
                PreferenceDataStoreFactory.create(scope = scope) { f }.edit {
                    it[booleanPreferencesKey("enabled")] = false
                    it[stringPreferencesKey("lossless_quality_tier")] = "HI_RES"
                    if (withLogin) {
                        it[stringPreferencesKey("bitchord_endpoint")] = "https://tidal.mine.example/backup-test-path"
                        it[stringPreferencesKey("bitchord_access_token")] = "backup-test-token"
                    }
                }
            }
            return f.readBytes()
        } finally {
            scope.cancel()
            f.delete()
        }
    }

    /** [bytes] read back by DataStore. */
    private fun readPrefs(bytes: ByteArray): Preferences {
        val f = File(context.cacheDir, "lossless-read-${System.nanoTime()}.preferences_pb").apply { writeBytes(bytes) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            return runBlocking { PreferenceDataStoreFactory.create(scope = scope) { f }.data.first() }
        } finally {
            scope.cancel()
            f.delete()
        }
    }

    @Test fun `settings leave out the add-ons' own files`() = runTest {
        val names = listOf("user_addons", "addon_manifests", "addon_index")
        val files = names.flatMap { n -> context.preferencesDataStoreFile(n).let { listOf(it, File(it.path + ".tmp")) } }
        val theme = context.preferencesDataStoreFile("theme_preferences")
        theme.parentFile?.mkdirs()
        for (f in files + theme) f.writeText(f.name)
        try {
            manager.exportDatabase(Uri.fromFile(out), BackupExportScope.SETTINGS_ONLY).getOrThrow()
            ZipFile(out).use { zip ->
                val inZip = zip.entries().toList().map { it.name }
                assertTrue("other settings still ride along", "datastore/theme_preferences.preferences_pb" in inZip)
                for (f in files) assertTrue("${f.name} must not", "datastore/${f.name}" !in inZip)
            }
        } finally {
            for (f in files + theme) f.delete()
        }
    }

    @Test fun `a backup never carries the own Tidal server or its token, but keeps the other lossless settings`() = runTest {
        val lossless = context.preferencesDataStoreFile("lossless_source_preferences")
        lossless.parentFile?.mkdirs()
        lossless.writeBytes(losslessPrefsBytes(withLogin = true))
        try {
            manager.exportDatabase(Uri.fromFile(out), BackupExportScope.SETTINGS_ONLY).getOrThrow()
            ZipFile(out).use { zip ->
                val entry = zip.getEntry("datastore/lossless_source_preferences.preferences_pb")
                assertTrue("the lossless settings still ride along", entry != null)
                val bytes = zip.getInputStream(entry).readBytes()
                val text = bytes.toString(Charsets.ISO_8859_1)
                assertFalse("no token", text.contains("backup-test-token"))
                assertFalse("no address", text.contains("backup-test-path"))
                val p = readPrefs(bytes)
                assertEquals(null, p[stringPreferencesKey("bitchord_endpoint")])
                assertEquals(null, p[stringPreferencesKey("bitchord_access_token")])
                assertEquals(false, p[booleanPreferencesKey("enabled")])
                assertEquals("HI_RES", p[stringPreferencesKey("lossless_quality_tier")])
            }
            // The phone's own file is untouched: the export only leaves the keys out of the archive.
            assertTrue(lossless.readBytes().toString(Charsets.ISO_8859_1).contains("backup-test-token"))
        } finally {
            lossless.delete()
        }
    }

    @Test fun `a lossless settings file that can't be read is left out of a backup, not carried whole`() = runTest {
        val lossless = context.preferencesDataStoreFile("lossless_source_preferences")
        val leftover = File(lossless.path + ".tmp")
        lossless.parentFile?.mkdirs()
        lossless.writeText("not a preferences file")
        leftover.writeBytes(losslessPrefsBytes(withLogin = true).let { it.copyOf(it.size - 3) }) // a write cut short
        try {
            manager.exportDatabase(Uri.fromFile(out), BackupExportScope.SETTINGS_ONLY).getOrThrow()
            ZipFile(out).use { zip ->
                val inZip = zip.entries().toList().map { it.name }
                assertTrue("datastore/lossless_source_preferences.preferences_pb" !in inZip)
                assertTrue("datastore/lossless_source_preferences.preferences_pb.tmp" !in inZip)
            }
        } finally {
            lossless.delete()
            leftover.delete()
        }
    }

    @Test fun `restoring an old archive never brings back the own Tidal server, its token, or the add-ons`() = runTest {
        // An archive from before the move: the lossless settings with the old server in them,
        // and (as no build wrote, but a crafted file could) the sealed add-on store.
        val zipFile = File(context.cacheDir, "old-own-server-${System.nanoTime()}.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"dbSchemaVersion":1,"exportTimestamp":1,"scope":"SETTINGS_ONLY"}""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("datastore/lossless_source_preferences.preferences_pb"))
            zip.write(losslessPrefsBytes(withLogin = true))
            zip.closeEntry()
            for (name in listOf("user_addons.preferences_pb", "addon_manifests.preferences_pb")) {
                zip.putNextEntry(ZipEntry("datastore/$name"))
                zip.write(name.toByteArray())
                zip.closeEntry()
            }
        }
        val lossless = context.preferencesDataStoreFile("lossless_source_preferences")
        val users = context.preferencesDataStoreFile("user_addons")
        val manifests = context.preferencesDataStoreFile("addon_manifests")
        lossless.parentFile?.mkdirs()
        for (f in listOf(lossless, users, manifests)) f.delete()
        try {
            val result = manager.importDatabase(Uri.fromFile(zipFile), BackupImportScope.SETTINGS_REPLACE)

            assertTrue("restore failed: ${result.exceptionOrNull()}", result.isSuccess)
            val p = readPrefs(lossless.readBytes())
            assertEquals(null, p[stringPreferencesKey("bitchord_endpoint")])
            assertEquals(null, p[stringPreferencesKey("bitchord_access_token")])
            assertEquals("the other lossless settings are restored", false, p[booleanPreferencesKey("enabled")])
            assertFalse(lossless.readBytes().toString(Charsets.ISO_8859_1).contains("backup-test-token"))
            assertFalse("the sealed add-ons are not installed", users.exists())
            assertFalse("nor the kept manifests", manifests.exists())
        } finally {
            for (f in listOf(lossless, users, manifests)) f.delete()
            zipFile.delete()
        }
    }

    @Test fun `an unreadable lossless settings file in an archive is not restored`() = runTest {
        val zipFile = File(context.cacheDir, "bad-lossless-${System.nanoTime()}.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"dbSchemaVersion":1,"exportTimestamp":1,"scope":"SETTINGS_ONLY"}""".toByteArray())
            zip.closeEntry()
            for ((name, body) in listOf(
                "theme_preferences.preferences_pb" to "theme".toByteArray(),
                "lossless_source_preferences.preferences_pb" to "garbage".toByteArray(),
            )) {
                zip.putNextEntry(ZipEntry("datastore/$name"))
                zip.write(body)
                zip.closeEntry()
            }
        }
        val theme = context.preferencesDataStoreFile("theme_preferences")
        val lossless = context.preferencesDataStoreFile("lossless_source_preferences")
        theme.parentFile?.mkdirs()
        lossless.writeBytes(losslessPrefsBytes(withLogin = false))
        val before = lossless.readBytes()
        try {
            val result = manager.importDatabase(Uri.fromFile(zipFile), BackupImportScope.SETTINGS_REPLACE)
            assertTrue("restore failed: ${result.exceptionOrNull()}", result.isSuccess)
            assertEquals("theme", theme.readText())
            assertTrue("the phone keeps its own lossless settings", lossless.readBytes().contentEquals(before))
            assertFalse(File(lossless.path + ".import-tmp").exists())
        } finally {
            theme.delete()
            lossless.delete()
            zipFile.delete()
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
