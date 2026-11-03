package com.stash.data.lyrics.di

import android.content.Context
import com.stash.core.common.Clock
import com.stash.core.common.SystemClock
import com.stash.data.lyrics.source.AppleTtmlLyricsSource
import com.stash.data.lyrics.source.InnerTubeLyricsGateway
import com.stash.data.lyrics.source.InnerTubeLyricsGatewayImpl
import com.stash.data.lyrics.source.IsrcLyricsHostBreaker
import com.stash.data.lyrics.source.IsrcResolver
import com.stash.data.lyrics.source.IsrcTtmlLyricsSource
import com.stash.data.lyrics.source.KugouLyricsSource
import com.stash.data.lyrics.source.LrclibLyricsSource
import com.stash.data.lyrics.source.LyricsSource
import com.stash.data.lyrics.source.SpicyLyricsSource
import com.stash.data.lyrics.source.YtMusicLyricsSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
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
         * Ordered source chain consumed by `LyricsRepository`. The order defines fallback
         * priority: lrc.red, Binimum, Spicy Lyrics, Apple TTML (paxsenix), LRCLIB, KuGou, InnerTube.
         * lrc.red and Binimum are `onDemandOnly`: asked only for on-demand lookups, and their
         * failures count as misses.
         */
        @Provides
        @Singleton
        fun provideLyricsSources(
            appleTtml: AppleTtmlLyricsSource,
            lrclib: LrclibLyricsSource,
            kugou: KugouLyricsSource,
            ytmusic: YtMusicLyricsSource,
            spicy: SpicyLyricsSource,
            okHttpClient: OkHttpClient,
            @ApplicationContext context: Context,
            isrcResolver: IsrcResolver,
            breaker: IsrcLyricsHostBreaker,
        ): List<@JvmSuppressWildcards LyricsSource> {
            val versionName = versionName(context)
            return listOf(
                IsrcTtmlLyricsSource(
                    okHttpClient, versionName, IsrcTtmlLyricsSource.LRC_RED_ID,
                    "lrc.red (word-synced)", IsrcTtmlLyricsSource.LRC_RED_URL, isrcResolver, breaker,
                ),
                IsrcTtmlLyricsSource(
                    okHttpClient, versionName, IsrcTtmlLyricsSource.BINIMUM_ID,
                    "Binimum (word-synced)", IsrcTtmlLyricsSource.BINIMUM_URL, isrcResolver, breaker,
                ),
                spicy,
                appleTtml, // lyrics.paxsenix.org
                lrclib,
                kugou,
                ytmusic,
            )
        }

        @Provides
        @Singleton
        fun provideSpicyLyricsSource(
            okHttpClient: OkHttpClient,
            @ApplicationContext context: Context,
            clock: Clock,
        ): SpicyLyricsSource = SpicyLyricsSource(
            client = okHttpClient,
            appVersionName = versionName(context),
            baseUrl = "https://proxy.paraliyzed.net/spicy",
        )

        /** title/artist -> ISRC for the lrc.red/Binimum sources; same DNS, User-Agent and breaker as they use. */
        @Provides
        @Singleton
        fun provideIsrcResolver(
            okHttpClient: OkHttpClient,
            @ApplicationContext context: Context,
            breaker: IsrcLyricsHostBreaker,
        ): IsrcResolver = IsrcResolver(okHttpClient, versionName(context), breaker)

        private fun versionName(context: Context): String =
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"

        /** Word-synced Apple Music TTML via lyrics.paxsenix.org; first so it wins when it has syllable timing. */
        @Provides
        @Singleton
        fun provideAppleTtmlLyricsSource(
            okHttpClient: OkHttpClient,
            @ApplicationContext context: Context,
        ): AppleTtmlLyricsSource {
            val versionName = context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName ?: "unknown"
            return AppleTtmlLyricsSource(okHttpClient, versionName)
        }

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
