package com.stash.app.di

import android.content.Context
import com.stash.core.common.power.BatterySaver
import com.stash.core.common.power.SystemBatterySaver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * One [BatterySaver] for the app: Android's own Battery Saver, watched for the life of the process
 * (one receiver). `:core:common` has no Hilt, so the binding lives here, like [AppVersionModule].
 */
@Module
@InstallIn(SingletonComponent::class)
object BatterySaverModule {

    @Provides
    @Singleton
    fun provideBatterySaver(@ApplicationContext context: Context): BatterySaver = SystemBatterySaver(context)
}
