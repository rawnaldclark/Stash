package com.stash.app.di

import com.stash.app.BuildConfig
import com.stash.data.cloud.CloudConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hands the cloud providers' public-client ids from BuildConfig to `data:cloud`, like
 * [LastFmCredentialsModule] does for Last.fm. An empty id hides that provider; both empty (a
 * plain checkout, CI) hides cloud storage entirely. Google Drive is a plain on/off flag
 * (`GOOGLE_DRIVE_ENABLED`): its Android OAuth client is matched by package + signing key.
 */
@Module
@InstallIn(SingletonComponent::class)
object CloudConfigModule {
    @Provides
    @Singleton
    fun provideCloudConfig(): CloudConfig = CloudConfig(
        dropboxAppKey = BuildConfig.DROPBOX_APP_KEY,
        oneDriveClientId = BuildConfig.ONEDRIVE_CLIENT_ID,
        applicationId = BuildConfig.APPLICATION_ID,
        googleDriveEnabled = BuildConfig.GOOGLE_DRIVE_ENABLED,
    )
}
