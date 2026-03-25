package com.stash.core.data.di

import android.content.Context
import androidx.room.Room
import com.stash.core.data.db.StashDatabase
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
            .fallbackToDestructiveMigration()
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
}
