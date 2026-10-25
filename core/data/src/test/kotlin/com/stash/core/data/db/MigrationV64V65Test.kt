package com.stash.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.stash.core.data.db.AllSongsMigrationFixtures.assertLibraryUnchanged
import com.stash.core.data.db.AllSongsMigrationFixtures.seedLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v64 -> v65 (Discogs as a second source): `album_back_covers.back_from_discogs` and
 * `inside_from_discogs` (0 by default) and `discogs_page` (nullable). A v64 row keeps its back and
 * pages and reads as all from the Cover Art Archive. Walked from 64 and from 63; each run validates
 * every table and index against the exported 65.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV64V65Test {

    private val DB_NAME = "migration-v64v65-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun `64 to 65 keeps each row and marks it all from the archive`() {
        helper.createDatabase(DB_NAME, 64).use { db ->
            seedLibrary(db)
            db.execSQL(
                "INSERT INTO album_back_covers (album_key, image_url, release_mbid, attempted_at, inside_urls) " +
                    "VALUES ('nirvana\u001Fnevermind', 'https://caa/back.jpg', 'r', 1, 'https://caa/p1.jpg')",
            )
            db.execSQL(
                "INSERT INTO album_back_covers (album_key, image_url, release_mbid, attempted_at, inside_urls) " +
                    "VALUES ('beatles\u001Fhelp', 'https://caa/help.jpg', 'r', 1, '')",
            )
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 65, true, StashDatabase.MIGRATION_64_65)

        db.query("SELECT album_key, attempted_at FROM album_back_covers ORDER BY album_key").use { c ->
            // Nothing inside on the archive: due again, for Discogs. A full row keeps its time.
            assertTrue(c.moveToFirst())
            assertEquals(0L, c.getLong(1))
            assertTrue(c.moveToNext())
            assertEquals(1L, c.getLong(1))
        }
        db.query(
            "SELECT image_url, inside_urls, back_from_discogs, inside_from_discogs, discogs_page FROM album_back_covers " +
                "WHERE album_key LIKE 'nirvana%'",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("https://caa/back.jpg", c.getString(0))
            assertEquals("https://caa/p1.jpg", c.getString(1))
            assertEquals(0, c.getInt(2))
            assertEquals(0, c.getInt(3))
            assertTrue(c.isNull(4))
        }
        assertLibraryUnchanged(db)
    }

    @Test fun `63 reaches 65`() {
        helper.createDatabase(DB_NAME, 63).use { seedLibrary(it) }
        val db = helper.runMigrationsAndValidate(
            DB_NAME, 65, true, StashDatabase.MIGRATION_63_64, StashDatabase.MIGRATION_64_65,
        )
        assertLibraryUnchanged(db)
    }
}
