package com.stash.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies migration v32 -> v33, which adds the additive nullable
 * `streaming_mode` column to `sync_history`. Old rows (written before the
 * column existed) read NULL — the UI suppresses the Online/Offline label
 * for those.
 *
 * `runMigrationsAndValidate` against `33.json` is the primary assertion;
 * the pre-migration row insert additionally confirms `streaming_mode`
 * defaults to NULL for rows that predate the column.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV32V33Test {

    private val DB_NAME = "migration-v32v33-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migration v32 to v33 adds streaming_mode column defaulting null`() {
        helper.createDatabase(DB_NAME, 32).use { db ->
            db.execSQL(
                "INSERT INTO sync_history (started_at, status, playlists_checked, " +
                    "new_tracks_found, tracks_downloaded, tracks_failed, bytes_downloaded, trigger) " +
                    "VALUES (0, 'COMPLETED', 5, 100, 0, 0, 0, 'MANUAL')",
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            DB_NAME, 33, true, StashDatabase.MIGRATION_32_33,
        )

        migrated.query("SELECT streaming_mode FROM sync_history LIMIT 1").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue("streaming_mode should be NULL on pre-migration rows", c.isNull(0))
        }
    }
}
