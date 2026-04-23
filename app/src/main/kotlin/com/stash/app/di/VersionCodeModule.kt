package com.stash.app.di

import com.stash.app.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the app's [BuildConfig.VERSION_CODE] as a function to the `core/data` layer.
 * This indirection allows [com.stash.core.data.youtube.YouTubeHistoryScrobbler] and
 * other components to access the version code without depending on BuildConfig directly.
 */
@Module
@InstallIn(SingletonComponent::class)
object VersionCodeModule {

    @Provides
    @Singleton
    fun provideVersionCodeProvider(): () -> Int = { BuildConfig.VERSION_CODE }
}
