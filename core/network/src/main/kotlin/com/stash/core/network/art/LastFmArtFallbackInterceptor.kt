package com.stash.core.network.art

import com.stash.core.common.ArtUrlUpgrader
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Retries a Last.fm cover that came back 404 at the next variant down.
 *
 * Last.fm advertises only a 300x300 PNG, but serves the same hash 770 wide,
 * and as a JPEG that is lighter than the 300px PNG (see [ArtUrlUpgrader]).
 * Those wider variants are rendered on demand and a few percent of hashes are
 * missing one of the two formats, so the stored URL alone would turn a cover
 * that renders today into nothing at all — the exact failure that made album
 * art go black when `sddefault` replaced `hqdefault` for YouTube thumbnails.
 *
 * Sits on the shared OkHttp client so ONE rung-walk covers every consumer:
 * Coil for Now Playing, rows and mosaics, and media3's bitmap loader for the
 * notification and lock screen.
 */
@Singleton
class LastFmArtFallbackInterceptor @Inject constructor() : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        var response = chain.proceed(request)
        // At most two steps: 770 jpg -> 770 png -> the API's own 300x300 png.
        repeat(2) {
            if (response.code != 404) return response
            val next = ArtUrlUpgrader.lastFmFallback(request.url.toString()) ?: return response
            response.close()
            request = request.newBuilder().url(next).build()
            response = chain.proceed(request)
        }
        return response
    }
}
