package com.stash.data.soundcloud

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Opt-in service compatibility check. CI and normal unit runs never access the live service. */
class SoundCloudLiveSmokeTest {
    @Test fun publicTrackAndManifest() {
        assumeTrue(System.getenv("STASH_SOUNDCLOUD_LIVE") == "1")
        runBlocking {
            withTimeout(60_000) {
                val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).addInterceptor { chain ->
                    val reply = chain.proceed(chain.request())
                    if (chain.request().url.host == "api-v2.soundcloud.com" && chain.request().url.encodedPath.startsWith("/media/")) {
                        runCatching {
                            val value = kotlinx.serialization.json.Json.parseToJsonElement(reply.peekBody(65536).string()) as kotlinx.serialization.json.JsonObject
                            val media = (value["url"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                            val uri = java.net.URI(media)
                            println("SoundCloud media destination: scheme=${uri.scheme}, host=${uri.host}, port=${uri.port}")
                        }
                    }
                    reply
                }.build()
                val client = SoundCloudClient(http)
                val entity = client.resolve(SoundCloudLinks.parse("https://soundcloud.com/forss/flickermood")!!)
                val track = client.parseTrack(entity)
                val stream = client.stream(track.id) ?: error("Public track has no playable stream")
                http.newCall(Request.Builder().url(stream.url).header("Range", "bytes=0-4095").build()).execute().use { response ->
                    assertTrue("SoundCloud media response ${response.code}", response.isSuccessful)
                    val sample = response.peekBody(4096).string()
                    assertTrue(sample.isNotEmpty())
                    if (stream.hls) assertTrue("Expected HLS playlist", sample.contains("#EXTM3U"))
                }
                println("SoundCloud public API and media verified: HLS=${stream.hls}, preview=${stream.preview}")
            }
        }
    }
}
