package com.stash.data.download.di

import com.stash.core.data.prefs.QualityPreference
import com.stash.core.data.sync.TrackDownloader
import com.stash.data.download.TrackDownloaderImpl
import com.stash.data.download.prefs.QualityPreferencesManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for the download layer.
 *
 * Binds abstraction interfaces defined in `:core:data` to their concrete
 * implementations in `:data:download`, preventing circular module dependencies
 * while allowing feature modules and sync workers to use the download pipeline.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DownloadModule {

    /**
     * Provides [TrackDownloaderImpl] wherever [TrackDownloader] is injected.
     */
    @Binds
    @Singleton
    abstract fun bindTrackDownloader(impl: TrackDownloaderImpl): TrackDownloader

    /**
     * Provides [QualityPreferencesManager] wherever [QualityPreference] is injected.
     */
    @Binds
    @Singleton
    abstract fun bindQualityPreference(impl: QualityPreferencesManager): QualityPreference

    /** Save to Google Drive names songs the way the downloader does ([DriveUploadNaming]). */
    @Binds
    @Singleton
    abstract fun bindUploadNaming(impl: com.stash.data.download.cloud.DriveUploadNaming): com.stash.data.cloud.upload.UploadNaming

    /** The one-time check for clips reads each FLAC's end through the file's own path or document. */
    @Binds
    abstract fun bindFlacLengthsReader(impl: com.stash.data.download.clips.FlacTailReader): com.stash.data.download.clips.FlacLengthsReader
}
