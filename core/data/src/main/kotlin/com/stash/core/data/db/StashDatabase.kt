package com.stash.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.stash.core.data.db.converter.Converters
import com.stash.core.data.db.dao.ArtistProfileCacheDao
import com.stash.core.data.db.dao.DiscoveryQueueDao
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.LastFmCacheDao
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.LyricsDao
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.RemoteSnapshotDao
import com.stash.core.data.db.dao.SourceAccountDao
import com.stash.core.data.db.dao.SpotifyResolutionDao
import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.db.dao.SyncHistoryDao
import com.stash.core.data.db.dao.TrackBlocklistDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.dao.TrackSkipEventDao
import com.stash.core.data.db.dao.TrackTagDao
import com.stash.core.data.db.entity.ArtistProfileCacheEntity
import com.stash.core.data.db.entity.DiscoveryQueueEntity
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.LastFmCacheEntity
import com.stash.core.data.db.entity.ListeningEventEntity
import com.stash.core.data.db.entity.LyricsEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.RemotePlaylistSnapshotEntity
import com.stash.core.data.db.entity.RemoteTrackSnapshotEntity
import com.stash.core.data.db.entity.SourceAccountEntity
import com.stash.core.data.db.entity.SpotifyResolutionEntity
import com.stash.core.data.db.entity.StashMixRecipeEntity
import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.data.db.entity.TrackBlocklistEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.db.entity.TrackFts
import com.stash.core.data.db.entity.TrackSkipEventEntity
import com.stash.core.data.db.entity.TrackTagEntity

/**
 * Central Room database for the Stash application.
 *
 * Version 2 — adds remote playlist/track snapshot entities and
 * a snapshot_id column on the playlists table.
 *
 * **Security note:** This database stores track metadata, playlists, sync
 * diagnostics, and download queue state. It does NOT store authentication
 * credentials (OAuth tokens, sp_dc cookies, etc.) — those live in
 * [com.stash.core.auth.EncryptedTokenStore] backed by EncryptedSharedPreferences.
 * The app manifest disables backups (`allowBackup=false`, `fullBackupContent=false`)
 * to prevent database extraction via `adb backup`. On rooted devices the
 * unencrypted SQLite file is still readable; adding SQLCipher encryption is a
 * future enhancement tracked separately.
 */
@Database(
    entities = [
        TrackEntity::class,
        PlaylistEntity::class,
        PlaylistTrackCrossRef::class,
        SyncHistoryEntity::class,
        DownloadQueueEntity::class,
        SourceAccountEntity::class,
        TrackFts::class,
        RemotePlaylistSnapshotEntity::class,
        RemoteTrackSnapshotEntity::class,
        ArtistProfileCacheEntity::class,
        ListeningEventEntity::class,
        TrackTagEntity::class,
        StashMixRecipeEntity::class,
        DiscoveryQueueEntity::class,
        TrackBlocklistEntity::class,
        TrackSkipEventEntity::class,
        LyricsEntity::class,
        LastFmCacheEntity::class,
        SpotifyResolutionEntity::class,
    ],
    version = 33,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class StashDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao

    abstract fun playlistDao(): PlaylistDao

    abstract fun syncHistoryDao(): SyncHistoryDao

    abstract fun downloadQueueDao(): DownloadQueueDao

    abstract fun sourceAccountDao(): SourceAccountDao

    abstract fun remoteSnapshotDao(): RemoteSnapshotDao

    abstract fun artistProfileCacheDao(): ArtistProfileCacheDao

    abstract fun listeningEventDao(): ListeningEventDao

    abstract fun trackTagDao(): TrackTagDao

    abstract fun stashMixRecipeDao(): StashMixRecipeDao

    abstract fun discoveryQueueDao(): DiscoveryQueueDao

    abstract fun trackBlocklistDao(): TrackBlocklistDao

    abstract fun trackSkipEventDao(): TrackSkipEventDao

    abstract fun lyricsDao(): LyricsDao

    abstract fun lastFmCacheDao(): LastFmCacheDao

    abstract fun spotifyResolutionDao(): SpotifyResolutionDao


    companion object {
        const val DATABASE_NAME = "stash.db"

        /** v3 → v4: add sync_enabled column to playlists table. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE playlists ADD COLUMN sync_enabled INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        /** v4 → v5: add failure_type to download_queue, match_dismissed to tracks. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE download_queue ADD COLUMN failure_type TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL("ALTER TABLE tracks ADD COLUMN match_dismissed INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v5 → v6: add rejected_video_id to download_queue for preview of closest rejected match. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE download_queue ADD COLUMN rejected_video_id TEXT DEFAULT NULL")
            }
        }

        /** v6 → v7: add artist_profile_cache table for SWR artist pages. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS artist_profile_cache (
                        artist_id TEXT NOT NULL PRIMARY KEY,
                        json TEXT NOT NULL,
                        fetched_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v7 → v8: add listening_events table for local play history +
         * optional Last.fm scrobbling. ForeignKey(tracks.id) cascades so
         * rows are cleaned up if a track is deleted.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS listening_events (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        track_id INTEGER NOT NULL,
                        started_at INTEGER NOT NULL,
                        scrobbled INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(track_id) REFERENCES tracks(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_listening_events_track_id ON listening_events(track_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_listening_events_started_at ON listening_events(started_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_listening_events_scrobbled ON listening_events(scrobbled)")
            }
        }

        /**
         * v8 → v9: add match_flagged column to tracks so users can mark a
         * wrongly-matched track from Now Playing and have it surface in the
         * Failed Matches screen for re-match. Defaults to 0 so no existing
         * row is retroactively flagged.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE tracks ADD COLUMN match_flagged INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * v9 → v10: add is_blacklisted column to tracks for the user-level
         * "never download again" list. Defaults to 0. DiffWorker consults
         * this during sync so blacklisted identities survive across sync
         * runs without the track ever being re-queued.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE tracks ADD COLUMN is_blacklisted INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * v10 → v11: three new tables backing the Stash Mixes feature —
         * track_tags (Last.fm tag enrichment), stash_mix_recipes
         * (declarative mix definitions), discovery_queue (Last.fm-sourced
         * candidate tracks waiting to be downloaded into a mix).
         *
         * Schema mirrors the Room annotations on [TrackTagEntity],
         * [StashMixRecipeEntity], and [DiscoveryQueueEntity] — if those
         * change, this migration + the schema JSON both need updating.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // track_tags — composite PK (track_id, tag), FK cascade on track delete.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS track_tags (
                        track_id INTEGER NOT NULL,
                        tag TEXT NOT NULL,
                        weight REAL NOT NULL,
                        source TEXT NOT NULL,
                        fetched_at INTEGER NOT NULL,
                        PRIMARY KEY (track_id, tag),
                        FOREIGN KEY (track_id) REFERENCES tracks(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_track_tags_tag ON track_tags(tag)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_track_tags_track_id ON track_tags(track_id)")

                // stash_mix_recipes — the recipe table, FK to playlists set null on delete.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS stash_mix_recipes (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        description TEXT,
                        include_tags_csv TEXT NOT NULL DEFAULT '',
                        exclude_tags_csv TEXT NOT NULL DEFAULT '',
                        era_start_year INTEGER,
                        era_end_year INTEGER,
                        affinity_bias REAL NOT NULL DEFAULT 0,
                        discovery_ratio REAL NOT NULL DEFAULT 0,
                        freshness_window_days INTEGER NOT NULL DEFAULT 0,
                        target_length INTEGER NOT NULL DEFAULT 50,
                        is_builtin INTEGER NOT NULL DEFAULT 0,
                        is_active INTEGER NOT NULL DEFAULT 1,
                        playlist_id INTEGER,
                        created_at INTEGER NOT NULL,
                        last_refreshed_at INTEGER,
                        FOREIGN KEY (playlist_id) REFERENCES playlists(id) ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_stash_mix_recipes_playlist_id ON stash_mix_recipes(playlist_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_stash_mix_recipes_is_active ON stash_mix_recipes(is_active)")

                // discovery_queue — FK to recipe cascades on delete.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS discovery_queue (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        recipe_id INTEGER NOT NULL,
                        artist TEXT NOT NULL,
                        title TEXT NOT NULL,
                        seed_artist TEXT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'PENDING',
                        track_id INTEGER,
                        queued_at INTEGER NOT NULL,
                        completed_at INTEGER,
                        error_message TEXT,
                        FOREIGN KEY (recipe_id) REFERENCES stash_mix_recipes(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_discovery_queue_status ON discovery_queue(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_discovery_queue_recipe_id ON discovery_queue(recipe_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_discovery_queue_queued_at ON discovery_queue(queued_at)")
            }
        }

        /**
         * v11 → v12: add `isrc` + `explicit` columns to both `tracks` and
         * `remote_track_snapshots`. Both nullable because neither field is
         * available for YouTube-sourced tracks. `isrc` holds Spotify's
         * per-master unique identifier; `explicit` is the parental-advisory
         * flag. The matcher uses them to distinguish clean vs. explicit
         * masters and to pin a specific recording when an ISRC is known.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN isrc TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE tracks ADD COLUMN explicit INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE remote_track_snapshots ADD COLUMN isrc TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE remote_track_snapshots ADD COLUMN explicit INTEGER DEFAULT NULL")
            }
        }

        /**
         * v12 → v13: add `date_added` to playlists. Drives the Library
         * tab's "Recently added" sort order (issue #13 on GitHub). Unlike
         * `last_synced` — which resets every sync run and caused
         * playlists to reshuffle in the list — `date_added` is stable:
         * set once when the playlist first enters the library.
         *
         * Existing rows are backfilled from `last_synced` when available
         * (best approximation of "when the user first saw this playlist"
         * for Spotify-imported mixes), otherwise from the migration's
         * wall clock.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlists ADD COLUMN date_added INTEGER NOT NULL DEFAULT 0")
                // last_synced is an Instant stored as epoch-millis INTEGER;
                // NULL when never synced. Use it as the best backfill
                // proxy, fall back to the migration's wall clock in ms.
                db.execSQL(
                    """
                    UPDATE playlists
                    SET date_added = COALESCE(last_synced, strftime('%s','now') * 1000)
                    WHERE date_added = 0
                    """.trimIndent()
                )
            }
        }

        /**
         * v14 — persists the per-track InnerTube `musicVideoType` (ATV /
         * OMV / UGC / OFFICIAL_SOURCE_MUSIC / PODCAST_EPISODE) so the
         * Fix-Wrong-Versions backfill can skip InnerTube verification
         * calls on tracks it's already typed. Nullable — null means
         * "never verified" and the worker will verify on next scan. The
         * column is TEXT (the enum's .name), not an INTEGER code, so
         * migrations reading the DB don't need to know the enum ordering.
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN music_video_type TEXT")
            }
        }

        /**
         * v14 → v15: add YouTube history sync columns.
         * `yt_scrobbled` on listening_events tracks whether a play event
         * was already sent to YouTube Music history. `yt_canonical_video_id`
         * on tracks holds the YouTube Music video ID for canonical matching.
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE listening_events ADD COLUMN yt_scrobbled INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_listening_events_yt_scrobbled " +
                        "ON listening_events(yt_scrobbled)"
                )
                db.execSQL(
                    "ALTER TABLE tracks ADD COLUMN yt_canonical_video_id TEXT"
                )
            }
        }

        /**
         * v15 → v16: add `partial` + `expected_count` columns to
         * remote_playlist_snapshots. Surfaces YouTube continuation-pagination
         * partial results from the new [YTMusicApiClient] paged-result types.
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE remote_playlist_snapshots ADD COLUMN partial INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE remote_playlist_snapshots ADD COLUMN expected_count INTEGER DEFAULT NULL"
                )
            }
        }

        /**
         * v16 → v17: per-track audio quality metadata.
         *
         * Two new nullable columns surface the bit-depth + sample-rate
         * read from each downloaded file — needed for the v0.9.11
         * Library FLAC badge upgrade ("FLAC 24/96") and the Now Playing
         * quality line ("FLAC · 24-bit/96.0 kHz · 4233 kbps").
         *
         * Both columns are nullable. NULL means "unknown": legacy rows
         * pre-backfill, lossy codecs with no meaningful bit-depth, or
         * files whose container won't expose the values via
         * `MediaExtractor`/STREAMINFO. A v0.9.11 first-launch worker
         * (`QualityInfoBackfillWorker`) sweeps lossless rows whose
         * columns are still NULL.
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN bits_per_sample INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE tracks ADD COLUMN sample_rate_hz INTEGER DEFAULT NULL")
            }
        }

        /**
         * v17 → v18: add Like-state timestamps to tracks (spotify /
         * ytmusic / stash) and `completed_at` to listening_events.
         * All nullable INTEGER, default NULL — same shape as v0.9.11's
         * MIGRATION_16_17 quality-info columns. v0.9.13 ships the
         * heart button + auto-save scrobbler that populate these.
         */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN spotify_saved_at INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE tracks ADD COLUMN ytmusic_saved_at INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE tracks ADD COLUMN stash_liked_at INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE listening_events ADD COLUMN completed_at INTEGER DEFAULT NULL")
            }
        }

        /**
         * v18 -> v19: introduce identity-keyed `track_blocklist` table and
         * seed it from existing `tracks.is_blacklisted = 1` rows. The
         * `is_blacklisted` column is NOT yet dropped (column drop happens
         * in v19->v20 once all read paths have been removed in Phase 3) —
         * this migration is purely additive to keep rollback-on-failure safe.
         *
         * v18 schema confirmed via core/data/schemas/.../18.json:
         *   - canonical_artist TEXT NOT NULL
         *   - canonical_title  TEXT NOT NULL
         *   - spotify_uri      TEXT (nullable)
         *   - youtube_id       TEXT (nullable)
         *   - date_added       INTEGER NOT NULL
         *   - is_blacklisted   INTEGER NOT NULL DEFAULT 0
         *
         * The seed query uses raw SQL string concat over canonical_artist +
         * canonical_title to derive the blocklist key. The runtime path uses
         * BlocklistKey.fromStoredCanonicals() which produces an identical
         * shape, ensuring the integrity worker can match these rows later.
         */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS track_blocklist (
                        canonical_key TEXT NOT NULL PRIMARY KEY,
                        artist        TEXT NOT NULL,
                        title         TEXT NOT NULL,
                        spotify_uri   TEXT,
                        youtube_id    TEXT,
                        blocked_at    INTEGER NOT NULL,
                        blocked_from  TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_track_blocklist_spotify_uri ON track_blocklist(spotify_uri)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_track_blocklist_youtube_id ON track_blocklist(youtube_id)"
                )

                db.execSQL(
                    """
                    INSERT OR IGNORE INTO track_blocklist
                        (canonical_key, artist, title, spotify_uri, youtube_id, blocked_at, blocked_from)
                    SELECT
                        canonical_artist || '|' || canonical_title AS canonical_key,
                        artist,
                        title,
                        spotify_uri,
                        youtube_id,
                        CASE WHEN date_added > 0 THEN date_added ELSE strftime('%s','now') * 1000 END,
                        'MIGRATION_V19'
                    FROM tracks
                    WHERE is_blacklisted = 1
                    """.trimIndent()
                )
            }
        }

        /**
         * v19 -> v20: drop the now-unused `is_blacklisted` column from
         * tracks. The source of truth moved to `track_blocklist` in v19;
         * after Phase 2 wired the chokepoints and Phase 3 made
         * `BlocklistGuard.block` hard-delete the tracks row, no Kotlin
         * code reads or writes the flag anymore.
         *
         * SQLite's column drop only landed in 3.35 (Android 12+ / API 31)
         * and Room's schema validator wants exact DDL, so we use the
         * recreate-table dance. The CREATE TABLE statement below is the
         * verbatim createSql from core/data/schemas/.../20.json — DO NOT
         * hand-edit. Likewise the index list below mirrors 20.json's
         * indices array exactly.
         */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Defensively disable FK enforcement during the dance
                // (no FK targets `tracks.id` directly today, but the
                // pragma is cheap insurance against a future FK addition
                // running during the recreate).
                db.execSQL("PRAGMA foreign_keys=OFF")

                db.execSQL("ALTER TABLE tracks RENAME TO tracks_v19")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tracks` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`artist` TEXT NOT NULL, " +
                        "`album` TEXT NOT NULL, " +
                        "`duration_ms` INTEGER NOT NULL, " +
                        "`file_path` TEXT, " +
                        "`file_format` TEXT NOT NULL, " +
                        "`quality_kbps` INTEGER NOT NULL, " +
                        "`file_size_bytes` INTEGER NOT NULL, " +
                        "`source` TEXT NOT NULL, " +
                        "`spotify_uri` TEXT, " +
                        "`youtube_id` TEXT, " +
                        "`album_art_url` TEXT, " +
                        "`album_art_path` TEXT, " +
                        "`date_added` INTEGER NOT NULL, " +
                        "`last_played` INTEGER, " +
                        "`play_count` INTEGER NOT NULL, " +
                        "`is_downloaded` INTEGER NOT NULL, " +
                        "`canonical_title` TEXT NOT NULL, " +
                        "`canonical_artist` TEXT NOT NULL, " +
                        "`match_confidence` REAL NOT NULL, " +
                        "`match_dismissed` INTEGER NOT NULL, " +
                        "`match_flagged` INTEGER NOT NULL DEFAULT 0, " +
                        "`isrc` TEXT, " +
                        "`explicit` INTEGER, " +
                        "`music_video_type` TEXT, " +
                        "`yt_canonical_video_id` TEXT, " +
                        "`bits_per_sample` INTEGER, " +
                        "`sample_rate_hz` INTEGER, " +
                        "`spotify_saved_at` INTEGER, " +
                        "`ytmusic_saved_at` INTEGER, " +
                        "`stash_liked_at` INTEGER" +
                        ")"
                )

                // Copy every column EXCEPT is_blacklisted. Enumerated
                // explicitly to avoid SELECT * pulling the dropped column.
                db.execSQL(
                    """
                    INSERT INTO tracks (
                        id, title, artist, album, duration_ms, file_path, file_format,
                        quality_kbps, file_size_bytes, source, spotify_uri, youtube_id,
                        album_art_url, album_art_path, date_added, last_played, play_count,
                        is_downloaded, canonical_title, canonical_artist, match_confidence,
                        match_dismissed, match_flagged, isrc, explicit, music_video_type,
                        yt_canonical_video_id, bits_per_sample, sample_rate_hz,
                        spotify_saved_at, ytmusic_saved_at, stash_liked_at
                    )
                    SELECT
                        id, title, artist, album, duration_ms, file_path, file_format,
                        quality_kbps, file_size_bytes, source, spotify_uri, youtube_id,
                        album_art_url, album_art_path, date_added, last_played, play_count,
                        is_downloaded, canonical_title, canonical_artist, match_confidence,
                        match_dismissed, match_flagged, isrc, explicit, music_video_type,
                        yt_canonical_video_id, bits_per_sample, sample_rate_hz,
                        spotify_saved_at, ytmusic_saved_at, stash_liked_at
                    FROM tracks_v19
                    """.trimIndent()
                )

                db.execSQL("DROP TABLE tracks_v19")

                // Re-create every index from 20.json#indices. Names must
                // match exactly or runMigrationsAndValidate will fail.
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tracks_spotify_uri` ON `tracks` (`spotify_uri`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tracks_youtube_id` ON `tracks` (`youtube_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_artist` ON `tracks` (`artist`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_album` ON `tracks` (`album`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_date_added` ON `tracks` (`date_added`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_last_played` ON `tracks` (`last_played`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_play_count` ON `tracks` (`play_count`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_title_artist` ON `tracks` (`title`, `artist`)")

                db.execSQL("PRAGMA foreign_keys=ON")
            }
        }

        /**
         * v20 -> v21: Discover Mix tuning (v0.9.16).
         *
         * Purely additive — no recreate-table dance:
         * 1. Four new track columns for Last.fm enrichment (mbid,
         *    lastfm_user_playcount, lastfm_listeners, lastfm_user_loved).
         *    Populated by TrackInfoEnrichmentWorker; nullable so
         *    pre-enrichment rows stay valid.
         * 2. seed_strategy on stash_mix_recipes — selects the
         *    discovery seed source (ARTIST_SIMILAR / TAG_GRAPH /
         *    TRACK_SIMILAR / NONE). Defaults to ARTIST_SIMILAR which
         *    matches pre-v0.9.16 behavior so existing recipes are
         *    behavior-preserving.
         * 3. New track_skip_events table — captures skip events
         *    separately from listening_events to preserve the
         *    "every listening_event is a real listen" invariant
         *    that the auto-save scrobbler depends on.
         */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Track-level columns for Last.fm enrichment.
                db.execSQL("ALTER TABLE tracks ADD COLUMN mbid TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE tracks ADD COLUMN lastfm_user_playcount INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE tracks ADD COLUMN lastfm_listeners INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE tracks ADD COLUMN lastfm_user_loved INTEGER NOT NULL DEFAULT 0")

                // 2. seed_strategy on stash_mix_recipes.
                db.execSQL(
                    "ALTER TABLE stash_mix_recipes ADD COLUMN seed_strategy TEXT NOT NULL DEFAULT 'ARTIST_SIMILAR'"
                )

                // 3. New track_skip_events table.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS track_skip_events (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        track_id    INTEGER NOT NULL,
                        skipped_at  INTEGER NOT NULL,
                        position_ms INTEGER NOT NULL,
                        FOREIGN KEY(track_id) REFERENCES tracks(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_track_skip_events_track_id ON track_skip_events(track_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_track_skip_events_skipped_at ON track_skip_events(skipped_at)")
            }
        }

        /**
         * v21 -> v22 (v0.9.17): introduces DownloadStatus.WAITING_FOR_LOSSLESS.
         *
         * The `download_queue.status` column is TEXT storing the enum
         * `.name`, so the new value parses without an ALTER TABLE — no
         * DDL change is required. This migration is intentionally a
         * no-op on disk; its only job is to advance the schema version
         * so Room is happy that the new enum vocabulary is "expected".
         */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // No-op DDL: TEXT columns accept new enum names natively.
            }
        }

        /**
         * v0.9.23 — track which playlist_tracks rows were added by the user
         * (vs. by a sync run). Drives REFRESH-mode survival so a user's
         * manual additions to an imported Spotify/YT Music playlist persist
         * across re-syncs. See issue #42.
         *
         * Default 0 (FALSE) on existing rows is correct: every pre-fix row
         * was either sync-added (CUSTOM playlists never go through REFRESH
         * so the flag is moot for them) or added via the existing
         * MusicRepositoryImpl.addTrackToPlaylist path which only targeted
         * CUSTOM playlists — those don't hit clearPlaylistTracks anyway.
         */
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE playlist_tracks " +
                        "ADD COLUMN locally_added INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * v23 → v24: per-track loudness metadata for BS.1770 normalization.
         *
         * Three nullable columns: `loudness_lufs` (integrated LUFS, null = not
         * measured, NaN = measurement attempted-and-failed sentinel),
         * `true_peak_dbfs` (sample-peak in dBFS, negative),
         * `loudness_measured_at` (epoch-ms timestamp of the measurement
         * attempt; usable for stale-measurement detection if the algorithm
         * ever changes and for the weekly NaN-resurrection query).
         */
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN loudness_lufs REAL")
                db.execSQL("ALTER TABLE tracks ADD COLUMN true_peak_dbfs REAL")
                db.execSQL("ALTER TABLE tracks ADD COLUMN loudness_measured_at INTEGER")
            }
        }

        /**
         * v24 → v25: per-track `album_artist` so the Library Albums query
         * can group by (album, album_artist) instead of album alone. Without
         * this, two distinct albums with the same title — e.g. "Singles" by
         * Usher and "Singles" by Drake — collide into one card with mixed
         * track lists and bleeding cover art. Multi-artist collab albums
         * (Drake & 21 Savage's "Her Loss") still group cleanly because all
         * tracks share the same album_artist string even though per-track
         * artist credits vary.
         *
         * NOT NULL default '' so existing rows survive the ALTER. The
         * v0.9.26 startup backfill in [com.stash.app.StashApplication]
         * fills the column from the file's path-encoded artist folder.
         */
        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN album_artist TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v25 → v26: per-track streamability metadata.
         *
         * `is_streamable` is the cached result of an `AvailabilityCheckWorker`
         * lookup against Kennyy's Qobuz proxy. `is_streamable_checked_at` is the
         * timestamp of that lookup (NULL = never checked, so the worker can drain
         * the un-checked rows on first run).
         *
         * Both columns are additive; existing rows survive untouched with their
         * default values, and the `AvailabilityCheckWorker` fills them in over
         * the next few minutes after first launch on v0.9.27.
         */
        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN is_streamable INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tracks ADD COLUMN is_streamable_checked_at INTEGER")
            }
        }

        /**
         * v26 → v27 (v0.9.35): per-track `metadata_embedded_at` for the
         * tag + art embedding backfill. Nullable INTEGER, no default —
         * NULL is the "needs tagging" sentinel; a non-null non-zero
         * value is the success stamp; 0L is the irrecoverable-failure
         * stamp (file missing, ffmpeg error, SAF row). The
         * MetadataBackfillWorker queries `metadata_embedded_at IS NULL`
         * so both success and failure stamps terminate the work item.
         */
        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN metadata_embedded_at INTEGER")
            }
        }

        /**
         * v27 → v28 (v0.9.36): per-track `lyrics_fetched_at` for the
         * lyrics-fetch backfill (mirror of v0.9.35's
         * `metadata_embedded_at` sentinel semantics) plus the new
         * `lyrics` table that stores the fetched payload keyed by
         * track id with FK cascade on delete.
         *
         * Sentinel semantics on `tracks.lyrics_fetched_at`: NULL =
         * never tried; 0L = a fetch tried and produced no hit (LRCLIB
         * miss + YT-Music fallback miss); non-null non-zero = success
         * epoch-millis (a `lyrics` row exists for this track). NULL means
         * the on-open priority fetch (or a re-download) may still retry.
         */
        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN lyrics_fetched_at INTEGER")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS lyrics (
                      track_id INTEGER NOT NULL PRIMARY KEY,
                      plain_text TEXT,
                      synced_lrc TEXT,
                      instrumental INTEGER NOT NULL DEFAULT 0,
                      language TEXT,
                      source TEXT NOT NULL,
                      source_lyrics_id TEXT,
                      fetched_at INTEGER NOT NULL,
                      FOREIGN KEY(track_id) REFERENCES tracks(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_lyrics_track_id ON lyrics(track_id)")
            }
        }

        /**
         * v28 → v29 (v0.9.38 / library-health-phase1): rewrite every
         * legacy `download_queue.failure_type = 'DOWNLOAD_ERROR'` row
         * to `'UNKNOWN'`.
         *
         * Phase 1 of the library-health epic deletes the `DOWNLOAD_ERROR`
         * constant from [com.stash.core.model.download.DownloadFailureType]
         * in favour of a richer failure-type vocabulary
         * (UNKNOWN + classifier-emitted variants). Without this migration
         * the converter's `valueOf(it)` call in
         * [com.stash.core.data.db.converter.Converters.fromFailureType]
         * would crash with `IllegalArgumentException` the first time Room
         * read a legacy `'DOWNLOAD_ERROR'` row. Room guarantees migrations
         * run before any DAO read on the upgraded schema, so once this
         * migration ships the converter only ever sees valid enum names.
         *
         * UPDATE-in-place is safe because `failure_type` is a TEXT column
         * (storing the enum `.name`), so swapping one literal for another
         * needs no DDL.
         */
        val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE download_queue SET failure_type = 'UNKNOWN' WHERE failure_type = 'DOWNLOAD_ERROR'"
                )
            }
        }

        val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stash_mix_recipes ADD COLUMN mood_keys_csv TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE stash_mix_recipes ADD COLUMN tag_sample_depth INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v30 → v31: add lastfm_response_cache for generic Last.fm lookups. */
        val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS lastfm_response_cache (
                        cache_key TEXT NOT NULL PRIMARY KEY,
                        json TEXT NOT NULL,
                        fetched_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v31 → v32: add the `spotify_resolution` side-table that caches the
         * outcome of resolving a local track to a Spotify URI (positive /
         * negative / transient), so the antra Spotify-URI resolver doesn't
         * re-search Spotify on every play. Purely additive — keyed by trackId.
         */
        val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS spotify_resolution (
                        trackId INTEGER NOT NULL PRIMARY KEY,
                        status TEXT NOT NULL,
                        spotifyUri TEXT,
                        matchedIsrc TEXT,
                        titleSim REAL,
                        durDeltaSec INTEGER,
                        resolvedAtMs INTEGER NOT NULL,
                        expiresAtMs INTEGER NOT NULL,
                        attempts INTEGER NOT NULL DEFAULT 1
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v32 → v33: add nullable `streaming_mode` to sync_history so each
         * receipt can show whether the run streamed (Online) or downloaded
         * (Offline). NULL on pre-migration rows — the UI suppresses the
         * label for those since their mode is unknown.
         */
        val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_history ADD COLUMN streaming_mode INTEGER DEFAULT NULL")
            }
        }
    }
}
