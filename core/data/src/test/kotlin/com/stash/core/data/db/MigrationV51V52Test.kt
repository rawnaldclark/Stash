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
 * v51 -> v52 adds `tracks.match_picked_at`, set when the user picks a track's
 * audio in Failed Matches (#531). Existing rows keep everything and read as
 * "not picked by hand".
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV51V52Test {

    private val DB_NAME = "migration-v51v52-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun `51 to 52 adds match_picked_at and keeps the rows`() {
        helper.createDatabase(DB_NAME, 51).use { db ->
            db.execSQL(
                "INSERT INTO tracks (id, title, artist, album, duration_ms, file_format, quality_kbps, " +
                    "file_size_bytes, source, date_added, play_count, is_downloaded, canonical_title, " +
                    "canonical_artist, match_confidence, match_dismissed, match_flagged) " +
                    "VALUES (1, 'Lacrymosa', 'Evanescence', 'Synthesis', 230000, 'flac', 0, 0, 'SPOTIFY', " +
                    "0, 0, 1, 'lacrymosa', 'evanescence', 0.0, 0, 1)",
            )
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 52, true, StashDatabase.MIGRATION_51_52)

        db.query("SELECT title, match_flagged, match_picked_at FROM tracks").use { c ->
            assertTrue(c.moveToNext())
            assertEquals("Lacrymosa", c.getString(0))
            assertEquals(1L, c.getLong(1))
            assertTrue("no existing row was picked by hand", c.isNull(2))
        }
    }
}
