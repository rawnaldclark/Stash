package com.stash.app.cast

import com.stash.core.media.cast.CastDevices
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Binds core:media's [CastDevices] contract to the Google Cast SDK implementation. */
@Module
@InstallIn(SingletonComponent::class)
abstract class CastModule {
    @Binds
    abstract fun bindCastDevices(impl: GoogleCastDevices): CastDevices
}
