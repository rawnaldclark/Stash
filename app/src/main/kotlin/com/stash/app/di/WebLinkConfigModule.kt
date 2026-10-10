package com.stash.app.di

import com.stash.app.BuildConfig
import com.stash.core.data.weblink.WebLinkConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Link Stash on the web: the sync Worker's address and the build flag, from BuildConfig (release: sync.stashfm.app, off until
 * the feature ships; debug: local.properties `sync.baseUrl`, on). `core:data` only sees [WebLinkConfig].
 */
@Module
@InstallIn(SingletonComponent::class)
object WebLinkConfigModule {
    @Provides
    @Singleton
    fun provideWebLinkConfig(): WebLinkConfig = WebLinkConfig(BuildConfig.SYNC_BASE_URL, BuildConfig.WEB_LINK_ENABLED)
}
