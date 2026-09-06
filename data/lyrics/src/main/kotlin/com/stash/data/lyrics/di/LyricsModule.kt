package com.stash.data.lyrics.di

import com.stash.core.common.Clock
import com.stash.core.common.SystemClock
import com.stash.data.lyrics.source.InnerTubeLyricsGateway
import com.stash.data.lyrics.source.InnerTubeLyricsGatewayImpl
import com.stash.data.lyrics.source.KugouLyricsSource
import com.stash.data.lyrics.source.LrclibLyricsSource
import com.stash.data.lyrics.source.LyricsSource
import com.stash.data.lyrics.source.YtMusicLyricsSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import okhttp3.OkHttpClient

/**
 * Qualifier for the LRCLIB base URL [String] consumed by
 * [com.stash.data.lyrics.source.LrclibLyricsSource]. A qualifier is
 * required so Dagger can disambiguate from other module-level
 * `@Provides String` bindings in the SingletonComponent graph.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LrclibBaseUrl

/**
 * Hilt wiring for the `:data:lyrics` module.
 *
 * Three responsibilities:
 * 1. Binds the production [InnerTubeLyricsGateway] implementation.
 * 2. Provides an ordered `List<LyricsSource>` (LRCLIB, then KuGou, then InnerTube)
 *    consumed by [com.stash.data.lyrics.LyricsRepository]. Ordering is
 *    semantic — LRCLIB wins because it can return synced LRC, InnerTube
 *    is plain-text fallback — so a multibinding `Set` is deliberately
 *    NOT used (sets do not preserve insertion order).
 * 3. Binds [Clock] -> [SystemClock] for the repository's epoch-millis stamps.
 *    Lives here because `:core:common` is currently Hilt-free; if other
 *    modules later need [Clock], lift this binding to a shared module.
 *
 * v0.9.36 / Task 7: [com.stash.data.lyrics.sidecar.LyricsSidecarWriter] is
 * a `@Singleton`-annotated concrete class with an `@Inject` constructor,
 * so Hilt provides it directly without an explicit @Binds. The T6
 * placeholder `NoOpLyricsSidecarWriter` was deleted alongside the
 * interface — the writer now exists in its real form and there is
 * nothing to swap.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class LyricsModule {

    @Binds
    @Singleton
    abstract fun bindInnerTubeLyricsGateway(
        impl: InnerTubeLyricsGatewayImpl,
    ): InnerTubeLyricsGateway

    companion object {

        /**
         * Ordered source chain consumed by `LyricsRepository`. The order
         * defines fallback priority: LRCLIB first (can return synced LRC),
         * InnerTube second (plain text only).
         */
        @Provides
        @Singleton
        fun provideLyricsSources(
            lrclib: LrclibLyricsSource,
            kugou: KugouLyricsSource,
            ytmusic: YtMusicLyricsSource,
        ): List<@JvmSuppressWildcards LyricsSource> = listOf(lrclib, kugou, ytmusic)

        /** KuGou: synced LRC for most of what LRCLIB misses; between LRCLIB and the plain-text fallback. */
        @Provides
        @Singleton
        fun provideKugouLyricsSource(okHttpClient: OkHttpClient): KugouLyricsSource = KugouLyricsSource(okHttpClient)

        @Provides
        @Singleton
        fun provideClock(): Clock = SystemClock()

        /**
         * Base URL for the public LRCLIB API. Defined here (rather than
         * baked into [LrclibLyricsSource] as a constructor default) so
         * Hilt can satisfy the SingletonComponent String binding without
         * tripping the "@Inject constructor String" missing-binding
         * error that surfaces when the lyrics graph is materialised by
         * the app's `:app:hiltJavaCompileDebug` task.
         *
         * Tests construct [LrclibLyricsSource] directly with a
         * MockWebServer URL — the constructor default is preserved as a
         * fallback so the test wiring doesn't change.
         */
        @Provides
        @Singleton
        @LrclibBaseUrl
        fun provideLrclibBaseUrl(): String = LrclibLyricsSource.DEFAULT_BASE_URL
    }
}
