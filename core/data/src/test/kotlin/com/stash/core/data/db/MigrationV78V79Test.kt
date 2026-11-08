package com.stash.core.data.db

import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.stash.core.data.db.AllSongsMigrationFixtures.assertLibraryUnchanged
import com.stash.core.data.db.AllSongsMigrationFixtures.count
import com.stash.core.data.db.AllSongsMigrationFixtures.indexes
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
 * v78 -> v79, more than one Spotify account (plan 2026-11-04 §2.1, §2.3): additive only.
 * `playlists.spotify_account` and `remote_playlist_snapshots.spotify_account` appear NULL on every
 * existing row (the main account's, today's behaviour), and the empty `spotify_accounts` table
 * arrives with its unique `user_id` index. Every run validates the result against the exported
 * 79.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV78V79Test {

    private val dbName = "migration-v78v79-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    /** The library fixture plus the main account's Liked Songs, a library playlist, and a snapshot of it. */
    private fun seed(db: SupportSQLiteDatabase) {
        seedLibrary(db)
        db.execSQL(
            "INSERT INTO playlists (id, name, source, source_id, type, is_active, sync_enabled, track_count, date_added) " +
                "VALUES (10, 'Liked Songs', 'SPOTIFY', 'spotify_liked_songs', 'LIKED_SONGS', 1, 1, 0, 0), " +
                "(11, 'Road trip', 'SPOTIFY', '37abc', 'CUSTOM', 1, 0, 0, 0)",
        )
        db.execSQL(
            "INSERT INTO sync_history (id, started_at, status, playlists_checked, new_tracks_found, " +
                "tracks_downloaded, tracks_failed, bytes_downloaded, trigger) " +
                "VALUES (1, 0, 'COMPLETED', 2, 0, 0, 0, 0, 'MANUAL')",
        )
        db.execSQL(
            "INSERT INTO remote_playlist_snapshots (id, sync_id, source, source_playlist_id, playlist_name, " +
                "playlist_type, track_count, fetched_at, partial) " +
                "VALUES (1, 1, 'SPOTIFY', '37abc', 'Road trip', 'CUSTOM', 0, 0, 0)",
        )
    }

    private fun columns(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA table_info(`$table`)").use { c ->
            val name = c.getColumnIndexOrThrow("name")
            buildList { while (c.moveToNext()) add(c.getString(name)) }
        }

    @Test fun `78 to 79 adds the account columns, NULL on every existing row, and keeps the library`() {
        helper.createDatabase(dbName, 78).use { db ->
            seed(db)
            assertFalse("spotify_account" in columns(db, "playlists"))
            assertFalse("spotify_account" in columns(db, "remote_playlist_snapshots"))
        }

        val db = helper.runMigrationsAndValidate(dbName, 79, true, StashDatabase.MIGRATION_78_79)

        assertTrue("spotify_account" in columns(db, "playlists"))
        assertTrue("spotify_account" in columns(db, "remote_playlist_snapshots"))
        assertEquals(3, count(db, "SELECT COUNT(*) FROM playlists"))
        assertEquals("every playlist stays the main account's", 3, count(db, "SELECT COUNT(*) FROM playlists WHERE spotify_account IS NULL"))
        assertEquals(1, count(db, "SELECT COUNT(*) FROM remote_playlist_snapshots WHERE spotify_account IS NULL"))
        assertEquals(1, count(db, "SELECT COUNT(*) FROM playlists WHERE source_id = 'spotify_liked_songs' AND type = 'LIKED_SONGS'"))
        assertLibraryUnchanged(db)
    }

    @Test fun `79 has an empty spotify_accounts table with a unique user id`() {
        helper.createDatabase(dbName, 78).use { seed(it) }

        val db = helper.runMigrationsAndValidate(dbName, 79, true, StashDatabase.MIGRATION_78_79)

        assertEquals("no account is made up", 0, count(db, "SELECT COUNT(*) FROM spotify_accounts"))
        assertEquals(mapOf("index_spotify_accounts_user_id" to true), indexes(db, "spotify_accounts"))
        assertEquals(
            listOf("id", "user_id", "label", "added_at", "sync_playlists", "sync_liked", "sync_albums", "sync_mixes", "signed_out_at"),
            columns(db, "spotify_accounts"),
        )

        // The switches' defaults: playlists, Liked Songs and saved albums on; mixes off; signed in.
        db.execSQL("INSERT INTO spotify_accounts (user_id, label, added_at) VALUES ('lm7d11nsf65n11sojl4twwr6t', 'Sam', 5)")
        db.query("SELECT sync_playlists, sync_liked, sync_albums, sync_mixes, signed_out_at FROM spotify_accounts").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(listOf(1, 1, 1, 0), (0..3).map { c.getInt(it) })
            assertTrue(c.isNull(4))
        }

        // One row per Spotify account.
        val duplicate = runCatching {
            db.execSQL("INSERT INTO spotify_accounts (user_id, label, added_at) VALUES ('lm7d11nsf65n11sojl4twwr6t', 'Again', 6)")
        }.exceptionOrNull()
        assertTrue("a second row for the same account: $duplicate", duplicate is SQLiteConstraintException)
        assertEquals(1, count(db, "SELECT COUNT(*) FROM spotify_accounts"))
    }

    @Test fun `76 reaches 79 through the whole chain`() {
        helper.createDatabase(dbName, 76).use { seedLibrary(it) }

        val db = helper.runMigrationsAndValidate(
            dbName,
            79,
            true,
            StashDatabase.MIGRATION_76_77,
            StashDatabase.MIGRATION_77_78,
            StashDatabase.MIGRATION_78_79,
        )

        assertEquals(1, count(db, "SELECT COUNT(*) FROM playlists WHERE spotify_account IS NULL"))
        assertLibraryUnchanged(db)
    }
}
