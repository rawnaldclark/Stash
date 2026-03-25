package com.stash.data.download.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt module for the download layer.
 *
 * All download classes use constructor injection via @Singleton @Inject,
 * so no @Provides methods are currently needed. This module exists as
 * an extension point for future bindings (e.g., abstract interface binds).
 */
@Module
@InstallIn(SingletonComponent::class)
object DownloadModule
