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
 * v73 -> v74 (Edit order): `playlist_order` and `playlist_order_entries`, both empty, so every
 * playlist keeps its order. Nothing else changes. Validated against 74.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV73V74Test {

    private val DB_NAME = "migration-v73v74-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun `73 to 74 adds the empty order tables and keeps the library`() {
        helper.createDatabase(DB_NAME, 73).use { seedLibrary(it) }

        val db = helper.runMigrationsAndValidate(DB_NAME, 74, true, StashDatabase.MIGRATION_73_74)

        assertEquals(0, count(db, "SELECT (SELECT COUNT(*) FROM playlist_order) + (SELECT COUNT(*) FROM playlist_order_entries)"))
        assertLibraryUnchanged(db)
    }
}
