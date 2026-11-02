package com.stash.app.di

import com.stash.app.widget.GlanceNowPlayingWidgetSink
import com.stash.core.media.widget.NowPlayingWidgetSink
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** The playback service (core:media) pushes the home screen widget's state into the app's Glance widget. */
@Module
@InstallIn(SingletonComponent::class)
abstract class NowPlayingWidgetModule {
    @Binds
    abstract fun bindNowPlayingWidgetSink(impl: GlanceNowPlayingWidgetSink): NowPlayingWidgetSink
}
