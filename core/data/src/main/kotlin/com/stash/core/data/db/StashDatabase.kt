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
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.RemoteSnapshotDao
import com.stash.core.data.db.dao.SourceAccountDao
import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.db.dao.SyncHistoryDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.dao.TrackTagDao
import com.stash.core.data.db.entity.ArtistProfileCacheEntity
import com.stash.core.data.db.entity.DiscoveryQueueEntity
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.ListeningEventEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.RemotePlaylistSnapshotEntity
import com.stash.core.data.db.entity.RemoteTrackSnapshotEntity
import com.stash.core.data.db.entity.SourceAccountEntity
import com.stash.core.data.db.entity.StashMixRecipeEntity
import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.db.entity.TrackFts
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
    ],
    version = 11,
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
    }
}
