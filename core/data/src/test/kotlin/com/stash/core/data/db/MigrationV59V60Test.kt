package com.stash.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.stash.core.data.db.AllSongsMigrationFixtures.assertLibraryUnchanged
import com.stash.core.data.db.AllSongsMigrationFixtures.columns
import com.stash.core.data.db.AllSongsMigrationFixtures.count
import com.stash.core.data.db.AllSongsMigrationFixtures.makeDebugV57
import com.stash.core.data.db.AllSongsMigrationFixtures.seedLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v59 -> v60 adds the empty `artist_genres` cache (artist_name TEXT NOCASE primary key, genres,
 * status, fetched_at) and touches nothing else. Stash has no destructive fallback, so every way a
 * phone reaches 60 is walked: master's 59, 58 and 57, the shipped v56, and the All Songs PR's debug
 * v57. Each run validates every table and index against the exported 60.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV59V60Test {

    private val DB_NAME = "migration-v59v60-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val from59 = arrayOf(StashDatabase.MIGRATION_59_60)
    private val from58 = arrayOf(StashDatabase.MIGRATION_58_59, *from59)
    private val from57 = arrayOf(StashDatabase.MIGRATION_57_58, *from58)
    private val from56 = arrayOf(StashDatabase.MIGRATION_56_57, *from57)

    /** One listen with v59's columns stamped. */
    private fun seedV59Listen(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO listening_events (id, track_id, started_at, scrobbled, yt_scrobbled, completed_at, origin_device, " +
                "local_day, local_minute, listened_ms) VALUES (7, 1, 1760000000000, 1, 1, 1760000031000, NULL, 20371, 600, 212000)",
        )
    }

    /** One listen in the pre-v59 shape (for the older starting points). */
    private fun seedOldListen(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO listening_events (id, track_id, started_at, scrobbled, yt_scrobbled, completed_at, origin_device) " +
                "VALUES (7, 1, 1760000000000, 1, 1, 1760000031000, NULL)",
        )
    }

    private fun assertListenKept(db: SupportSQLiteDatabase) {
        db.query("SELECT id, track_id, started_at, scrobbled FROM listening_events").use { c ->
            assertEquals(1, c.count)
            assertTrue(c.moveToFirst())
            assertEquals(7L, c.getLong(0))
            assertEquals(1L, c.getLong(1))
            assertEquals(1760000000000L, c.getLong(2))
            assertEquals(1, c.getInt(3))
        }
    }

    private fun assertEmptyGenreCache(db: SupportSQLiteDatabase) {
        assertEquals(listOf("artist_name", "genres", "status", "fetched_at"), columns(db, "artist_genres"))
        assertEquals(0, count(db, "SELECT COUNT(*) FROM artist_genres"))
    }

    @Test fun `59 to 60 adds an empty artist_genres and keeps history and library`() {
        helper.createDatabase(DB_NAME, 59).use {
            seedLibrary(it)
            seedV59Listen(it)
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 60, true, *from59)

        assertEmptyGenreCache(db)
        assertListenKept(db)
        db.query("SELECT local_day, local_minute, listened_ms FROM listening_events WHERE id = 7").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(20371L, c.getLong(0))
            assertEquals(600, c.getInt(1))
            assertEquals(212000L, c.getLong(2))
        }
        assertLibraryUnchanged(db)
    }

    @Test fun `the artist key is case-insensitive, so case variants share one row`() {
        helper.createDatabase(DB_NAME, 59).close()
        val db = helper.runMigrationsAndValidate(DB_NAME, 60, true, *from59)

        db.execSQL(
            "INSERT INTO artist_genres (artist_name, genres, status, fetched_at) " +
                "VALUES ('Drake', '[{\"g\":\"hip-hop\",\"w\":1.0}]', 'TAGGED', 5)",
        )
        db.execSQL(
            "INSERT OR REPLACE INTO artist_genres (artist_name, genres, status, fetched_at) " +
                "VALUES ('drake', '[]', 'UNTAGGABLE', 6)",
        )

        assertEquals(1, count(db, "SELECT COUNT(*) FROM artist_genres"))
        db.query("SELECT status, fetched_at FROM artist_genres WHERE artist_name = 'DRAKE'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("UNTAGGABLE", c.getString(0))
            assertEquals(6L, c.getLong(1))
        }
    }

    @Test fun `master's v58 reaches 60`() {
        helper.createDatabase(DB_NAME, 58).use {
            seedLibrary(it)
            seedOldListen(it)
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 60, true, *from58)

        assertEmptyGenreCache(db)
        assertListenKept(db)
        assertLibraryUnchanged(db)
    }

    @Test fun `the shipped v56 (v0_9_116) reaches 60 through every step`() {
        helper.createDatabase(DB_NAME, 56).use {
            seedLibrary(it)
            seedOldListen(it)
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 60, true, *from56)

        assertEmptyGenreCache(db)
        assertListenKept(db)
        assertLibraryUnchanged(db)
    }

    @Test fun `the All Songs debug v57 is healed on the way and reaches 60`() {
        helper.createDatabase(DB_NAME, 55).use {
            seedLibrary(it)
            seedOldListen(it)
            makeDebugV57(it)
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 60, true, *from57)

        assertEmptyGenreCache(db)
        assertListenKept(db)
        assertLibraryUnchanged(db)
    }

    @Test fun `the migration is safe to run on a database that already has the table`() {
        helper.createDatabase(DB_NAME, 59).use { db ->
            // A half-applied upgrade (process killed after the CREATE) must not fail the retry.
            StashDatabase.MIGRATION_59_60.migrate(db)
            StashDatabase.MIGRATION_59_60.migrate(db)
        }
        // The helper re-runs the step on its own: CREATE TABLE IF NOT EXISTS keeps it a no-op.
        val db = helper.runMigrationsAndValidate(DB_NAME, 60, true, *from59)
        assertEmptyGenreCache(db)
    }
}
