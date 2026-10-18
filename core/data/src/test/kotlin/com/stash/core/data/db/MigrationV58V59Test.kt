package com.stash.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.stash.core.data.db.AllSongsMigrationFixtures.assertLibraryUnchanged
import com.stash.core.data.db.AllSongsMigrationFixtures.columns
import com.stash.core.data.db.AllSongsMigrationFixtures.count
import com.stash.core.data.db.AllSongsMigrationFixtures.indexes
import com.stash.core.data.db.AllSongsMigrationFixtures.makeDebugV57
import com.stash.core.data.db.AllSongsMigrationFixtures.seedLibrary
import com.stash.core.data.db.AllSongsMigrationFixtures.seedScanV57Shape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v58 -> v59 adds `listening_events.local_day`, `local_minute` and `listened_ms` (all NULL on
 * existing rows) and the covering day index (local_day, track_id, started_at, listened_ms,
 * local_minute). Stash has no destructive fallback, so every way
 * a phone reaches 59 is walked: master's 56 (the shipped v0.9.116), 57 and 58, and the All Songs
 * PR's debug v57. Each run validates every table and index against the exported 59.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV58V59Test {

    private val DB_NAME = "migration-v58v59-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val from58 = arrayOf(StashDatabase.MIGRATION_58_59)
    private val from57 = arrayOf(StashDatabase.MIGRATION_57_58, *from58)
    private val from56 = arrayOf(StashDatabase.MIGRATION_56_57, *from57)

    private val newColumns = listOf("local_day", "local_minute", "listened_ms")
    private val newIndex = "index_listening_events_local_day_track_id_started_at_listened_ms_local_minute"

    /** Two listens on the seeded track (one from the web) and an unsent ListenBrainz outcome on the first. */
    private fun seedHistory(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO listening_events (id, track_id, started_at, scrobbled, yt_scrobbled, completed_at, origin_device) " +
                "VALUES (7, 1, 1760000000000, 0, 1, 1760000031000, NULL)",
        )
        db.execSQL(
            "INSERT INTO listening_events (id, track_id, started_at, scrobbled, yt_scrobbled, completed_at, origin_device) " +
                "VALUES (8, 1, 1760000100000, 1, 1, NULL, 'd_web00000000000')",
        )
        db.execSQL(
            "INSERT INTO listen_submissions (event_id, target, state, attempts, updated_at) VALUES (7, 'listenbrainz', 'FAILED', 2, 5)",
        )
    }

    private fun assertHistoryKeptAndUnstamped(db: SupportSQLiteDatabase) {
        db.query(
            "SELECT id, track_id, started_at, scrobbled, yt_scrobbled, completed_at, origin_device, " +
                "local_day, local_minute, listened_ms FROM listening_events ORDER BY id",
        ).use { c ->
            assertEquals(2, c.count)
            assertTrue(c.moveToFirst())
            assertEquals(7L, c.getLong(0))
            assertEquals(1L, c.getLong(1))
            assertEquals(1760000000000L, c.getLong(2))
            assertEquals(0, c.getInt(3))
            assertEquals(1, c.getInt(4))
            assertEquals(1760000031000L, c.getLong(5))
            assertTrue(c.isNull(6))
            for (i in 7..9) assertTrue("column $i of a pre-v59 row starts NULL", c.isNull(i))
            assertTrue(c.moveToNext())
            assertEquals(8L, c.getLong(0))
            assertTrue(c.isNull(5))
            assertEquals("d_web00000000000", c.getString(6))
            for (i in 7..9) assertTrue(c.isNull(i))
        }
        // The unsent outcome still hangs off the same id: no rebuild renumbered the listens.
        assertEquals(1, count(db, "SELECT COUNT(*) FROM listen_submissions WHERE event_id = 7"))
    }

    @Test fun `58 to 59 keeps every listen, adds NULL columns and the day index`() {
        helper.createDatabase(DB_NAME, 58).use {
            seedLibrary(it)
            seedHistory(it)
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 59, true, *from58)

        assertTrue(columns(db, "listening_events").containsAll(newColumns))
        assertEquals(false, indexes(db, "listening_events")[newIndex])
        db.query("PRAGMA index_info(`$newIndex`)").use { c ->
            val cols = buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
            assertEquals(listOf("local_day", "track_id", "started_at", "listened_ms", "local_minute"), cols)
        }
        assertHistoryKeptAndUnstamped(db)
        assertLibraryUnchanged(db)
    }

    @Test fun `the new columns take values and the foreign key still cascades`() {
        helper.createDatabase(DB_NAME, 58).use {
            seedLibrary(it)
            seedHistory(it)
        }
        val db = helper.runMigrationsAndValidate(DB_NAME, 59, true, *from58)

        db.execSQL("UPDATE listening_events SET local_day = 20371, local_minute = 1439, listened_ms = 212000 WHERE id = 7")
        db.query("SELECT local_day, local_minute, listened_ms FROM listening_events WHERE id = 7").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(20371L, c.getLong(0))
            assertEquals(1439, c.getInt(1))
            assertEquals(212000L, c.getLong(2))
        }
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM tracks WHERE id = 1")
        assertEquals(0, count(db, "SELECT COUNT(*) FROM listening_events"))
        assertEquals(0, count(db, "SELECT COUNT(*) FROM listen_submissions"))
    }

    @Test fun `master's v57 reaches 59 with its history`() {
        helper.createDatabase(DB_NAME, 57).use {
            seedLibrary(it)
            seedHistory(it)
            seedScanV57Shape(it)
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 59, true, *from57)

        assertHistoryKeptAndUnstamped(db)
        assertTrue("album_art_path" in columns(db, "scanned_songs"))
        assertLibraryUnchanged(db)
    }

    @Test fun `the shipped v56 (v0_9_116) reaches 59 through every step`() {
        helper.createDatabase(DB_NAME, 56).use {
            seedLibrary(it)
            seedHistory(it)
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 59, true, *from56)

        assertHistoryKeptAndUnstamped(db)
        assertTrue(newIndex in indexes(db, "listening_events"))
        assertLibraryUnchanged(db)
    }

    @Test fun `the All Songs debug v57 is healed on the way and reaches 59`() {
        helper.createDatabase(DB_NAME, 55).use {
            seedLibrary(it)
            seedHistory(it)
            makeDebugV57(it)
        }

        // Validation fails if 57 -> 58 didn't add master's tidal_match_cache, or 58 -> 59 missed a column.
        val db = helper.runMigrationsAndValidate(DB_NAME, 59, true, *from57)

        assertHistoryKeptAndUnstamped(db)
        db.query("SELECT album_art_path FROM scanned_songs WHERE id = 5").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("scanned_abc.jpg", c.getString(0))
        }
        assertLibraryUnchanged(db)
    }

    @Test fun `an empty history migrates`() {
        helper.createDatabase(DB_NAME, 58).close()
        val db = helper.runMigrationsAndValidate(DB_NAME, 59, true, *from58)
        assertEquals(0, count(db, "SELECT COUNT(*) FROM listening_events"))
        assertFalse(columns(db, "listening_events").isEmpty())
    }
}
