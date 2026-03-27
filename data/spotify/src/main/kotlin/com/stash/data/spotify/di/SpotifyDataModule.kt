package com.stash.data.spotify.di

import com.stash.core.auth.TokenManager
import com.stash.core.auth.spotify.SpotifyAuthManager
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
 *
 * The [SpotifyAuthManager] is injected to provide client token acquisition
 * for the GraphQL Partner API.
 */
@Module
@InstallIn(SingletonComponent::class)
object SpotifyDataModule {

    @Provides
    @Singleton
    fun provideSpotifyApiClient(
        okHttpClient: OkHttpClient,
        tokenManager: TokenManager,
        spotifyAuthManager: SpotifyAuthManager,
    ): SpotifyApiClient = SpotifyApiClient(okHttpClient, tokenManager, spotifyAuthManager)
}
