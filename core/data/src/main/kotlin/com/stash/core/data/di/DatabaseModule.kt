package com.stash.core.data.di

import android.content.Context
import androidx.room.Room
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.dao.ArtistProfileCacheDao
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.RemoteSnapshotDao
import com.stash.core.data.db.dao.SourceAccountDao
import com.stash.core.data.db.dao.SyncHistoryDao
import com.stash.core.data.db.dao.TrackDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing the Room [StashDatabase] singleton and all DAO
 * instances derived from it.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): StashDatabase {
        return Room.databaseBuilder(
            context,
            StashDatabase::class.java,
            StashDatabase.DATABASE_NAME,
        )
            .addMigrations(
                StashDatabase.MIGRATION_3_4,
                StashDatabase.MIGRATION_4_5,
                StashDatabase.MIGRATION_5_6,
                StashDatabase.MIGRATION_6_7,
                StashDatabase.MIGRATION_7_8,
                StashDatabase.MIGRATION_8_9,
            )
            // No fallbackToDestructiveMigration() — if a migration is missing,
            // the app will crash on startup instead of silently wiping the
            // user's entire library. This forces us to write proper migrations
            // for every schema change. The crash is preferable to data loss.
            .build()
    }

    @Provides
    fun provideTrackDao(db: StashDatabase): TrackDao = db.trackDao()

    @Provides
    fun providePlaylistDao(db: StashDatabase): PlaylistDao = db.playlistDao()

    @Provides
    fun provideSyncHistoryDao(db: StashDatabase): SyncHistoryDao = db.syncHistoryDao()

    @Provides
    fun provideDownloadQueueDao(db: StashDatabase): DownloadQueueDao = db.downloadQueueDao()

    @Provides
    fun provideSourceAccountDao(db: StashDatabase): SourceAccountDao = db.sourceAccountDao()

    @Provides
    fun provideRemoteSnapshotDao(db: StashDatabase): RemoteSnapshotDao = db.remoteSnapshotDao()

    @Provides
    fun provideArtistProfileCacheDao(db: StashDatabase): ArtistProfileCacheDao =
        db.artistProfileCacheDao()

    @Provides
    fun provideListeningEventDao(db: StashDatabase): com.stash.core.data.db.dao.ListeningEventDao =
        db.listeningEventDao()
}
