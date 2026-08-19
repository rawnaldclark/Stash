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
 * Verifies migration v43 -> v44: the `artist_images` cache table is created
 * with its COLLATE NOCASE primary key, existing tables survive, and the
 * sentinel semantics hold (NULL `image_url` = resolvable-but-no-photo stamp).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV43V44Test {

    private val DB_NAME = "migration-v43v44-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `artist_images table is created with NOCASE primary key and stores photos`() {
        helper.createDatabase(DB_NAME, 43).use { db ->
            db.execSQL(
                """
                INSERT INTO playlists (id, name, source, source_id, type, track_count, is_active)
                VALUES (1, 'Gym', 'SPOTIFY', 'sp1', 'CUSTOM', 10, 1)
                """.trimIndent(),
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            DB_NAME, 44, true, StashDatabase.MIGRATION_43_44,
        )

        // Existing data survives the migration.
        migrated.query("SELECT name FROM playlists WHERE id = 1").use { c ->
            assertTrue(c.moveToNext())
            assertEquals("Gym", c.getString(0))
        }

        // A resolved photo stores round-trip.
        migrated.execSQL(
            "INSERT INTO artist_images (artist_name, image_url, attempted_at) " +
                "VALUES ('Aarne', 'https://yt3.example/aarne.jpg', 1723900000000)",
        )
        // A no-photo sentinel (NULL image_url) also round-trips.
        migrated.execSQL(
            "INSERT INTO artist_images (artist_name, image_url, attempted_at) " +
                "VALUES ('Local Rip', NULL, 1723900000001)",
        )
        migrated.query("SELECT artist_name, image_url FROM artist_images").use { c ->
            assertTrue(c.moveToNext())
            assertEquals("Aarne", c.getString(0))
            assertEquals("https://yt3.example/aarne.jpg", c.getString(1))
            assertTrue(c.moveToNext())
            assertEquals("Local Rip", c.getString(0))
            assertTrue("unresolvable name is a NULL-image sentinel", c.isNull(1))
        }

        // COLLATE NOCASE: a case-variant key resolves to the SAME row.
        migrated.query(
            "SELECT image_url FROM artist_images WHERE artist_name = 'aarne' COLLATE NOCASE",
        ).use { c ->
            assertTrue(c.moveToNext())
            assertEquals("https://yt3.example/aarne.jpg", c.getString(0))
        }
    }
}
