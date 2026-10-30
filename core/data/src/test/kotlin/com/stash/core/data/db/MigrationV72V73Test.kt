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
 * v72 -> v73 (per-mix Stack): `playlists.stack_songs`, 0 for every row; the first sync after it
 * carries an accumulating account over (DiffWorker). Nothing else changes. Validated against 73.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV72V73Test {

    private val DB_NAME = "migration-v72v73-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun `72 to 73 adds stack_songs off for every playlist and keeps the library`() {
        helper.createDatabase(DB_NAME, 72).use { seedLibrary(it) }

        val db = helper.runMigrationsAndValidate(DB_NAME, 73, true, StashDatabase.MIGRATION_72_73)

        assertEquals(0, count(db, "SELECT COUNT(*) FROM playlists WHERE stack_songs != 0"))
        assertLibraryUnchanged(db)
    }
}
