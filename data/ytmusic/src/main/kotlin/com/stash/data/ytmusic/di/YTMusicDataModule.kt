package com.stash.data.ytmusic.di

import com.stash.core.auth.TokenManager
import com.stash.core.auth.youtube.YouTubeCookieHelper
import com.stash.data.ytmusic.InnerTubeClient
import com.stash.data.ytmusic.potoken.BotGuardPoTokenMinter
import com.stash.data.ytmusic.potoken.PoTokenMinter
import com.stash.data.ytmusic.YTMusicApiClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * Hilt module that provides YouTube Music data-layer dependencies.
 *
 * Both the low-level [InnerTubeClient] and the high-level [YTMusicApiClient]
 * are scoped as singletons to share a single OkHttp connection pool.
 */
@Module
@InstallIn(SingletonComponent::class)
object YTMusicDataModule {

    @Provides
    @Singleton
    fun provideInnerTubeClient(
        okHttpClient: OkHttpClient,
        tokenManager: TokenManager,
        cookieHelper: YouTubeCookieHelper,
        poTokenMinter: PoTokenMinter,
    ): InnerTubeClient = InnerTubeClient(okHttpClient, tokenManager, cookieHelper, poTokenMinter)

    /** BotGuard in a headless WebView; every failure is a null token, never an error. */
    @Provides
    @Singleton
    fun providePoTokenMinter(impl: BotGuardPoTokenMinter): PoTokenMinter = impl

    @Provides
    @Singleton
    fun provideYTMusicApiClient(
        innerTubeClient: InnerTubeClient,
    ): YTMusicApiClient = YTMusicApiClient(innerTubeClient)
}
