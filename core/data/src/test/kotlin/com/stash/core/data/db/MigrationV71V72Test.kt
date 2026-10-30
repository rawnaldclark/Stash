package com.stash.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.stash.core.data.db.AllSongsMigrationFixtures.assertLibraryUnchanged
import com.stash.core.data.db.AllSongsMigrationFixtures.count
import com.stash.core.data.db.AllSongsMigrationFixtures.seedLibrary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v71 -> v72 (the Sync tab overhaul): `playlists.user_hidden`, 0 for every row, since nothing was
 * taken out of the library before. Nothing else changes. Validated against the exported 72.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV71V72Test {

    private val DB_NAME = "migration-v71v72-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun `71 to 72 adds user_hidden off for every playlist and keeps the library`() {
        helper.createDatabase(DB_NAME, 71).use { seedLibrary(it) }

        val db = helper.runMigrationsAndValidate(DB_NAME, 72, true, StashDatabase.MIGRATION_71_72)

        assertEquals(0, count(db, "SELECT COUNT(*) FROM playlists WHERE user_hidden != 0"))
        assertLibraryUnchanged(db)
    }
}
