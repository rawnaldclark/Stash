package com.stash.core.data.di

import com.stash.core.data.audio.FFmpegBridge
import com.stash.core.data.audio.FFmpegBridgeImpl
import com.stash.core.data.prefs.AutoplayRadioPreference
import com.stash.core.data.prefs.AutoplayRadioPreferencesManager
import com.stash.core.data.prefs.CrossfadePreference
import com.stash.core.data.prefs.CrossfadePreferencesManager
import com.stash.core.data.prefs.HiResOutputPreference
import com.stash.core.data.prefs.HiResOutputPreferencesManager
import com.stash.core.data.prefs.RadioEnginePreference
import com.stash.core.data.prefs.RadioEnginePreferencesManager
import com.stash.core.data.prefs.StoragePreference
import com.stash.core.data.radio.LastFmRadioEngine
import com.stash.core.data.radio.RadioEngine
import com.stash.core.data.prefs.StoragePreferencesManager
import com.stash.core.data.prefs.ThemePreference
import com.stash.core.data.prefs.ThemePreferencesManager
import com.stash.core.data.prefs.TuningPreference
import com.stash.core.data.prefs.TuningPreferencesManager
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.repository.MusicRepositoryImpl
import com.stash.core.data.youtube.OkHttpPingSubmitter
import com.stash.core.data.youtube.PingSubmitter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
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
    abstract fun bindCrossfadePreference(impl: CrossfadePreferencesManager): CrossfadePreference

    @Binds
    @Singleton
    abstract fun bindHiResOutputPreference(impl: HiResOutputPreferencesManager): HiResOutputPreference

    @Binds
    @Singleton
    abstract fun bindTuningPreference(impl: TuningPreferencesManager): TuningPreference

    @Binds
    @Singleton
    abstract fun bindAutoplayRadioPreference(impl: AutoplayRadioPreferencesManager): AutoplayRadioPreference

    @Binds
    @Singleton
    abstract fun bindRadioEnginePreference(impl: RadioEnginePreferencesManager): RadioEnginePreference

    /** The Stash engine is always in the graph: it is what [RadioEngines.active] falls back to. */
    @Binds
    @IntoSet
    abstract fun bindLastFmRadioEngine(impl: LastFmRadioEngine): RadioEngine

    /**
     * Declares the radio engines as a possibly-empty set on top of the Stash engine bound
     * below, so a build that drops the module contributing [RadioEngine] (the YouTube
     * Music extension) still assembles its graph.
     */
    @Multibinds
    abstract fun radioEngines(): Set<RadioEngine>

    @Binds
    @Singleton
    abstract fun bindHeadphonePreference(impl: com.stash.core.data.prefs.HeadphonePreferencesManager): com.stash.core.data.prefs.HeadphonePreference

    @Binds
    @Singleton
    abstract fun bindRecordInMixesPreference(impl: com.stash.core.data.prefs.RecordInMixesPreferencesManager): com.stash.core.data.prefs.RecordInMixesPreference

    @Binds
    @Singleton
    abstract fun bindRecordMixTaste(impl: com.stash.core.data.record.RoomRecordMixTaste): com.stash.core.data.record.RecordMixTaste

    @Binds
    @Singleton
    abstract fun bindPingSubmitter(impl: OkHttpPingSubmitter): PingSubmitter

    @Binds
    @Singleton
    abstract fun bindFFmpegBridge(impl: FFmpegBridgeImpl): FFmpegBridge
}
