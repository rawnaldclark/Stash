package com.stash.core.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The import's search-index repair (#838): a backup made on schema 67-76 can carry a stale `tracks_fts`, which
 * Android 15's SQLite (3.44+) reports as corrupt in the integrity check that runs before the migration chain.
 *
 * Robolectric's `integrity_check` does not look inside FTS tables, so it can't produce that report. These tests
 * hand [repairSearchIndexIfOnlyProblem] the words a device's SQLite prints for it (captured from SQLite 3.50)
 * and check what a user would see: whether search still finds a song by an album it no longer has.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SearchIndexRepairTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    /** A backup's database at [version]; with [staleIndex], its index still holds the album 66 -> 67 cleared. */
    private fun stagedBackup(version: Int, staleIndex: Boolean = true): File {
        val name = "staged-$version-$staleIndex"
        helper.createDatabase(name, version).use { db ->
            db.execSQL(
                "INSERT INTO tracks (id, title, artist, album, album_artist, duration_ms, file_format, " +
                    "quality_kbps, file_size_bytes, source, date_added, play_count, is_downloaded, " +
                    "canonical_title, canonical_artist, match_confidence, match_dismissed) VALUES " +
                    "(1, 'Blinding Lights', 'The Weeknd', 'Now 35', 'Various Artists', 200000, 'opus', " +
                    "0, 0, 'YOUTUBE', 1, 0, 0, 'blinding lights', 'the weeknd', 0, 0)",
            )
            if (!staleIndex) return@use
            // Room drops the sync triggers around a migration chain; 66 -> 67 cleared the album meanwhile.
            val triggers = db.query(
                "SELECT name, sql FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'room_fts_content_sync_%'",
            ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
            assertEquals(4, triggers.size)
            triggers.forEach { (name, _) -> db.execSQL("DROP TRIGGER `$name`") }
            db.execSQL("UPDATE tracks SET album = '', album_artist = '' WHERE id = 1")
            triggers.forEach { (_, sql) -> db.execSQL(sql) }
        }
        return context.getDatabasePath(name)
    }

    private fun <T> openStaged(file: File, block: (SQLiteDatabase) -> T): T =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use(block)

    private fun matches(db: SQLiteDatabase, query: String): Int =
        db.rawQuery("SELECT count(*) FROM tracks_fts WHERE tracks_fts MATCH ?", arrayOf(query))
            .use { it.moveToFirst(); it.getInt(0) }

    @Test fun `a stale search index alone is rebuilt on the staged copy and nothing is left wrong`() {
        for (version in listOf(67, 76)) {
            openStaged(stagedBackup(version)) { db ->
                assertEquals("v$version stale before the repair", 1, matches(db, "now"))

                val left = repairSearchIndexIfOnlyProblem(db, listOf(STALE_INDEX), "staged-v$version")

                assertEquals("v$version", emptyList<String>(), left)
                assertEquals("v$version", 0, matches(db, "now"))
                assertEquals("v$version", 1, matches(db, "blinding"))
                assertEquals("v$version", 1, matches(db, "weeknd"))
            }
        }
    }

    @Test fun `any other problem leaves the file untouched and the problems as they were`() {
        val problems = listOf(STALE_INDEX, BROKEN_PAGE)
        openStaged(stagedBackup(76)) { db ->
            assertEquals(problems, repairSearchIndexIfOnlyProblem(db, problems, "staged"))
            assertEquals("no rebuild ran", 1, matches(db, "now"))
        }
    }

    @Test fun `a sound backup passes the import check with the repair on`() {
        val file = stagedBackup(76, staleIndex = false)

        DatabaseBackupManager(context, mockk(relaxed = true)).verifyDatabaseIntegrity(file, repairSearchIndex = true)

        openStaged(file) { db -> assertEquals(1, matches(db, "blinding")) }
    }

    @Test fun `only problems that all name tracks_fts count as the search index's`() {
        assertTrue(onlySearchIndexProblems(listOf(STALE_INDEX)))
        assertTrue(onlySearchIndexProblems(listOf(STALE_INDEX, UNREADABLE_INDEX)))

        assertFalse("nothing wrong is nothing to repair", onlySearchIndexProblems(emptyList()))
        assertFalse(onlySearchIndexProblems(listOf(STALE_INDEX, BROKEN_PAGE)))
        assertFalse(onlySearchIndexProblems(listOf(STALE_INDEX, "row 4 missing from index index_tracks_spotify_uri")))
        assertFalse(onlySearchIndexProblems(listOf("integrity_check returned no rows")))
    }

    private companion object {
        /** What SQLite 3.44+ prints for a stale `tracks_fts` (measured on 3.50.4). */
        const val STALE_INDEX = "malformed inverted index for FTS4 table main.tracks_fts"

        /** SQLite's other FTS report, when it can't read the index at all. */
        const val UNREADABLE_INDEX =
            "unable to validate the inverted index for FTS4 table main.tracks_fts: database disk image is malformed"

        /** A b-tree fault, which names no table: never the search index's to fix. */
        const val BROKEN_PAGE = "Tree 5 page 5: btreeInitPage() returns error code 11"
    }
}
