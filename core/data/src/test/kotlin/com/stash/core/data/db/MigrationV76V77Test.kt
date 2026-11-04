package com.stash.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v76 -> v77 rebuilds `tracks_fts`. Room drops the FTS sync triggers for the whole migration chain
 * (`onPreMigrate` -> `dropFtsSyncTriggers`), so 66 -> 67 clearing guessed albums left their terms
 * in the index. Robolectric's `integrity_check` does not look inside FTS tables, so this checks
 * what a user sees: a search for the cleared album still finds the song before, and not after.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV76V77Test {

    private val dbName = "migration-v76v77-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private fun matches(db: SupportSQLiteDatabase, query: String): Int =
        db.query("SELECT count(*) FROM tracks_fts WHERE tracks_fts MATCH ?", arrayOf<Any?>(query))
            .use { it.moveToFirst(); it.getInt(0) }

    @Test fun `76 to 77 drops album terms an update without the sync triggers left behind`() {
        helper.createDatabase(dbName, 76).use { db ->
            db.execSQL(
                "INSERT INTO tracks (id, title, artist, album, album_artist, duration_ms, file_format, " +
                    "quality_kbps, file_size_bytes, source, date_added, play_count, is_downloaded, " +
                    "canonical_title, canonical_artist, match_confidence, match_dismissed) VALUES " +
                    "(1, 'Blinding Lights', 'The Weeknd', 'Now 35', 'Various Artists', 200000, 'opus', " +
                    "0, 0, 'YOUTUBE', 1, 0, 0, 'blinding lights', 'the weeknd', 0, 0)",
            )
            assertEquals(1, matches(db, "now"))
            // What Room does around every migration chain: drop the triggers, migrate, put them back.
            val triggers = db.query(
                "SELECT name, sql FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'room_fts_content_sync_%'",
            ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
            assertEquals(4, triggers.size)
            triggers.forEach { (name, _) -> db.execSQL("DROP TRIGGER `$name`") }
            db.execSQL("UPDATE tracks SET album = '', album_artist = '' WHERE id = 1") // 66 -> 67
            triggers.forEach { (_, sql) -> db.execSQL(sql) }
            assertEquals("stale before the fix", 1, matches(db, "now"))
        }

        helper.runMigrationsAndValidate(dbName, 77, true, StashDatabase.MIGRATION_76_77).use { db ->
            assertEquals(0, matches(db, "now"))
            assertEquals(1, matches(db, "blinding"))
            assertEquals(1, matches(db, "weeknd"))
        }
    }
}
