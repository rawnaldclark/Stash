package com.stash.core.media.di

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.stash.core.data.diagnostics.DiagnosticsContributor
import com.stash.core.media.PlayerRepository
import com.stash.core.media.PlayerRepositoryImpl
import com.stash.core.media.diagnostics.PlaybackDiagnosticsContributor
import com.stash.core.media.equalizer.EqStore
import com.stash.core.media.equalizer.LegacyEqualizerStore
import com.stash.core.media.equalizer.LegacyEqualizerStoreImpl
import com.stash.core.media.equalizer.LoudnessStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

// File-scope extension for the new EqStore DataStore (name differs from the
// legacy "equalizer_prefs" store so the two DataStore instances never collide).
private val Context.eqDataStore by preferencesDataStore(
    name = "eq_state_v1",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

// File-scope extension for the LoudnessStore DataStore. Per-concern naming so
// the loudness JSON blob never collides with EQ state, and so a corrupt write
// during process death only resets loudness — not EQ.
private val Context.loudnessDataStore by preferencesDataStore(
    name = "loudness_state_v1",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

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

    /** The diagnostics bundle's "Playback" section: audio settings, output, recent resolves and errors. */
    @Binds
    @IntoSet
    abstract fun bindPlaybackDiagnostics(impl: PlaybackDiagnosticsContributor): DiagnosticsContributor

    companion object {

        @Provides
        @Singleton
        fun provideEqStore(
            @ApplicationContext context: Context,
        ): EqStore = EqStore(context.eqDataStore)

        @Provides
        @Singleton
        fun provideLoudnessStore(
            @ApplicationContext context: Context,
        ): LoudnessStore = LoudnessStore(context.loudnessDataStore)
    }
}
