package com.stash.data.spotify.di

import com.stash.core.auth.TokenManager
import com.stash.data.spotify.SpotifyApiClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * Hilt module that provides the [SpotifyApiClient] singleton.
 *
 * The client is scoped to [SingletonComponent] so that a single instance is
 * shared across the entire application, avoiding redundant OkHttp connections.
 */
@Module
@InstallIn(SingletonComponent::class)
object SpotifyDataModule {

    @Provides
    @Singleton
    fun provideSpotifyApiClient(
        okHttpClient: OkHttpClient,
        tokenManager: TokenManager,
    ): SpotifyApiClient = SpotifyApiClient(okHttpClient, tokenManager)
}
