package com.stash.data.spotify.di

import com.stash.core.auth.spotify.SpotifyAccounts
import com.stash.core.auth.spotify.SpotifyAuthManager
import com.stash.data.spotify.SpotifyApiClient
import com.stash.data.spotify.SpotifyLibrary
import dagger.Binds
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
        spotifyAccounts: SpotifyAccounts,
        spotifyAuthManager: SpotifyAuthManager,
    ): SpotifyApiClient = SpotifyApiClient(okHttpClient, spotifyAccounts, spotifyAuthManager)
}

/**
 * Binds [SpotifyLibrary] to the [SpotifyApiClient] singleton above: every caller of the Spotify
 * library codes against the interface (add-ons plan 2026-11-08 §4.3). Phase 3 rebinds it to the
 * Spotify add-on's adapter.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SpotifyLibraryModule {

    @Binds
    abstract fun bindSpotifyLibrary(impl: SpotifyApiClient): SpotifyLibrary
}
