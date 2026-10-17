package com.stash.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.stash.core.data.db.AllSongsMigrationFixtures.TRACKS_INDEX
import com.stash.core.data.db.AllSongsMigrationFixtures.assertLibraryUnchanged
import com.stash.core.data.db.AllSongsMigrationFixtures.columns
import com.stash.core.data.db.AllSongsMigrationFixtures.count
import com.stash.core.data.db.AllSongsMigrationFixtures.indexes
import com.stash.core.data.db.AllSongsMigrationFixtures.makeDebugV56
import com.stash.core.data.db.AllSongsMigrationFixtures.makeDebugV57
import com.stash.core.data.db.AllSongsMigrationFixtures.makeFirstDebugV55
import com.stash.core.data.db.AllSongsMigrationFixtures.seedLibrary
import com.stash.core.data.db.AllSongsMigrationFixtures.seedScanV57Shape
import com.stash.core.data.db.AllSongsMigrationFixtures.seedTidalMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v57 -> v58 adds `scanned_songs.album_art_path`, the file name of a scanned song's embedded cover:
 * NULL on existing rows ("not looked at yet"). Also every way a phone reaches 58: master's v55 and
 * v56, and each of the All Songs PR's debug databases (see [AllSongsMigrationFixtures]). Every run
 * validates all tables and indices against the exported 58.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV57V58Test {

    private val DB_NAME = "migration-v57v58-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val toLatestFrom56 = arrayOf(StashDatabase.MIGRATION_56_57, StashDatabase.MIGRATION_57_58)
    private val toLatestFrom55 = arrayOf(StashDatabase.MIGRATION_55_56, *toLatestFrom56)

    @Test fun `57 to 58 keeps the folder and song and leaves the cover NULL`() {
        helper.createDatabase(DB_NAME, 57).use {
            seedLibrary(it)
            seedScanV57Shape(it)
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 58, true, StashDatabase.MIGRATION_57_58)

        db.query(
            "SELECT id, uri, title, album, duration_ms, file_format, file_size_bytes, last_modified, date_added, " +
                "album_art_path FROM scanned_songs",
        ).use { c ->
            assertEquals(1, c.count)
            assertTrue(c.moveToFirst())
            assertEquals(5L, c.getLong(0))
            assertEquals("content://t/tree/a/document/x", c.getString(1))
            assertEquals("T", c.getString(2))
            assertEquals("Al", c.getString(3))
            assertEquals(2000L, c.getLong(4))
            assertEquals("flac", c.getString(5))
            assertEquals(5L, c.getLong(6))
            assertEquals(7L, c.getLong(7))
            assertEquals(99L, c.getLong(8))
            assertTrue("a row from before covers is 'not looked at yet'", c.isNull(9))
        }
        assertEquals(1, count(db, "SELECT COUNT(*) FROM scan_folders"))
        assertLibraryUnchanged(db)
    }

    @Test fun `the new column takes a file name, an empty string and NULL`() {
        helper.createDatabase(DB_NAME, 57).use { seedScanV57Shape(it) }
        val db = helper.runMigrationsAndValidate(DB_NAME, 58, true, StashDatabase.MIGRATION_57_58)

        for (value in listOf("'scanned_abc.jpg'", "''")) {
            db.execSQL("UPDATE scanned_songs SET album_art_path = $value WHERE id = 5")
            db.query("SELECT album_art_path FROM scanned_songs WHERE id = 5").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(value.trim('\''), c.getString(0))
            }
        }
        db.execSQL("UPDATE scanned_songs SET album_art_path = NULL WHERE id = 5")
        db.query("SELECT album_art_path FROM scanned_songs WHERE id = 5").use { c ->
            assertTrue(c.moveToFirst())
            assertNull(c.getString(0))
        }
    }

    @Test fun `master's v56 as shipped reaches 58 with its library and Tidal cache`() {
        helper.createDatabase(DB_NAME, 56).use {
            seedLibrary(it)
            seedTidalMatch(it)
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 58, true, *toLatestFrom56)

        assertTrue("album_art_path" in columns(db, "scanned_songs"))
        assertEquals(1, count(db, "SELECT COUNT(*) FROM tidal_match_cache"))
        assertLibraryUnchanged(db)
    }

    @Test fun `master's v55 reaches 58 through every step, library unchanged`() {
        helper.createDatabase(DB_NAME, 55).use { seedLibrary(it) }

        val db = helper.runMigrationsAndValidate(DB_NAME, 58, true, *toLatestFrom55)

        assertEquals(0, count(db, "SELECT COUNT(*) FROM scanned_songs"))
        assertEquals(0, count(db, "SELECT COUNT(*) FROM tidal_match_cache"))
        assertTrue(TRACKS_INDEX in indexes(db, "tracks"))
        assertLibraryUnchanged(db)
    }

    @Test fun `the All Songs debug v57 is healed and keeps its folders, songs and covers`() {
        helper.createDatabase(DB_NAME, 55).use {
            seedLibrary(it)
            makeDebugV57(it)
        }

        // Validation fails if master's tidal_match_cache wasn't added.
        val db = helper.runMigrationsAndValidate(DB_NAME, 58, true, StashDatabase.MIGRATION_57_58)

        assertEquals(1, count(db, "SELECT COUNT(*) FROM scan_folders"))
        db.query("SELECT album_art_path FROM scanned_songs WHERE id = 5").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("scanned_abc.jpg", c.getString(0))
        }
        assertEquals(1, columns(db, "scanned_songs").count { it == "album_art_path" })
        assertLibraryUnchanged(db)
    }

    @Test fun `the All Songs debug v56 reaches 58`() {
        helper.createDatabase(DB_NAME, 55).use {
            seedLibrary(it)
            makeDebugV56(it)
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 58, true, *toLatestFrom56)

        assertTrue("album_art_path" in columns(db, "scanned_songs"))
        assertLibraryUnchanged(db)
    }

    @Test fun `the All Songs first debug v55 reaches 58 through master's 55 to 56`() {
        helper.createDatabase(DB_NAME, 55).use {
            seedLibrary(it)
            makeFirstDebugV55(it)
        }

        // Validation fails if the tracks index is still missing or the old scanned_songs shape survived.
        val db = helper.runMigrationsAndValidate(DB_NAME, 58, true, *toLatestFrom55)

        assertTrue(TRACKS_INDEX in indexes(db, "tracks"))
        assertTrue("last_modified" in columns(db, "scanned_songs"))
        assertEquals("the scan cache is refilled by the next scan", 0, count(db, "SELECT COUNT(*) FROM scanned_songs"))
        assertLibraryUnchanged(db)
    }
}
