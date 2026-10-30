package com.stash.core.auth.di

import com.stash.data.soundcloud.SoundCloudClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object SoundCloudModule {
    /** Dedicated client has no logging interceptor or credentials belonging to another service. */
    @Provides
    @Singleton
    fun provideSoundCloudClient(): SoundCloudClient = SoundCloudClient(OkHttpClient())
}
