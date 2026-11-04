package com.stash.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
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
 * v77 -> v78 adds the four nullable Spicy Lyrics credit columns to `lyrics` (#837): existing rows
 * keep their lyrics and read as "no credits". Also the PR's first build, which added the same
 * columns at v77: a phone that ran it is already at 77 with them, and must reach 78 without
 * "duplicate column name". Every run validates the result against the exported 78.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV77V78Test {

    private val dbName = "migration-v77v78-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val credits = listOf("credit_uploader_name", "credit_uploader_url", "credit_maker_name", "credit_maker_url")

    private fun seed(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO tracks (id, title, artist, album, album_artist, duration_ms, file_format, " +
                "quality_kbps, file_size_bytes, source, date_added, play_count, is_downloaded, " +
                "canonical_title, canonical_artist, match_confidence, match_dismissed) VALUES " +
                "(1, 'Song', 'Artist', 'Album', 'Artist', 200000, 'opus', " +
                "0, 0, 'YOUTUBE', 1, 0, 0, 'song', 'artist', 0, 0)",
        )
        db.execSQL(
            "INSERT INTO lyrics (track_id, plain_text, synced_lrc, instrumental, language, source, " +
                "source_lyrics_id, fetched_at, ttml, ttml_checked_at, sync_offset_ms) VALUES " +
                "(1, 'Hi', '[00:01.00]Hi', 0, NULL, 'lrclib', '42', 5, NULL, 7, -250)",
        )
    }

    private fun columns(db: SupportSQLiteDatabase): List<String> =
        db.query("PRAGMA table_info(`lyrics`)").use { c ->
            val name = c.getColumnIndexOrThrow("name")
            buildList { while (c.moveToNext()) add(c.getString(name)) }
        }

    /** The seeded row, unchanged, with [expectedCredits] in the credit columns. */
    private fun assertRow(db: SupportSQLiteDatabase, expectedCredits: List<String?>) {
        db.query(
            "SELECT plain_text, synced_lrc, source, source_lyrics_id, fetched_at, ttml_checked_at, sync_offset_ms, " +
                credits.joinToString() + " FROM lyrics WHERE track_id = 1",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Hi", c.getString(0))
            assertEquals("[00:01.00]Hi", c.getString(1))
            assertEquals("lrclib", c.getString(2))
            assertEquals("42", c.getString(3))
            assertEquals(5L, c.getLong(4))
            assertEquals(7L, c.getLong(5))
            assertEquals(-250L, c.getLong(6))
            assertEquals(expectedCredits, (7 until 11).map { if (c.isNull(it)) null else c.getString(it) })
        }
    }

    @Test fun `77 to 78 adds the credit columns, NULL on existing rows`() {
        helper.createDatabase(dbName, 77).use { db ->
            seed(db)
            assertTrue(credits.none { it in columns(db) })
        }

        helper.runMigrationsAndValidate(dbName, 78, true, StashDatabase.MIGRATION_77_78).use { db ->
            assertTrue(columns(db).containsAll(credits))
            assertRow(db, listOf(null, null, null, null))
        }
    }

    @Test fun `a 77 that already has the credit columns reaches 78 and keeps its credits`() {
        helper.createDatabase(dbName, 77).use { db ->
            seed(db)
            // What the PR's first build did at 76 -> 77.
            credits.forEach { db.execSQL("ALTER TABLE `lyrics` ADD COLUMN `$it` TEXT") }
            db.execSQL(
                "UPDATE lyrics SET credit_uploader_name = 'alice', credit_uploader_url = 'https://a.example', " +
                    "credit_maker_name = 'bob' WHERE track_id = 1",
            )
        }

        helper.runMigrationsAndValidate(dbName, 78, true, StashDatabase.MIGRATION_77_78).use { db ->
            assertEquals("no column added twice", credits.size, columns(db).count { it in credits })
            assertRow(db, listOf("alice", "https://a.example", "bob", null))
        }
    }

    @Test fun `a 77 with only some of the columns gets the rest`() {
        helper.createDatabase(dbName, 77).use { db ->
            seed(db)
            db.execSQL("ALTER TABLE `lyrics` ADD COLUMN `credit_uploader_name` TEXT")
        }

        helper.runMigrationsAndValidate(dbName, 78, true, StashDatabase.MIGRATION_77_78).use { db ->
            assertEquals(credits.size, columns(db).count { it in credits })
            assertRow(db, listOf(null, null, null, null))
        }
    }

    @Test fun `76 reaches 78 through the search index rebuild`() {
        helper.createDatabase(dbName, 76).use { seed(it) }

        helper.runMigrationsAndValidate(
            dbName, 78, true, StashDatabase.MIGRATION_76_77, StashDatabase.MIGRATION_77_78,
        ).use { db -> assertRow(db, listOf(null, null, null, null)) }
    }
}
