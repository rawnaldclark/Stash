package com.stash.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.stash.core.data.db.AllSongsMigrationFixtures.assertLibraryUnchanged
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
 * v61 -> v62 (Save to Google Drive): the empty `cloud_uploads` table. Nothing existing changes.
 * Walked from 61 and from master's 59; each run validates every table and index against the
 * exported 62.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV61V62Test {

    private val DB_NAME = "migration-v61v62-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val from61 = arrayOf(StashDatabase.MIGRATION_61_62)
    private val from59 = arrayOf(StashDatabase.MIGRATION_59_60, StashDatabase.MIGRATION_60_61, *from61)

    @Test fun `61 to 62 adds the uploads table and keeps the library`() {
        helper.createDatabase(DB_NAME, 61).use { seedLibrary(it) }

        val db = helper.runMigrationsAndValidate(DB_NAME, 62, true, *from61)

        assertEquals(0, count(db, "SELECT COUNT(*) FROM cloud_uploads"))
        assertLibraryUnchanged(db)
        assertEquals(false, indexes(db, "cloud_uploads")["index_cloud_uploads_state_queued_at"])
        assertEquals(false, indexes(db, "cloud_uploads")["index_cloud_uploads_account_id"])
    }

    @Test fun `master's v59 reaches 62`() {
        helper.createDatabase(DB_NAME, 59).use { seedLibrary(it) }
        val db = helper.runMigrationsAndValidate(DB_NAME, 62, true, *from59)
        assertEquals(0, count(db, "SELECT COUNT(*) FROM cloud_uploads"))
        assertLibraryUnchanged(db)
    }

    @Test fun `deleting a track or the Drive account drops its upload rows, never the track`() {
        helper.createDatabase(DB_NAME, 61).use { seedLibrary(it) }
        val db = helper.runMigrationsAndValidate(DB_NAME, 62, true, *from61)
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("INSERT INTO cloud_accounts (id, provider, remote_id, display_name, email, connected_at) VALUES (1, 'gdrive', 'p', 'P', NULL, 0)")
        db.execSQL(
            "INSERT INTO cloud_uploads (track_id, account_id, state, origin, queued_at, updated_at) " +
                "VALUES (1, 1, 'QUEUED', 'MANUAL', 0, 0)",
        )
        // Defaults for the progress columns.
        db.query("SELECT bytes_sent, attempts, session_uri FROM cloud_uploads WHERE track_id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0)); assertEquals(0, c.getInt(1)); assertTrue(c.isNull(2))
        }
        val tracks = count(db, "SELECT COUNT(*) FROM tracks")
        db.execSQL("DELETE FROM cloud_accounts WHERE id = 1")
        assertEquals(0, count(db, "SELECT COUNT(*) FROM cloud_uploads"))
        assertEquals(tracks, count(db, "SELECT COUNT(*) FROM tracks"))
    }
}
