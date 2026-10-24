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
 * v63 -> v64 (the pages inside an album's cover): `album_back_covers.inside_urls`, nullable. A v63
 * row keeps its back and reads as "inside not looked for yet". Walked from 63 and from 62; each run
 * validates every table and index against the exported 64.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV63V64Test {

    private val DB_NAME = "migration-v63v64-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun `63 to 64 keeps each back and leaves the inside unknown`() {
        helper.createDatabase(DB_NAME, 63).use { db ->
            seedLibrary(db)
            db.execSQL(
                "INSERT INTO album_back_covers (album_key, image_url, release_mbid, attempted_at) " +
                    "VALUES ('nirvana\u001Fnevermind', 'https://caa/back.jpg', 'r', 1)",
            )
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 64, true, StashDatabase.MIGRATION_63_64)

        db.query("SELECT image_url, inside_urls FROM album_back_covers").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("https://caa/back.jpg", c.getString(0))
            assertTrue(c.isNull(1))
        }
        assertLibraryUnchanged(db)
    }

    @Test fun `62 reaches 64`() {
        helper.createDatabase(DB_NAME, 62).use { seedLibrary(it) }
        val db = helper.runMigrationsAndValidate(
            DB_NAME, 64, true, StashDatabase.MIGRATION_62_63, StashDatabase.MIGRATION_63_64,
        )
        assertLibraryUnchanged(db)
    }
}
