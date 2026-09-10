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
 * Verifies migration v45 -> v46: the two columns that let a restored library
 * ask for its audio back. Existing rows keep their data and start unstamped —
 * nothing recorded what was lost before this, and inventing it would offer
 * downloads the user may have deleted on purpose.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV45V46Test {
    private val DB_NAME = "migration-v45v46-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `adds the loss stamp and the user-requested flag without disturbing existing rows`() {
        helper.createDatabase(DB_NAME, 45).use { db ->
            db.execSQL(
                """
                INSERT INTO tracks (id, title, artist, album, duration_ms, file_format, quality_kbps,
                    file_size_bytes, source, date_added, play_count, is_downloaded,
                    canonical_title, canonical_artist, match_confidence, match_dismissed, file_path)
                VALUES (1, 'Kept', 'A', 'Al', 1000, 'flac', 900, 42, 'SPOTIFY', 7, 3, 1,
                    'kept', 'a', 0.0, 0, '/music/kept.flac')
                """.trimIndent(),
            )
            db.execSQL(
                "INSERT INTO download_queue (id, track_id, status, search_query, retry_count, failure_type, created_at) " +
                    "VALUES (1, 1, 'PENDING', '', 0, 'NONE', 7)",
            )
        }
        val migrated = helper.runMigrationsAndValidate(DB_NAME, 46, true, StashDatabase.MIGRATION_45_46)

        migrated.query("SELECT title, is_downloaded, file_path, download_missing_at FROM tracks WHERE id = 1").use { c ->
            assertTrue(c.moveToNext())
            assertEquals("Kept", c.getString(0))
            assertEquals(1, c.getInt(1))
            assertEquals("/music/kept.flac", c.getString(2))
            assertTrue("a pre-existing row must not claim a loss", c.isNull(3))
        }
        migrated.query("SELECT user_requested FROM download_queue WHERE id = 1").use { c ->
            assertTrue(c.moveToNext())
            assertEquals("queued rows default to automatic, not user-requested", 0, c.getInt(0))
        }
    }
}
