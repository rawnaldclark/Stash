package com.stash.data.download.lossless.di

import com.stash.core.data.lossless.DownloadModeReader
import com.stash.core.data.lossless.LosslessUpgrader
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.LosslessUpgraderImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class UpgraderModule {

    @Binds
    @Singleton
    abstract fun bindLosslessUpgrader(impl: LosslessUpgraderImpl): LosslessUpgrader

    /** The download mode for `:core:data` / `:feature:*` code that can't see `:data:download`. */
    @Binds
    abstract fun bindDownloadModeReader(impl: LosslessSourcePreferences): DownloadModeReader
}
