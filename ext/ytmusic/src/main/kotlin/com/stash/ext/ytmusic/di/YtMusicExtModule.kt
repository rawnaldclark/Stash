package com.stash.ext.ytmusic.di

import com.stash.core.data.radio.RadioEngine
import com.stash.ext.ytmusic.radio.YouTubeMusicRadioEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * What this module contributes: one more radio engine.
 *
 * Everything else already exists — `RadioEngines` collects engines by set multibinding and
 * `RadioEngines.active()` falls back to the Stash engine when this one is absent or has no
 * account — so the engine's presence here is the whole of the wiring.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class YtMusicExtModule {

    @Binds
    @IntoSet
    abstract fun youtubeMusicRadioEngine(impl: YouTubeMusicRadioEngine): RadioEngine
}
