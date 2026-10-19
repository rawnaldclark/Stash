package com.stash.app.di

import com.stash.app.BuildConfig
import com.stash.core.data.lastfm.recap.LastFmRecapDebugUser
import com.stash.core.data.lastfm.recap.LastFmRecapDebugUserStore
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The Last.fm recap's test username override: DEBUG BUILDS ONLY. A debug build reads the value
 * the adb-only receiver (app/src/debug DebugLastFmRecapUser) wrote; a release build binds
 * [LastFmRecapDebugUser.None] and never opens that store, so the recap there follows the
 * connected Last.fm account alone.
 */
@Module
@InstallIn(SingletonComponent::class)
object LastFmRecapDebugModule {

    @Provides
    @Singleton
    fun provideLastFmRecapDebugUser(store: Lazy<LastFmRecapDebugUserStore>): LastFmRecapDebugUser =
        if (BuildConfig.DEBUG) store.get() else LastFmRecapDebugUser.None
}
