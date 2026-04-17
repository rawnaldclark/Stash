package com.stash.app

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath

/**
 * App-wide [ImageLoader] configuration.
 *
 * Tuned for the Search + Artist Profile surfaces, which render lots of
 * thumbnails concurrently:
 *  - 25% of heap for the in-memory bitmap cache.
 *  - 250 MB on-disk cache for artwork persistence between launches.
 *  - Shares the app's [OkHttpClient] so DNS/TLS reuse is maximal.
 *  - Crossfade off globally — the hero composables do their own crossfade.
 */
object CoilConfiguration {
    fun build(context: PlatformContext, okHttp: OkHttpClient): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("coil").toOkioPath())
                    .maxSizeBytes(250L * 1024 * 1024)
                    .build()
            }
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttp }))
            }
            .crossfade(false) // hero does its own crossfade
            .build()
}
