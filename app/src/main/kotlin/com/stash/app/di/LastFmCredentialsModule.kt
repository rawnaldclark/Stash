package com.stash.app.di

import com.stash.app.BuildConfig
import com.stash.core.data.lastfm.LastFmCredentials
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the Last.fm API key + shared secret to the `core/data` layer
 * from the app's BuildConfig. Keeps `core/data` from needing to know
 * about BuildConfig directly — it only sees [LastFmCredentials].
 *
 * Empty strings are a valid configuration: the Settings UI checks
 * [LastFmCredentials.isConfigured] before offering the Connect button.
 */
@Module
@InstallIn(SingletonComponent::class)
object LastFmCredentialsModule {

    @Provides
    @Singleton
    fun provideLastFmCredentials(): LastFmCredentials = LastFmCredentials(
        apiKey = BuildConfig.LASTFM_API_KEY,
        apiSecret = BuildConfig.LASTFM_API_SECRET,
    )
}
