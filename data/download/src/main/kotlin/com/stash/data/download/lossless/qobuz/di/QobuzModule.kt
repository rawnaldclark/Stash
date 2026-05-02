package com.stash.data.download.lossless.qobuz.di

import com.stash.data.download.lossless.LosslessSource
import com.stash.data.download.lossless.qobuz.QobuzSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Hilt wiring for the Qobuz-via-squid.wtf lossless source.
 *
 * Binds [QobuzSource] into the `Set<LosslessSource>` multibinding so
 * [com.stash.data.download.lossless.LosslessSourceRegistry] picks it
 * up alongside any other registered sources.
 *
 * No credentials wiring — squid.wtf is a public proxy; the operator
 * holds the upstream Qobuz subscription and the user supplies nothing.
 * If we ever add a "BYO Qobuz subscription" power-user mode, that
 * would be a *separate* `LosslessSource` (id `"qobuz"`, registered
 * via its own module) rather than a configuration knob on this one.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class QobuzModule {

    @Binds
    @IntoSet
    abstract fun bindQobuzAsLosslessSource(impl: QobuzSource): LosslessSource
}
