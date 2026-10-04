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
 * v52 -> v53 adds `tracks.flac_no_match_at`, when a FLAC-upgrade lookup last found
 * no lossless match. Existing rows keep everything and read as "never came back
 * empty", so the next sweep still tries them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV52V53Test {

    private val DB_NAME = "migration-v52v53-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun `52 to 53 adds flac_no_match_at and keeps the rows`() {
        helper.createDatabase(DB_NAME, 52).use { db ->
            db.execSQL(
                "INSERT INTO tracks (id, title, artist, album, duration_ms, file_format, quality_kbps, " +
                    "file_size_bytes, source, date_added, play_count, is_downloaded, canonical_title, " +
                    "canonical_artist, match_confidence, match_dismissed, match_flagged, match_picked_at) " +
                    "VALUES (1, 'Lacrymosa', 'Evanescence', 'Synthesis', 230000, 'opus', 160, 0, 'SPOTIFY', " +
                    "0, 0, 1, 'lacrymosa', 'evanescence', 0.0, 0, 0, 5000)",
            )
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 53, true, StashDatabase.MIGRATION_52_53)

        db.query("SELECT title, file_format, match_picked_at, flac_no_match_at FROM tracks").use { c ->
            assertTrue(c.moveToNext())
            assertEquals("Lacrymosa", c.getString(0))
            assertEquals("opus", c.getString(1))
            assertEquals(5000L, c.getLong(2))
            assertTrue("no existing row has come back without a match", c.isNull(3))
        }
    }
}
