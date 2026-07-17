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
 * Verifies migration v33 -> v34, which adds the additive `hide_from_home`
 * column to `playlists` (NOT NULL DEFAULT 0). Rows written before the column
 * existed read 0 — they stay visible on Home until explicitly hidden.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV33V34Test {

    private val DB_NAME = "migration-v33v34-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate33To34_addsHideFromHomeColumn_defaultsZero() {
        helper.createDatabase(DB_NAME, 33).use { db ->
            db.execSQL(
                "INSERT INTO playlists (name, source, source_id, type, track_count, is_active) " +
                    "VALUES ('My Mix', 'SPOTIFY', 'spotify:playlist:1', 'DAILY_MIX', 10, 1)",
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            DB_NAME, 34, true, StashDatabase.MIGRATION_33_34,
        )

        migrated.query("SELECT hide_from_home FROM playlists LIMIT 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
    }
}
