package com.stash.app.di

import com.stash.data.download.lyrics.LyricsUpgradeTrigger
import com.stash.data.lyrics.worker.WorkManagerLyricsUpgradeTrigger
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class LyricsUpgradeTriggerModule {
    @Binds
    abstract fun bindLyricsUpgradeTrigger(impl: WorkManagerLyricsUpgradeTrigger): LyricsUpgradeTrigger
}