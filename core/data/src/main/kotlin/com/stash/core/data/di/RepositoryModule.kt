package com.stash.core.data.di

import com.stash.core.data.prefs.StoragePreference
import com.stash.core.data.prefs.StoragePreferencesManager
import com.stash.core.data.prefs.ThemePreference
import com.stash.core.data.prefs.ThemePreferencesManager
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.repository.MusicRepositoryImpl
import com.stash.core.data.youtube.OkHttpPingSubmitter
import com.stash.core.data.youtube.PingSubmitter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module that binds abstractions defined in `:core:data` to their
 * concrete implementations in the same module.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindMusicRepository(impl: MusicRepositoryImpl): MusicRepository

    @Binds
    @Singleton
    abstract fun bindThemePreference(impl: ThemePreferencesManager): ThemePreference

    @Binds
    @Singleton
    abstract fun bindStoragePreference(impl: StoragePreferencesManager): StoragePreference

    @Binds
    @Singleton
    abstract fun bindPingSubmitter(impl: OkHttpPingSubmitter): PingSubmitter
}
