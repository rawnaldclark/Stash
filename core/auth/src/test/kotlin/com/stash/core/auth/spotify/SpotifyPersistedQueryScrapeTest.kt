package com.stash.core.auth.spotify

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Spotify rotates its persisted-query hashes with every web-player build.
 * Issue #471 (0.9.102): the `home` hash rotated, Daily Mixes and Release
 * Radar stopped being discovered, and only the library MUTATION knew how to
 * re-scrape. Every operation now reads its current hash from the live
 * bundle, from ONE bundle download per process.
 *
 * Shapes below are the exact minified forms in web-player.981dd70a.js
 * (2026-09-06): `new i.l("home","query","<64 hex>",null)`.
 */
class SpotifyPersistedQueryScrapeTest {

    private val bundleFetches = AtomicInteger()

    private val shell =
        """<html><script src="https://open.spotifycdn.com/cdn/build/web-player/web-player.981dd70a.js"></script></html>"""

    private val bundle =
        """let a=new i.l("home","query","$HOME_NOW",null),s=new i.l("homeSection","query","$HOME_SECTION",null);""" +
            """let cR=new i9.l("libraryV3","query","$LIBRARY_NOW",null);""" +
            """let cb=new tV.l("addToLibrary","mutation","$MUTATION_NOW",null)"""

    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        val url = chain.request().url.toString()
        val body = when {
            url == "https://open.spotify.com/" -> shell
            url.endsWith("web-player.981dd70a.js") -> { bundleFetches.incrementAndGet(); bundle }
            else -> error("unexpected request $url")
        }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body.toResponseBody("text/plain".toMediaType())).build()
    }.build()

    private val manager = SpotifyAuthManager(http)

    @Test fun `a rotated query hash is read from the live bundle`() {
        assertEquals(HOME_NOW, manager.scrapePersistedQueryHash("home", staleHash = HOME_OLD))
    }

    @Test fun `one bundle download serves every operation`() {
        manager.scrapePersistedQueryHash("home", staleHash = HOME_OLD)
        assertEquals(LIBRARY_NOW, manager.scrapePersistedQueryHash("libraryV3", staleHash = "0".repeat(64)))
        assertEquals(MUTATION_NOW, manager.scrapePersistedQueryHash("addToLibrary", staleHash = "0".repeat(64)))
        assertEquals("the multi-MB bundle is fetched once per process", 1, bundleFetches.get())
    }

    @Test fun `an operation the bundle does not define yields null`() {
        assertNull(manager.scrapePersistedQueryHash("searchDesktop", staleHash = "0".repeat(64)))
    }

    @Test fun `a hash the bundle still lists as current is not offered again`() {
        // The failure was not a rotation (the bundle agrees with what we sent): nothing to swap,
        // and a freshly scraped bundle is not downloaded again for it.
        manager.scrapePersistedQueryHash("home", staleHash = HOME_OLD)
        assertNull(manager.scrapePersistedQueryHash("home", staleHash = HOME_NOW))
        assertEquals(1, bundleFetches.get())
    }

    private companion object {
        const val HOME_OLD = "23e37f2e58d82d567f27080101d36609009d8c3676457b1086cb0acc55b72a5d"
        const val HOME_NOW = "76243c78b0e20ecdbe41b794dec8cbe73f75e585b0a7201b8d2e84578412847a"
        const val HOME_SECTION = "5cb9a9e3d9a7c7f4b2a1e0d9c8b7a6f5e4d3c2b1a0f9e8d7c6b5a4f3e2d1c0b9"
        const val LIBRARY_NOW = "390c78e5b951029bad359785e69b07b536a509c581cbcd0aded5e5067f187455"
        const val MUTATION_NOW = "1ad0d40b3c09660d818b9e770eb1e84745dfbe941df159a64f8772b6fa2bfc3a"
    }
}
