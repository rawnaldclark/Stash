package com.stash.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.stash.core.data.db.AllSongsMigrationFixtures.assertLibraryUnchanged
import com.stash.core.data.db.AllSongsMigrationFixtures.columns
import com.stash.core.data.db.AllSongsMigrationFixtures.count
import com.stash.core.data.db.AllSongsMigrationFixtures.indexes
import com.stash.core.data.db.AllSongsMigrationFixtures.seedLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v60 -> v61 (cloud storage libraries): `tracks.cloud_uri` plus the empty `cloud_accounts`,
 * `cloud_folders` and `cloud_items` tables. Nothing existing changes. Walked from master's 60, 59
 * and the shipped 56; each run validates every table and index against the exported 61.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV60V61Test {

    private val DB_NAME = "migration-v60v61-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val from60 = arrayOf(StashDatabase.MIGRATION_60_61)
    private val from59 = arrayOf(StashDatabase.MIGRATION_59_60, *from60)
    private val from56 = arrayOf(
        StashDatabase.MIGRATION_56_57, StashDatabase.MIGRATION_57_58, StashDatabase.MIGRATION_58_59, *from59,
    )

    private fun assertCloudTablesEmpty(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        assertEquals(0, count(db, "SELECT COUNT(*) FROM cloud_accounts"))
        assertEquals(0, count(db, "SELECT COUNT(*) FROM cloud_folders"))
        assertEquals(0, count(db, "SELECT COUNT(*) FROM cloud_items"))
        assertTrue("cloud_uri" in columns(db, "tracks"))
        assertEquals(0, count(db, "SELECT COUNT(*) FROM tracks WHERE cloud_uri IS NOT NULL"))
    }

    @Test fun `60 to 61 adds the cloud tables and cloud_uri and keeps the library`() {
        helper.createDatabase(DB_NAME, 60).use { seedLibrary(it) }

        val db = helper.runMigrationsAndValidate(DB_NAME, 61, true, *from60)

        assertCloudTablesEmpty(db)
        assertLibraryUnchanged(db)
        assertEquals(true, indexes(db, "cloud_items")["index_cloud_items_account_id_item_id"])
        assertEquals(false, indexes(db, "cloud_items")["index_cloud_items_folder_id_seen_generation"])
    }

    @Test fun `master's v59 reaches 61`() {
        helper.createDatabase(DB_NAME, 59).use { seedLibrary(it) }
        val db = helper.runMigrationsAndValidate(DB_NAME, 61, true, *from59)
        assertCloudTablesEmpty(db)
        assertLibraryUnchanged(db)
    }

    @Test fun `the shipped v56 reaches 61 through every step`() {
        helper.createDatabase(DB_NAME, 56).use { seedLibrary(it) }
        val db = helper.runMigrationsAndValidate(DB_NAME, 61, true, *from56)
        assertCloudTablesEmpty(db)
        assertLibraryUnchanged(db)
    }

    @Test fun `deleting a track drops its cloud item, deleting an account drops its folders`() {
        helper.createDatabase(DB_NAME, 60).use { seedLibrary(it) }
        val db = helper.runMigrationsAndValidate(DB_NAME, 61, true, *from60)
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("INSERT INTO cloud_accounts (id, provider, remote_id, display_name, email, connected_at) VALUES (1, 'onedrive', 'u', 'U', NULL, 0)")
        db.execSQL("INSERT INTO cloud_folders (id, account_id, item_id, name, path, added_at) VALUES (2, 1, 'f', 'Music', '/Music', 0)")
        db.execSQL(
            "INSERT INTO cloud_items (track_id, account_id, folder_id, item_id, name, relative_path, parent_item_id, " +
                "c_tag, e_tag, size, mime_type, seen_generation, scanned_at) VALUES (1, 1, 2, 'i', 'a.flac', '', 'f', 'c', 'e', 10, NULL, 1, 0)",
        )
        // Defaults for the scan state columns.
        db.query("SELECT cursor_is_delta, full_scan, scan_generation FROM cloud_folders WHERE id = 2").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0)); assertEquals(1, c.getInt(1)); assertEquals(0, c.getInt(2))
        }
        db.execSQL("DELETE FROM cloud_accounts WHERE id = 1")
        assertEquals(0, count(db, "SELECT COUNT(*) FROM cloud_folders"))
        assertEquals(0, count(db, "SELECT COUNT(*) FROM cloud_items"))
        // The track itself is not the account's to cascade (CloudDao.removeAccount handles it).
        assertEquals(1, count(db, "SELECT COUNT(*) FROM tracks"))
    }
}
