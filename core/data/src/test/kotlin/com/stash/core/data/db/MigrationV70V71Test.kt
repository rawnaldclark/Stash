package com.stash.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.stash.core.data.db.AllSongsMigrationFixtures.assertLibraryUnchanged
import com.stash.core.data.db.AllSongsMigrationFixtures.count
import com.stash.core.data.db.AllSongsMigrationFixtures.seedLibrary
import com.stash.core.model.weblink.SongKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v70 -> v71 (Your record's taste): the empty taste tables (`record_taste_songs`, `record_taste`, `record_taste_keep`), the index on
 * `record_plays(s)`, and `record_hidden`'s keys worked out again with today's song key (JavaScript's ASCII-only classes): a key
 * v70 wrote with Kotlin's Unicode `IGNORE_CASE` moves to its new one, two that become one keep the first written, the rest stay.
 * Nothing else changes (the library, the record's plays and lengths among it). Walked from 70 and from 69; each run validates every
 * table and index against the exported 71.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV70V71Test {

    private val DB_NAME = "migration-v70v71-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val from70 = arrayOf(StashDatabase.MIGRATION_70_71)
    private val from69 = arrayOf(StashDatabase.MIGRATION_69_70, *from70)

    @Test fun `70 to 71 adds the taste tables and the plays' index, and keeps the library, the plays and the lengths`() {
        helper.createDatabase(DB_NAME, 70).use {
            seedLibrary(it)
            it.execSQL("INSERT INTO record_plays (id, t, a, s, ms, f) VALUES ('ivy|frank ocean|1', 'Ivy', 'Frank Ocean', 1, 210000, 192)")
            it.execSQL("INSERT INTO record_lengths (`key`, kind, at) VALUES ('ivy|frank ocean', 0, 100)")
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 71, true, *from70)

        assertEquals(0, count(db, "SELECT COUNT(*) FROM record_taste_songs"))
        assertEquals(0, count(db, "SELECT COUNT(*) FROM record_taste"))
        assertEquals(0, count(db, "SELECT COUNT(*) FROM record_taste_keep"))
        assertEquals(1, count(db, "SELECT COUNT(*) FROM record_plays"))
        assertEquals(1, count(db, "SELECT COUNT(*) FROM record_lengths"))
        assertEquals(1, count(db, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_record_plays_s'"))
        assertLibraryUnchanged(db)
    }

    @Test fun `the hidden songs are keyed by today's song key, the first written kept when two become one`() {
        helper.createDatabase(DB_NAME, 70).use {
            seedLibrary(it)
            // v70's keys (Kotlin's IGNORE_CASE took LONG S for an s, and so "Remaſter" for a remaster mark)
            it.execSQL("INSERT INTO record_hidden (`key`, t, a) VALUES ('song|band', 'Song - 2011 Remaſter', 'Band')")
            it.execSQL("INSERT INTO record_hidden (`key`, t, a) VALUES ('song 2011 remaster|band', 'Song 2011 Remaster', 'Band')")
            it.execSQL("INSERT INTO record_hidden (`key`, t, a) VALUES ('never again|artist y', 'Never Again (Remastered)', 'Artist Y')")
            it.execSQL("INSERT INTO record_hidden (`key`, t, a) VALUES ('static hum|artist z', 'Static Hum', 'Artist Z')")
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 71, true, *from70)

        val rows = db.query("SELECT `key`, t, a FROM record_hidden ORDER BY `key`").use { c ->
            buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getString(2))) }
        }
        assertEquals(
            listOf(
                Triple("never again|artist y", "Never Again (Remastered)", "Artist Y"),
                // "Remaſter" is no remaster mark in JavaScript: its song key keeps the words (folded), and it was written first
                Triple("song 2011 remaster|band", "Song - 2011 Remaſter", "Band"),
                Triple("static hum|artist z", "Static Hum", "Artist Z"),
            ),
            rows,
        )
        // Every key is today's song key of its words
        for ((k, t, a) in rows) assertEquals(SongKey.songKey(t, a), k)
        assertLibraryUnchanged(db)
    }

    @Test fun `hidden songs already keyed as today stay as they were`() {
        helper.createDatabase(DB_NAME, 70).use {
            seedLibrary(it)
            it.execSQL("INSERT INTO record_hidden (`key`, t, a) VALUES ('never again|artist y', 'Never Again (Remastered)', 'Artist Y')")
        }
        val db = helper.runMigrationsAndValidate(DB_NAME, 71, true, *from70)
        assertEquals(1, count(db, "SELECT COUNT(*) FROM record_hidden WHERE `key` = 'never again|artist y' AND t = 'Never Again (Remastered)'"))
    }

    @Test fun `69 reaches 71`() {
        helper.createDatabase(DB_NAME, 69).use { seedLibrary(it) }
        val db = helper.runMigrationsAndValidate(DB_NAME, 71, true, *from69)
        assertEquals(0, count(db, "SELECT COUNT(*) FROM record_hidden"))
        assertEquals(0, count(db, "SELECT COUNT(*) FROM record_taste"))
        assertLibraryUnchanged(db)
    }

    @Test fun `a put-back song is kept once, its first time`() {
        helper.createDatabase(DB_NAME, 70).use { seedLibrary(it) }
        val db = helper.runMigrationsAndValidate(DB_NAME, 71, true, *from70)
        db.execSQL("INSERT OR IGNORE INTO record_taste_keep (`key`, at) VALUES ('song|band', 1)")
        db.execSQL("INSERT OR IGNORE INTO record_taste_keep (`key`, at) VALUES ('song|band', 2)")
        assertEquals(1, count(db, "SELECT COUNT(*) FROM record_taste_keep WHERE at = 1"))
    }
}
