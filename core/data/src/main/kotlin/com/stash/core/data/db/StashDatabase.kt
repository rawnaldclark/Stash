package com.stash.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.stash.core.data.db.converter.Converters
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.SourceAccountDao
import com.stash.core.data.db.dao.SyncHistoryDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.SourceAccountEntity
import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.db.entity.TrackFts

/**
 * Central Room database for the Stash application.
 *
 * Version 1 — initial schema containing tracks, playlists, sync history,
 * download queue, source accounts, and full-text search support.
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
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class StashDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao

    abstract fun playlistDao(): PlaylistDao

    abstract fun syncHistoryDao(): SyncHistoryDao

    abstract fun downloadQueueDao(): DownloadQueueDao

    abstract fun sourceAccountDao(): SourceAccountDao

    companion object {
        const val DATABASE_NAME = "stash.db"
    }
}
