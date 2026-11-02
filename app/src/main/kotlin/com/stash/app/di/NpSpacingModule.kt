package com.stash.app.di

import com.stash.app.BuildConfig
import com.stash.feature.nowplaying.ui.NpSpacingOverrides
import com.stash.feature.nowplaying.ui.NpSpacingSource
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Now Playing's spacing: DEBUG BUILDS ONLY read the live overrides the browser tuner sets through
 * the adb-only receiver (app/src/debug DebugNpSpacing). A release build binds the production values
 * ([NpSpacingSource.Production]) and never creates the overrides' store, as LastFmRecapDebugModule
 * does for its test user.
 */
@Module
@InstallIn(SingletonComponent::class)
object NpSpacingModule {

    @Provides
    @Singleton
    fun provideNpSpacingSource(overrides: Lazy<NpSpacingOverrides>): NpSpacingSource =
        NpSpacingSource.select(BuildConfig.DEBUG) { overrides.get() }
}
