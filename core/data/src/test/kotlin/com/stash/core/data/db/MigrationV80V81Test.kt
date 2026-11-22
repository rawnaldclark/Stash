package com.stash.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.stash.core.data.db.AllSongsMigrationFixtures.assertLibraryUnchanged
import com.stash.core.data.db.AllSongsMigrationFixtures.count
import com.stash.core.data.db.AllSongsMigrationFixtures.seedLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v80 -> v81, true tuning (spec 2026-11-21): three columns on `tracks`, all NULL, which is "not measured yet".
 * Every run validates the result against the exported 81.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV80V81Test {

    private val dbName = "migration-v80v81-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun `80 to 81 adds the tuning columns, empty, and changes nothing else`() {
        helper.createDatabase(dbName, 80).use { seedLibrary(it) }

        val db = helper.runMigrationsAndValidate(dbName, 81, true, StashDatabase.MIGRATION_80_81)

        val tracks = count(db, "SELECT COUNT(*) FROM tracks")
        assertTrue("the fixture has songs", tracks > 0)
        assertEquals(
            "every song starts as not measured",
            tracks,
            count(db, "SELECT COUNT(*) FROM tracks WHERE tuning_cents IS NULL AND tuning_confidence IS NULL AND tuning_measured_at IS NULL"),
        )
        assertLibraryUnchanged(db)
    }

    @Test fun `a reading can be written and read back`() {
        helper.createDatabase(dbName, 80).use { seedLibrary(it) }
        val db = helper.runMigrationsAndValidate(dbName, 81, true, StashDatabase.MIGRATION_80_81)

        db.execSQL("UPDATE tracks SET tuning_cents = -31.77, tuning_confidence = 0.9, tuning_measured_at = 1795000000000")

        db.query("SELECT tuning_cents, tuning_confidence, tuning_measured_at FROM tracks LIMIT 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(-31.77, c.getDouble(0), 1e-6)
            assertEquals(0.9, c.getDouble(1), 1e-6)
            assertEquals(1_795_000_000_000, c.getLong(2))
        }
    }

    @Test fun `79 reaches 81 through the chain`() {
        helper.createDatabase(dbName, 79).use { seedLibrary(it) }
        val db = helper.runMigrationsAndValidate(dbName, 81, true, StashDatabase.MIGRATION_79_80, StashDatabase.MIGRATION_80_81)
        assertLibraryUnchanged(db)
    }
}
