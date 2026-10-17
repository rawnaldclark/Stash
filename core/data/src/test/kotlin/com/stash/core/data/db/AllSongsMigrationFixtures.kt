package com.stash.core.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Shared by the All Songs migration tests (MigrationV56V57Test, MigrationV57V58Test): a small
 * library to carry through, and the All Songs PR's three debug databases.
 *
 * Those debug builds (the `.debug` app only, never released) numbered the scan tables onto
 * versions master later shipped with other content, so the real chain must heal each of them:
 *  - its first debug v55: master's v54 plus scan tables without `last_modified`;
 *  - its debug v56: master's v55 plus the scan tables, but no `tidal_match_cache` (master's v56);
 *  - its debug v57: as v56 plus `album_art_path`.
 * Each is rebuilt from master's v55 schema JSON, then reshaped and renumbered by hand.
 */
internal object AllSongsMigrationFixtures {

    const val TRACKS_INDEX = "index_tracks_canonical_title_canonical_artist"

    private const val SCAN_FOLDERS_DDL =
        "CREATE TABLE IF NOT EXISTS `scan_folders` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`tree_uri` TEXT NOT NULL, `display_name` TEXT NOT NULL, `added_at` INTEGER NOT NULL)"

    /** The scan tables' indices, the same in every debug build. */
    private fun createScanIndices(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_scan_folders_tree_uri` ON `scan_folders` (`tree_uri`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_scanned_songs_uri` ON `scanned_songs` (`uri`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_scanned_songs_folder_id` ON `scanned_songs` (`folder_id`)")
    }

    /** `scanned_songs` as the debug v56 made it; [withArt] adds v57's `album_art_path`. */
    private fun scannedSongsDdl(withLastModified: Boolean, withArt: Boolean): String =
        "CREATE TABLE IF NOT EXISTS `scanned_songs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`folder_id` INTEGER NOT NULL, `uri` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, " +
            "`album` TEXT NOT NULL, `duration_ms` INTEGER NOT NULL, `file_format` TEXT NOT NULL, " +
            "`file_size_bytes` INTEGER NOT NULL, " +
            (if (withLastModified) "`last_modified` INTEGER NOT NULL, " else "") +
            "`date_added` INTEGER NOT NULL, " +
            (if (withArt) "`album_art_path` TEXT, " else "") +
            "FOREIGN KEY(`folder_id`) REFERENCES `scan_folders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"

    /** Turns a fresh master v55 into the PR's first debug v55 (still numbered 55), with one folder and song. */
    fun makeFirstDebugV55(db: SupportSQLiteDatabase) {
        db.execSQL("DROP INDEX IF EXISTS `$TRACKS_INDEX`")
        db.execSQL(SCAN_FOLDERS_DDL)
        db.execSQL(scannedSongsDdl(withLastModified = false, withArt = false))
        createScanIndices(db)
        db.execSQL("INSERT INTO scan_folders (id, tree_uri, display_name, added_at) VALUES (1, 'content://t/tree/a', 'A', 0)")
        db.execSQL(
            "INSERT INTO scanned_songs (id, folder_id, uri, title, artist, album, duration_ms, file_format, " +
                "file_size_bytes, date_added) VALUES (5, 1, 'content://t/tree/a/document/x', 'T', 'A', '', 0, 'mp3', 5, 0)",
        )
    }

    /** Turns a fresh master v55 into the PR's debug v56 and renumbers it 56, with one folder and song. */
    fun makeDebugV56(db: SupportSQLiteDatabase) {
        db.execSQL(SCAN_FOLDERS_DDL)
        db.execSQL(scannedSongsDdl(withLastModified = true, withArt = false))
        createScanIndices(db)
        seedScanV57Shape(db)
        db.version = 56
    }

    /** Turns a fresh master v55 into the PR's debug v57 and renumbers it 57, with a folder and a song with a cover. */
    fun makeDebugV57(db: SupportSQLiteDatabase) {
        db.execSQL(SCAN_FOLDERS_DDL)
        db.execSQL(scannedSongsDdl(withLastModified = true, withArt = true))
        createScanIndices(db)
        seedScanV57Shape(db)
        db.execSQL("UPDATE scanned_songs SET album_art_path = 'scanned_abc.jpg' WHERE id = 5")
        db.version = 57
    }

    /** One folder and one song in the v57 shape (`last_modified`, no cover column needed). */
    fun seedScanV57Shape(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO scan_folders (id, tree_uri, display_name, added_at) VALUES (1, 'content://t/tree/a', 'A', 11)")
        db.execSQL(
            "INSERT INTO scanned_songs (id, folder_id, uri, title, artist, album, duration_ms, file_format, " +
                "file_size_bytes, last_modified, date_added) " +
                "VALUES (5, 1, 'content://t/tree/a/document/x', 'T', 'A', 'Al', 2000, 'flac', 5, 7, 99)",
        )
    }

    /** A library song in a custom playlist: what every step must leave alone. */
    fun seedLibrary(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO tracks (id, title, artist, album, duration_ms, file_format, quality_kbps, " +
                "file_size_bytes, source, date_added, play_count, is_downloaded, canonical_title, " +
                "canonical_artist, match_confidence, match_dismissed, match_flagged, youtube_id) " +
                "VALUES (1, 'Song', 'Artist', 'Album', 1000, 'flac', 0, 10, 'LOCAL', 0, 3, 1, 'song', 'artist', 1.0, 0, 0, NULL)",
        )
        db.execSQL(
            "INSERT INTO playlists (id, name, source, source_id, type, is_active, sync_enabled, track_count, date_added) " +
                "VALUES (9, 'P', 'BOTH', 'custom_9', 'CUSTOM', 1, 1, 1, 0)",
        )
        db.execSQL("INSERT INTO playlist_tracks (playlist_id, track_id, position, added_at) VALUES (9, 1, 0, 0)")
    }

    fun assertLibraryUnchanged(db: SupportSQLiteDatabase) {
        db.query("SELECT id, title, file_format, play_count, is_downloaded FROM tracks").use { c ->
            assertEquals(1, c.count)
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
            assertEquals("Song", c.getString(1))
            assertEquals("flac", c.getString(2))
            assertEquals(3, c.getInt(3))
            assertEquals(1, c.getInt(4))
        }
        db.query("SELECT track_id FROM playlist_tracks WHERE playlist_id = 9").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
        }
    }

    /** A row in master's v56 `tidal_match_cache`. */
    fun seedTidalMatch(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO tidal_match_cache (`key`, tidal_id, title, artist, artwork, confidence, searched_at, " +
                "lossless_miss_at, aac_miss_at) VALUES ('isrc:X', 'td:1', 'Song', 'A', NULL, 0.9, 5, NULL, NULL)",
        )
    }

    /** name -> unique, for every index on [table]. */
    fun indexes(db: SupportSQLiteDatabase, table: String): Map<String, Boolean> =
        db.query("PRAGMA index_list(`$table`)").use { c ->
            buildMap {
                while (c.moveToNext()) {
                    put(c.getString(c.getColumnIndexOrThrow("name")), c.getInt(c.getColumnIndexOrThrow("unique")) == 1)
                }
            }
        }

    fun columns(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA table_info(`$table`)").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
        }

    fun count(db: SupportSQLiteDatabase, sql: String): Int =
        db.query(sql).use { c -> c.moveToFirst(); c.getInt(0) }
}
