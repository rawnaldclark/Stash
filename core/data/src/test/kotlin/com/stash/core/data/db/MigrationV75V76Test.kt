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
 * v75 -> v76 (duplicates): `track_aliases`, `track_merge_log` and `duplicate_dismissals`, all empty,
 * and nothing else. The phones run 75 today; the migration only adds tables, so every song,
 * membership, file and play is carried as it was. Validated against 76.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV75V76Test {

    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), StashDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun `75 to 76 adds the empty duplicate tables and keeps the library`() {
        val name = "duplicates-migration"
        helper.createDatabase(name, 75).use { seedLibrary(it) }

        helper.runMigrationsAndValidate(name, 76, true, StashDatabase.MIGRATION_75_76).use { db ->
            assertLibraryUnchanged(db)
            assertEquals(
                0,
                count(
                    db,
                    "SELECT (SELECT COUNT(*) FROM track_aliases) + (SELECT COUNT(*) FROM track_merge_log) + " +
                        "(SELECT COUNT(*) FROM duplicate_dismissals)",
                ),
            )
        }
    }

    @Test fun `an alias and a dismissal go with their row and the merge log outlives it`() {
        val name = "duplicates-migration-cascade"
        helper.createDatabase(name, 75).use { seedLibrary(it) }

        helper.runMigrationsAndValidate(name, 76, true, StashDatabase.MIGRATION_75_76).use { db ->
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("INSERT INTO track_aliases (kind, value, track_id, created_at) VALUES ('youtube_id', 'v1', 1, 0)")
            db.execSQL(
                "INSERT INTO tracks (id, title, artist, album, duration_ms, file_format, quality_kbps, file_size_bytes, source, " +
                    "date_added, play_count, is_downloaded, canonical_title, canonical_artist, match_confidence, match_dismissed, " +
                    "match_flagged) VALUES (2, 'Song', 'Artist', '', 0, 'opus', 0, 0, 'YOUTUBE', 0, 0, 0, 'song', 'artist', 0, 0, 0)",
            )
            db.execSQL("INSERT INTO duplicate_dismissals (track_a, track_b, dismissed_at) VALUES (1, 2, 0)")
            db.execSQL(
                "INSERT INTO track_merge_log (at, reason, kept_track_id, removed_track_id, title, artist, detail) " +
                    "VALUES (0, 'USER_MERGE', 1, 3, 'Song', 'Artist', '{}')",
            )

            db.execSQL("DELETE FROM tracks WHERE id = 1")

            assertEquals(0, count(db, "SELECT COUNT(*) FROM track_aliases"))
            assertEquals(0, count(db, "SELECT COUNT(*) FROM duplicate_dismissals"))
            assertEquals(1, count(db, "SELECT COUNT(*) FROM track_merge_log"))
        }
    }
}
