package com.stash.core.data.db

import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.stash.core.data.db.AllSongsMigrationFixtures.TRACKS_INDEX
import com.stash.core.data.db.AllSongsMigrationFixtures.assertLibraryUnchanged
import com.stash.core.data.db.AllSongsMigrationFixtures.columns
import com.stash.core.data.db.AllSongsMigrationFixtures.count
import com.stash.core.data.db.AllSongsMigrationFixtures.indexes
import com.stash.core.data.db.AllSongsMigrationFixtures.makeDebugV56
import com.stash.core.data.db.AllSongsMigrationFixtures.seedLibrary
import com.stash.core.data.db.AllSongsMigrationFixtures.seedTidalMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v56 -> v57 adds All Songs' `scan_folders` and `scanned_songs`. From master's v56 (what v0.9.116
 * shipped) that is two new, empty tables; from the All Songs PR's debug v56 it also adds master's
 * missing `tidal_match_cache` and rebuilds the scan tables. Each run validates every table and
 * index against the exported 57.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV56V57Test {

    private val DB_NAME = "migration-v56v57-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun `master's v56 gets the two empty scan tables, library and Tidal cache unchanged`() {
        helper.createDatabase(DB_NAME, 56).use {
            seedLibrary(it)
            seedTidalMatch(it)
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 57, true, StashDatabase.MIGRATION_56_57)

        assertEquals(0, count(db, "SELECT COUNT(*) FROM scan_folders"))
        assertEquals(0, count(db, "SELECT COUNT(*) FROM scanned_songs"))
        assertTrue("last_modified" in columns(db, "scanned_songs"))
        assertEquals(1, count(db, "SELECT COUNT(*) FROM tidal_match_cache WHERE `key` = 'isrc:X'"))
        assertTrue(TRACKS_INDEX in indexes(db, "tracks"))
        assertLibraryUnchanged(db)
    }

    @Test fun `a tree is added once, a file is listed once, and songs go with their folder`() {
        helper.createDatabase(DB_NAME, 56).close()
        val db = helper.runMigrationsAndValidate(DB_NAME, 57, true, StashDatabase.MIGRATION_56_57)
        db.execSQL("PRAGMA foreign_keys = ON")

        assertEquals(true, indexes(db, "scan_folders")["index_scan_folders_tree_uri"])
        assertEquals(true, indexes(db, "scanned_songs")["index_scanned_songs_uri"])
        assertEquals(false, indexes(db, "scanned_songs")["index_scanned_songs_folder_id"])

        db.execSQL("INSERT INTO scan_folders (id, tree_uri, display_name, added_at) VALUES (1, 'content://t/tree/a', 'A', 0)")
        assertThrows(SQLiteConstraintException::class.java) {
            db.execSQL("INSERT INTO scan_folders (tree_uri, display_name, added_at) VALUES ('content://t/tree/a', 'A again', 0)")
        }
        val song = "INSERT INTO scanned_songs (folder_id, uri, title, artist, album, duration_ms, file_format, " +
            "file_size_bytes, last_modified, date_added) VALUES (1, 'content://t/tree/a/document/x', 'T', 'A', '', 0, 'mp3', 5, 0, 0)"
        db.execSQL(song)
        assertThrows(SQLiteConstraintException::class.java) { db.execSQL(song) }

        db.execSQL("DELETE FROM scan_folders WHERE id = 1")
        assertEquals(0, count(db, "SELECT COUNT(*) FROM scanned_songs"))
    }

    @Test fun `the All Songs debug v56 is healed with master's Tidal cache added and scan tables rebuilt`() {
        helper.createDatabase(DB_NAME, 55).use {
            seedLibrary(it)
            makeDebugV56(it)
        }

        // Validation fails if tidal_match_cache is still missing or a scan table kept a stray shape.
        val db = helper.runMigrationsAndValidate(DB_NAME, 57, true, StashDatabase.MIGRATION_56_57)

        assertEquals(0, count(db, "SELECT COUNT(*) FROM tidal_match_cache"))
        assertEquals("the scan cache is refilled by the next scan", 0, count(db, "SELECT COUNT(*) FROM scanned_songs"))
        assertTrue(TRACKS_INDEX in indexes(db, "tracks"))
        assertLibraryUnchanged(db)
    }
}
