package com.stash.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.stash.core.data.db.AllSongsMigrationFixtures.assertLibraryUnchanged
import com.stash.core.data.db.AllSongsMigrationFixtures.count
import com.stash.core.data.db.AllSongsMigrationFixtures.seedLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV74V75Test {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), StashDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun `SoundCloud columns preserve files membership and custom order`() {
        val name = "soundcloud-migration"
        helper.createDatabase(name, 74).use {
            seedLibrary(it)
            it.execSQL("INSERT INTO playlist_order (playlist_id) VALUES (9)")
            it.execSQL("INSERT INTO playlist_order_entries (playlist_id, track_id, rank) VALUES (9, 1, 8192)")
        }
        helper.runMigrationsAndValidate(name, 75, true, StashDatabase.MIGRATION_74_75).use { db ->
            assertLibraryUnchanged(db)
            assertEquals(1, count(db, "SELECT COUNT(*) FROM tracks WHERE soundcloud_id IS NULL AND soundcloud_permalink IS NULL AND soundcloud_preview = 0"))
            assertEquals(8192, count(db, "SELECT rank FROM playlist_order_entries WHERE playlist_id = 9 AND track_id = 1"))
            db.execSQL("UPDATE tracks SET soundcloud_id = '123' WHERE id = 1")
            // Clone without the primary key; the new remote identity must not be shared by two rows.
            val columns = mutableListOf<String>()
            db.query("PRAGMA table_info(tracks)").use { c ->
                while (c.moveToNext()) c.getString(1).takeIf { it != "id" }?.let(columns::add)
            }
            val names = columns.joinToString(",") { "`$it`" }
            assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
                db.execSQL("INSERT INTO tracks ($names) SELECT $names FROM tracks WHERE id = 1")
            }
        }
    }
}
