package com.stash.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v50 -> v51 adds `playlists.keep_offline` (the playlist page's Download switch, #474).
 * An existing playlist survives with the switch off. It also clears every mix's sync
 * switch once, replacing the every-launch reset (#438) that wiped real consent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV50V51Test {

    private val DB_NAME = "migration-v50v51-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun `50 to 51 adds keep_offline, clears mix sync once, and keeps the rows`() {
        helper.createDatabase(DB_NAME, 50).use { db ->
            db.execSQL(
                "INSERT INTO playlists (id, name, source, source_id, type, track_count, is_active, " +
                    "sync_enabled, date_added, hide_from_home, pinned) VALUES " +
                    "(1, 'Release Radar', 'SPOTIFY', 'spotify:playlist:rr', 'DAILY_MIX', 30, 1, 1, 0, 0, 0), " +
                    "(2, 'Road Trip', 'SPOTIFY', 'spotify:playlist:mine', 'CUSTOM', 12, 1, 1, 0, 0, 0)",
            )
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 51, true, StashDatabase.MIGRATION_50_51)

        db.query("SELECT name, sync_enabled, keep_offline FROM playlists ORDER BY id").use { c ->
            assertTrue(c.moveToNext())
            assertEquals("Release Radar", c.getString(0))
            assertEquals(0L, c.getLong(1)) // the mix's stale flag is cleared
            assertEquals(0L, c.getLong(2))
            assertTrue(c.moveToNext())
            assertEquals("Road Trip", c.getString(0))
            assertEquals(1L, c.getLong(1)) // a playlist's switch is the user's, untouched
            assertEquals(0L, c.getLong(2))
        }
    }
}
