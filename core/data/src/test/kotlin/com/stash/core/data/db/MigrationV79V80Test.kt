package com.stash.core.data.db

import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.stash.core.data.db.AllSongsMigrationFixtures.assertLibraryUnchanged
import com.stash.core.data.db.AllSongsMigrationFixtures.count
import com.stash.core.data.db.AllSongsMigrationFixtures.seedLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v79 -> v80, add-ons phase 2 (plan 2026-11-09 §5.2): `tidal_match_cache` becomes `addon_refs`,
 * and every Tidal match, miss and AAC-mode stamp carries over unchanged under the add-on id
 * `tidal`, so a song matched before the update plays without a new search. Every run validates
 * the result against the exported 80.json (validateDroppedTables: the old table is gone).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV79V80Test {

    private val dbName = "migration-v79v80-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    /** One row of each kind TidalClient writes: a match with stamps, a no-match, a short (backdated) miss, an AAC-mode stamp. */
    private val rows = listOf(
        Row("isrc:USQX91300108|explicit", "td:363236466", "Get Lucky", "Daft Punk", "https://resources.tidal.com/a.jpg", 0.95, 1_793_000_000_000, 1_793_100_000_000, null),
        Row("t:daft punk|one more time|32|explicit", null, "", "", null, 0.0, 1_793_000_000_001, null, null),
        Row("isrc:GBDUW0000053|clean", "td:1550546", "One More Time", "Daft Punk", null, 0.81, 1_792_000_000_000, 1_791_500_000_000, 1_791_500_000_000),
        Row("aaconly|isrc:USQX91300105|explicit", null, "", "", null, 0.0, 1_793_200_000_000, null, null),
    )

    private data class Row(
        val key: String, val tidalId: String?, val title: String, val artist: String, val artwork: String?,
        val confidence: Double, val searchedAt: Long, val losslessMissAt: Long?, val aacMissAt: Long?,
    )

    private fun seed(db: SupportSQLiteDatabase) {
        seedLibrary(db)
        for (r in rows) {
            db.execSQL(
                "INSERT INTO tidal_match_cache (`key`, tidal_id, title, artist, artwork, confidence, searched_at, " +
                    "lossless_miss_at, aac_miss_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(r.key, r.tidalId, r.title, r.artist, r.artwork, r.confidence, r.searchedAt, r.losslessMissAt, r.aacMissAt),
            )
        }
    }

    private fun tables(db: SupportSQLiteDatabase): Set<String> =
        db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }

    private fun readBack(db: SupportSQLiteDatabase): List<Row> =
        db.query(
            "SELECT song_key, remote_id, title, artist, artwork, confidence, resolved_at, lossless_miss_at, aac_miss_at " +
                "FROM addon_refs WHERE addon_id = 'tidal' ORDER BY resolved_at",
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Row(
                            key = c.getString(0),
                            tidalId = if (c.isNull(1)) null else c.getString(1),
                            title = c.getString(2),
                            artist = c.getString(3),
                            artwork = if (c.isNull(4)) null else c.getString(4),
                            confidence = c.getDouble(5),
                            searchedAt = c.getLong(6),
                            losslessMissAt = if (c.isNull(7)) null else c.getLong(7),
                            aacMissAt = if (c.isNull(8)) null else c.getLong(8),
                        ),
                    )
                }
            }
        }

    @Test fun `79 to 80 carries every Tidal row into addon_refs and drops the old table`() {
        helper.createDatabase(dbName, 79).use { db ->
            seed(db)
            assertEquals(4, count(db, "SELECT COUNT(*) FROM tidal_match_cache"))
        }

        val db = helper.runMigrationsAndValidate(dbName, 80, true, StashDatabase.MIGRATION_79_80)

        assertFalse("tidal_match_cache" in tables(db))
        assertEquals(4, count(db, "SELECT COUNT(*) FROM addon_refs"))
        assertEquals("every row is the Tidal add-on's", 4, count(db, "SELECT COUNT(*) FROM addon_refs WHERE addon_id = 'tidal'"))
        // Value for value, the float confidence included (REAL both sides).
        val back = readBack(db)
        val expected = rows.sortedBy { it.searchedAt }
        assertEquals(expected.map { it.copy(confidence = 0.0) }, back.map { it.copy(confidence = 0.0) })
        expected.zip(back).forEach { (e, b) -> assertEquals(e.key, e.confidence, b.confidence, 1e-6) }
        assertLibraryUnchanged(db)
    }

    @Test fun `an empty cache migrates to an empty table`() {
        helper.createDatabase(dbName, 79).use { seedLibrary(it) }
        val db = helper.runMigrationsAndValidate(dbName, 80, true, StashDatabase.MIGRATION_79_80)
        assertEquals(0, count(db, "SELECT COUNT(*) FROM addon_refs"))
        assertTrue("addon_refs" in tables(db))
        assertLibraryUnchanged(db)
    }

    @Test fun `addon_refs is keyed by add-on and song`() {
        helper.createDatabase(dbName, 79).use { seed(it) }
        val db = helper.runMigrationsAndValidate(dbName, 80, true, StashDatabase.MIGRATION_79_80)

        // The same song key under another add-on is its own row.
        db.execSQL(
            "INSERT INTO addon_refs (addon_id, song_key, remote_id, title, artist, confidence, resolved_at) " +
                "VALUES ('jiosaavn', 'isrc:USQX91300108|explicit', 'js:1', 'Get Lucky', 'Daft Punk', 0.9, 5)",
        )
        assertEquals(2, count(db, "SELECT COUNT(*) FROM addon_refs WHERE song_key = 'isrc:USQX91300108|explicit'"))
        val duplicate = runCatching {
            db.execSQL(
                "INSERT INTO addon_refs (addon_id, song_key, remote_id, title, artist, confidence, resolved_at) " +
                    "VALUES ('tidal', 'isrc:USQX91300108|explicit', 'td:2', 'X', 'Y', 0.5, 6)",
            )
        }.exceptionOrNull()
        assertTrue("a second row for one add-on and song: $duplicate", duplicate is SQLiteConstraintException)
    }

    @Test fun `78 reaches 80 through the whole chain`() {
        helper.createDatabase(dbName, 78).use { db ->
            seedLibrary(db)
            db.execSQL(
                "INSERT INTO tidal_match_cache (`key`, tidal_id, title, artist, artwork, confidence, searched_at, " +
                    "lossless_miss_at, aac_miss_at) VALUES ('isrc:X|explicit', 'td:1', 'Song', 'A', NULL, 0.9, 5, NULL, NULL)",
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 80, true, StashDatabase.MIGRATION_78_79, StashDatabase.MIGRATION_79_80)

        assertEquals(1, count(db, "SELECT COUNT(*) FROM addon_refs WHERE addon_id = 'tidal' AND remote_id = 'td:1' AND resolved_at = 5"))
        assertLibraryUnchanged(db)
    }
}
