package com.stash.core.data.db

import androidx.room.testing.MigrationTestHelper
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
 * v50 -> v51 adds `playlists.keep_offline` (the playlist page's Download switch, #474).
 * An existing playlist survives with the switch off. It also clears every mix's sync
 * switch once, replacing the every-launch reset (#438) that wiped real consent, and
 * drops Stash Mixes' own old download rows that never started.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV50V51Test {

    private val DB_NAME = "migration-v50v51-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun `50 to 51 adds keep_offline, clears mix sync once, and keeps the rows`() {
        helper.createDatabase(DB_NAME, 50).use { db ->
            db.execSQL(
                "INSERT INTO playlists (id, name, source, source_id, type, track_count, is_active, " +
                    "sync_enabled, date_added, hide_from_home, pinned) VALUES " +
                    "(1, 'Release Radar', 'SPOTIFY', 'spotify:playlist:rr', 'DAILY_MIX', 30, 1, 1, 0, 0, 0), " +
                    "(2, 'Road Trip', 'SPOTIFY', 'spotify:playlist:mine', 'CUSTOM', 12, 1, 1, 0, 0, 0)",
            )
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 51, true, StashDatabase.MIGRATION_50_51)

        db.query("SELECT name, sync_enabled, keep_offline FROM playlists ORDER BY id").use { c ->
            assertTrue(c.moveToNext())
            assertEquals("Release Radar", c.getString(0))
            assertEquals(0L, c.getLong(1)) // the mix's stale flag is cleared
            assertEquals(0L, c.getLong(2))
            assertTrue(c.moveToNext())
            assertEquals("Road Trip", c.getString(0))
            assertEquals(1L, c.getLong(1)) // a playlist's switch is the user's, untouched
            assertEquals(0L, c.getLong(2))
        }
    }

    @Test fun `50 to 51 drops only Stash Mixes' old download rows`() {
        val old = 1_779_753_600_000L - 30L * 24 * 60 * 60 * 1000 // a month before 2026-05-26
        val recent = 1_779_753_600_000L + 24L * 60 * 60 * 1000
        helper.createDatabase(DB_NAME, 50).use { db ->
            db.execSQL(
                "INSERT INTO stash_mix_recipes (id, name, include_tags_csv, exclude_tags_csv, affinity_bias, " +
                    "discovery_ratio, freshness_window_days, target_length, is_builtin, is_active, created_at) " +
                    "VALUES (1, 'Daily Discover', '', '', 0.5, 0.5, 7, 50, 1, 1, 0)",
            )
            db.execSQL(
                "INSERT INTO playlists (id, name, source, source_id, type, track_count, is_active, " +
                    "sync_enabled, date_added, hide_from_home, pinned) " +
                    "VALUES (10, 'Followed', 'BOTH', 'share:abc', 'CUSTOM', 1, 1, 1, 0, 0, 0)",
            )
            for (id in 1..4) {
                db.execSQL(
                    "INSERT INTO tracks (id, title, artist, album, duration_ms, file_format, quality_kbps, " +
                        "file_size_bytes, source, date_added, play_count, is_downloaded, canonical_title, " +
                        "canonical_artist, match_confidence, match_dismissed) " +
                        "VALUES ($id, 'Song $id', 'Artist', '', 200000, 'opus', 160, 0, 'YOUTUBE', 0, 0, 0, " +
                        "'song $id', 'artist', 0.0, 0)",
                )
            }
            // 1: a discovery, queued before v0.9.37 and never started: dropped.
            // 2: queued then too, but never a discovery (a tap back then): kept.
            // 3: a discovery, queued after the cutoff: kept.
            // 4: a discovery a followed mix with "Download this mix" on also holds: kept.
            for ((track, createdAt) in listOf(1 to old, 2 to old, 3 to recent, 4 to old)) {
                db.execSQL(
                    "INSERT INTO download_queue (track_id, sync_id, status, search_query, retry_count, " +
                        "failure_type, user_requested, created_at) " +
                        "VALUES ($track, NULL, 'PENDING', 'q', 0, 'NONE', 0, $createdAt)",
                )
            }
            for (track in listOf(1, 3, 4)) {
                db.execSQL(
                    "INSERT INTO discovery_queue (recipe_id, artist, title, seed_artist, status, track_id, queued_at) " +
                        "VALUES (1, 'Artist', 'Song $track', 'Seed', 'DONE', $track, 0)",
                )
            }
            db.execSQL("INSERT INTO playlist_tracks (playlist_id, track_id, position, added_at) VALUES (10, 4, 0, 0)")
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 51, true, StashDatabase.MIGRATION_50_51)

        db.query("SELECT track_id FROM download_queue ORDER BY track_id").use { c ->
            val left = generateSequence { if (c.moveToNext()) c.getLong(0) else null }.toList()
            assertEquals(listOf(2L, 3L, 4L), left)
        }
    }
}
