package com.stash.core.network.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Hilt module that provides networking dependencies shared across all modules.
 *
 * The [OkHttpClient] singleton is configured with sensible timeouts, a logging
 * interceptor (body-level in debug builds, none in release), and enforced
 * TLS 1.2+ via [ConnectionSpec.MODERN_TLS] to prevent cipher-downgrade attacks.
 *
 * **Certificate pinning note:** Full certificate pinning with SHA-256 pin hashes
 * for Spotify / YouTube endpoints is a future enhancement. Pinned hashes must be
 * rotated whenever the service rotates its leaf or intermediate certificates,
 * which risks hard-locking users out of the app if an update is missed. For now,
 * MODERN_TLS ensures only strong cipher suites and TLS 1.2+ are negotiated.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val TIMEOUT_SECONDS = 30L

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (com.stash.core.network.BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
            redactHeader("Authorization")
            redactHeader("Cookie")
        }

        return OkHttpClient.Builder()
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor)
            .build()
    }
}
