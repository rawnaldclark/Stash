package com.stash.core.media.di

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.stash.core.media.PlayerRepository
import com.stash.core.media.PlayerRepositoryImpl
import com.stash.core.media.equalizer.EqStore
import com.stash.core.media.equalizer.LegacyEqualizerStore
import com.stash.core.media.equalizer.LegacyEqualizerStoreImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// File-scope extension for the new EqStore DataStore (name differs from the
// legacy "equalizer_prefs" store so the two DataStore instances never collide).
private val Context.eqDataStore by preferencesDataStore(name = "eq_state_v1")

/**
 * Hilt module that provides the [PlayerRepository] binding for the app.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MediaModule {

    @Binds
    @Singleton
    abstract fun bindPlayerRepository(impl: PlayerRepositoryImpl): PlayerRepository

    // ------------------------------------------------------------------
    // EQ rebuild — Task 11 (additive only; legacy EqualizerManager untouched)
    // ------------------------------------------------------------------

    @Binds
    @Singleton
    abstract fun bindLegacyEqualizerStore(impl: LegacyEqualizerStoreImpl): LegacyEqualizerStore

    companion object {

        @Provides
        @Singleton
        fun provideEqStore(
            @ApplicationContext context: Context,
        ): EqStore = EqStore(context.eqDataStore)
    }
}
