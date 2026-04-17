package com.stash.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.stash.core.data.db.converter.Converters
import com.stash.core.data.db.dao.ArtistProfileCacheDao
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.RemoteSnapshotDao
import com.stash.core.data.db.dao.SourceAccountDao
import com.stash.core.data.db.dao.SyncHistoryDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.ArtistProfileCacheEntity
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.RemotePlaylistSnapshotEntity
import com.stash.core.data.db.entity.RemoteTrackSnapshotEntity
import com.stash.core.data.db.entity.SourceAccountEntity
import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.db.entity.TrackFts

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
    ],
    version = 7,
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
    }
}
