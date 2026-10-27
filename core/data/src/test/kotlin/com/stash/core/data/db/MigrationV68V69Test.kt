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
 * v68 -> v69 (Your record's lengths pass): the empty `record_lengths` table, keyed by song key, and nothing existing changed
 * (the record's plays among it). Walked from 68 and from 67; each run validates every table and index against the exported 69.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV68V69Test {

    private val DB_NAME = "migration-v68v69-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val from68 = arrayOf(StashDatabase.MIGRATION_68_69)
    private val from67 = arrayOf(StashDatabase.MIGRATION_67_68, *from68)

    @Test fun `68 to 69 adds the lengths table and keeps the library and the record's plays`() {
        helper.createDatabase(DB_NAME, 68).use {
            seedLibrary(it)
            it.execSQL("INSERT INTO record_plays (id, t, a, s, ms, f) VALUES ('ivy|frank ocean|1', 'Ivy', 'Frank Ocean', 1, 210000, 192)")
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 69, true, *from68)

        assertEquals(0, count(db, "SELECT COUNT(*) FROM record_lengths"))
        assertEquals(1, count(db, "SELECT COUNT(*) FROM record_plays"))
        assertLibraryUnchanged(db)
    }

    @Test fun `67 reaches 69`() {
        helper.createDatabase(DB_NAME, 67).use { seedLibrary(it) }
        val db = helper.runMigrationsAndValidate(DB_NAME, 69, true, *from67)
        assertEquals(0, count(db, "SELECT COUNT(*) FROM record_lengths"))
        assertLibraryUnchanged(db)
    }

    @Test fun `a song is kept once by its key`() {
        helper.createDatabase(DB_NAME, 68).use { seedLibrary(it) }
        val db = helper.runMigrationsAndValidate(DB_NAME, 69, true, *from68)
        db.execSQL("INSERT INTO record_lengths (`key`, kind, at) VALUES ('ivy|frank ocean', 0, 100)")
        db.execSQL("INSERT OR REPLACE INTO record_lengths (`key`, kind, at) VALUES ('ivy|frank ocean', 1, 200)")
        assertEquals(1, count(db, "SELECT COUNT(*) FROM record_lengths"))
        assertEquals(200, count(db, "SELECT at FROM record_lengths"))
    }
}
