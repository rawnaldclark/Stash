package com.stash.core.data.di

import com.stash.core.data.prefs.ThemePreference
import com.stash.core.data.prefs.ThemePreferencesManager
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.repository.MusicRepositoryImpl
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
}
