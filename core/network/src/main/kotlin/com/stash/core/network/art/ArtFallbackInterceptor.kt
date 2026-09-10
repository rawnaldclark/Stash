package com.stash.core.network.art

import com.stash.core.common.ArtUrlUpgrader
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Retries album art that came back 404 at the next variant down.
 *
 * Both upgraded hosts store a URL that is better than what is guaranteed to
 * exist: Last.fm serves its covers 770 wide as a JPEG though the API only
 * advertises a 300x300 PNG, and YouTube's `maxresdefault` is a clean 1280x720
 * where `hqdefault` is a 480x360 letterbox. Neither is present for every
 * item, and a 404 draws nothing — the exact failure that made album art go
 * black when `sddefault` first replaced `hqdefault`. This walks down instead:
 * a cover can never render worse than the variant it replaced.
 *
 * Sits on the shared OkHttp client so ONE rung-walk covers every consumer:
 * Coil for Now Playing, rows and mosaics, and media3's bitmap loader for the
 * notification and lock screen.
 */
@Singleton
class ArtFallbackInterceptor @Inject constructor() : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        var response = chain.proceed(request)
        // At most two steps on either host: Last.fm 770 jpg -> 770 png ->
        // its own 300x300 png; YouTube maxres -> sd -> hq.
        repeat(2) {
            if (response.code != 404) return response
            val next = ArtUrlUpgrader.artFallback(request.url.toString()) ?: return response
            response.close()
            request = request.newBuilder().url(next).build()
            response = chain.proceed(request)
        }
        return response
    }
}
