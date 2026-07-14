package com.stash.data.download.lossless.qbdlx.di

import com.stash.core.data.discography.DiscographySupplement
import com.stash.core.data.discography.QobuzAlbumFetcher
import com.stash.core.data.discovery.HomeDiscoveryRepository
import com.stash.data.download.lossless.qbdlx.HomeDiscoveryRepositoryImpl
import com.stash.data.download.BuildConfig
import com.stash.data.download.lossless.LosslessSource
import com.stash.data.download.lossless.qbdlx.QbdlxPoolCipher
import com.stash.data.download.lossless.qbdlx.QbdlxPoolProvider
import com.stash.data.download.lossless.qbdlx.QbdlxQobuzSource
import com.stash.data.download.lossless.qbdlx.QbdlxSigner
import com.stash.data.download.lossless.qbdlx.QobuzAlbumFetcherImpl
import com.stash.data.download.lossless.qbdlx.QobuzDiscographyProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/**
 * Hilt wiring for the qbdlx direct-Qobuz lossless source.
 *
 * Binds [QbdlxQobuzSource] into the `Set<LosslessSource>` multibinding so
 * [com.stash.data.download.lossless.LosslessSourceRegistry] picks it up
 * alongside the other registered sources.
 *
 * The ONLY thing this module @Provides is the [QbdlxSigner] (it needs the
 * bundled app secret). Deliberately NOT provided here:
 *  - a bare `String` appId — that would pollute the global Hilt `String`
 *    namespace; [com.stash.data.download.lossless.qbdlx.QbdlxApiClient] reads
 *    `BuildConfig.QBDLX_APP_ID` itself.
 *  - `QbdlxApiClient` / `QbdlxCredentialStore` — both have `@Inject`
 *    constructors, so a second binding here = duplicate-binding error.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class QbdlxModule {

    @Binds
    @IntoSet
    abstract fun bindQbdlxAsLosslessSource(impl: QbdlxQobuzSource): LosslessSource

    @Binds
    abstract fun bindDiscographySupplement(impl: QobuzDiscographyProvider): DiscographySupplement

    @Binds
    abstract fun bindQobuzAlbumFetcher(impl: QobuzAlbumFetcherImpl): QobuzAlbumFetcher

    @Binds
    @Singleton
    abstract fun bindHomeDiscoveryRepository(impl: HomeDiscoveryRepositoryImpl): HomeDiscoveryRepository

    companion object {
        @Provides
        @Singleton
        fun provideQbdlxSigner(): QbdlxSigner = QbdlxSigner(BuildConfig.QBDLX_APP_SECRET)

        @Provides
        @Singleton
        fun provideQbdlxPoolProvider(): QbdlxPoolProvider = QbdlxPoolProvider {
            val pool = QbdlxPoolCipher.decrypt(BuildConfig.QBDLX_TOKEN_POOL)
            val fp = BuildConfig.QBDLX_POOL_FP
            if (fp.isNotBlank()) {
                val actual = if (pool.isBlank()) "" else
                    java.security.MessageDigest.getInstance("SHA-256")
                        .digest(pool.toByteArray())
                        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }.take(8)
                if (actual != fp) {
                    android.util.Log.w("QbdlxPool", "pool fp mismatch — embed/runtime crypto drift?")
                }
            }
            pool
        }
    }
}
