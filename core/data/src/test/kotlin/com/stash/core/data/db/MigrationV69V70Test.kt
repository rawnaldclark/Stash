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
 * v69 -> v70 (Your record's songs hidden on Spotify): the empty `record_hidden` table, keyed by song key, and nothing existing
 * changed (the record's plays and lengths among it). Walked from 69 and from 68; each run validates every table and index against
 * the exported 70.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV69V70Test {

    private val DB_NAME = "migration-v69v70-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val from69 = arrayOf(StashDatabase.MIGRATION_69_70)
    private val from68 = arrayOf(StashDatabase.MIGRATION_68_69, *from69)

    @Test fun `69 to 70 adds the hidden table and keeps the library, the record's plays and its lengths`() {
        helper.createDatabase(DB_NAME, 69).use {
            seedLibrary(it)
            it.execSQL("INSERT INTO record_plays (id, t, a, s, ms, f) VALUES ('ivy|frank ocean|1', 'Ivy', 'Frank Ocean', 1, 210000, 192)")
            it.execSQL("INSERT INTO record_lengths (`key`, kind, at) VALUES ('ivy|frank ocean', 0, 100)")
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 70, true, *from69)

        assertEquals(0, count(db, "SELECT COUNT(*) FROM record_hidden"))
        assertEquals(1, count(db, "SELECT COUNT(*) FROM record_plays"))
        assertEquals(1, count(db, "SELECT COUNT(*) FROM record_lengths"))
        assertLibraryUnchanged(db)
    }

    @Test fun `68 reaches 70`() {
        helper.createDatabase(DB_NAME, 68).use { seedLibrary(it) }
        val db = helper.runMigrationsAndValidate(DB_NAME, 70, true, *from68)
        assertEquals(0, count(db, "SELECT COUNT(*) FROM record_hidden"))
        assertLibraryUnchanged(db)
    }

    @Test fun `a song is kept once by its key, its first spelling`() {
        helper.createDatabase(DB_NAME, 69).use { seedLibrary(it) }
        val db = helper.runMigrationsAndValidate(DB_NAME, 70, true, *from69)
        db.execSQL("INSERT INTO record_hidden (`key`, t, a) VALUES ('never again|band', 'Never Again (Remastered)', 'Band')")
        db.execSQL("INSERT OR IGNORE INTO record_hidden (`key`, t, a) VALUES ('never again|band', 'never again', 'BAND')")
        assertEquals(1, count(db, "SELECT COUNT(*) FROM record_hidden"))
        db.query("SELECT t FROM record_hidden").use { c ->
            c.moveToFirst()
            assertEquals("Never Again (Remastered)", c.getString(0))
        }
    }
}
