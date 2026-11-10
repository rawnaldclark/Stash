package com.stash.core.auth.di

import com.stash.core.auth.TokenManager
import com.stash.core.auth.TokenManagerImpl
import com.stash.core.auth.spotify.SpotifyAccounts
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module that wires up authentication dependencies.
 *
 * Binds the [TokenManagerImpl] concrete implementation to the [TokenManager]
 * interface so that consumers depend only on the abstraction.
 *
 * [SpotifyAccounts] is bound to the SAME singleton: `TokenManagerImpl` is `@Singleton`, so both
 * keys hand out one instance with one set of refresh locks and one auth state. Phase 3 of the
 * add-ons plan rebinds [SpotifyAccounts] to the Spotify add-on's adapter.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {

    @Binds
    @Singleton
    abstract fun bindTokenManager(impl: TokenManagerImpl): TokenManager

    @Binds
    @Singleton
    abstract fun bindSpotifyAccounts(impl: TokenManagerImpl): SpotifyAccounts
}
