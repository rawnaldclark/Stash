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
 * v67 -> v68 (Your record's imported plays): the empty `record_plays` table, keyed by id with no other index, and nothing
 * existing changed.
 * Walked from 67 and from 66; each run validates every table and index against the exported 68.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV67V68Test {

    private val DB_NAME = "migration-v67v68-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val from67 = arrayOf(StashDatabase.MIGRATION_67_68)
    private val from66 = arrayOf(StashDatabase.MIGRATION_66_67, *from67)

    @Test fun `67 to 68 adds the record's table and keeps the library`() {
        helper.createDatabase(DB_NAME, 67).use { seedLibrary(it) }

        val db = helper.runMigrationsAndValidate(DB_NAME, 68, true, *from67)

        assertEquals(0, count(db, "SELECT COUNT(*) FROM record_plays"))
        assertLibraryUnchanged(db)
        val indexes = db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'record_plays'").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        // Only the primary key's own index (SQLite's autoindex for a TEXT key)
        assertTrue(indexes.all { it.startsWith("sqlite_autoindex_record_plays") })
    }

    @Test fun `66 reaches 68`() {
        helper.createDatabase(DB_NAME, 66).use { seedLibrary(it) }
        val db = helper.runMigrationsAndValidate(DB_NAME, 68, true, *from66)
        assertEquals(0, count(db, "SELECT COUNT(*) FROM record_plays"))
        assertLibraryUnchanged(db)
    }

    @Test fun `a play is kept once by its id, with no album or Spotify id needed`() {
        helper.createDatabase(DB_NAME, 67).use { seedLibrary(it) }
        val db = helper.runMigrationsAndValidate(DB_NAME, 68, true, *from67)
        db.execSQL("INSERT INTO record_plays (id, t, a, s, ms, f) VALUES ('ivy|frank ocean|1', 'Ivy', 'Frank Ocean', 1, 2, 16)")
        db.execSQL("INSERT OR REPLACE INTO record_plays (id, t, a, al, sid, s, ms, f) VALUES ('ivy|frank ocean|1', 'Ivy', 'Frank Ocean', 'Blonde', '2ZWlPOoWh0626oTaHrnl2a', 1, 2, 34)")
        assertEquals(1, count(db, "SELECT COUNT(*) FROM record_plays"))
        assertEquals(34, count(db, "SELECT f FROM record_plays"))
    }
}
