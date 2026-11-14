package com.stash.core.media.streaming

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.addons.AddonHostOptions
import com.stash.core.addons.HttpAddonClient
import com.stash.core.addons.NoFirstPartyAddons
import com.stash.core.addons.OpenAddonRateGate
import com.stash.core.addons.PrivateAddressException
import com.stash.core.addons.UnsignedAddons
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_DASH
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_ORIGIN
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList

/**
 * review-882 B1 and M1, the player's half: a stream an add-on answered is read through the add-on
 * media client and nothing else, on each of the player's routes ([StashMediaSourceFactory]):
 *  - an item already resolved to the add-on's URL (the next-up prefetch, a tap);
 *  - a `stash-resolve://` placeholder whose stream turns out to be an add-on's
 *    ([LazyResolvingDataSource]);
 *  - an add-on's DASH manifest (the factory handed to the DASH source);
 *  - and with no add-on source wired, such a stream is refused, never read on the default source.
 *
 * The factory under test is the real one, built with [AddonPlayback]'s source and name check: the
 * two things the playback service passes it. What is NOT proven here is the service's own wiring
 * (that it passes them, and reads the origin from the item's extras): that needs a phone.
 *
 * Each case points the add-on's media at the user's own network (a redirect to a private
 * address) and asserts, from the shared client's event listener, that no connection to anything
 * but the add-on's own host was even attempted.
 */
@RunWith(RobolectricTestRunner::class)
class AddonPlaybackWiringTest {

    /** Every DNS lookup and connection attempt of any client built from [shared]. */
    private class Wire : EventListener() {
        val lookups = CopyOnWriteArrayList<String>()
        val connections = CopyOnWriteArrayList<InetSocketAddress>()

        override fun dnsStart(call: Call, domainName: String) {
            lookups += domainName
        }

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            connections += inetSocketAddress
            // Don't wait out a connect timeout to report the failure being tested for.
            if (!inetSocketAddress.address.isLoopbackAddress) call.cancel()
        }

        val offLoopback get() = connections.filterNot { it.address.isLoopbackAddress }
    }

    private val wire = Wire()

    private val shared: OkHttpClient = OkHttpClient.Builder()
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                if (hostname == "media.attacker.example") {
                    listOf(InetAddress.getByAddress(hostname, byteArrayOf(192.toByte(), 168.toByte(), 1, 1)))
                } else {
                    Dns.SYSTEM.lookup(hostname)
                }
        })
        .eventListener(wire)
        .build()

    /** A debug host: the test's server is plain http on 127.0.0.1, which only such a host may call. */
    private val host = object : AddonHostOptions {
        override val allowLocalHttp = true
        override val userAgent = "Stash/9.9.9-test"
    }

    private val server = MockWebServer()
    private val addons = HttpAddonClient(shared, OpenAddonRateGate, NoFirstPartyAddons, UnsignedAddons, host)
    private val streaming: StreamingMediaSourceFactory = mockk(relaxed = true)
    private val cache = StreamUrlCache()
    private val dao: TrackDao = mockk { coEvery { getById(any()) } returns null }

    @Before fun setUp() = server.start(InetAddress.getByName("127.0.0.1"), 0)

    @After fun tearDown() = server.shutdown()

    /** Never `server.url()`: its host is the machine's reverse name for 127.0.0.1, which is not always a development host. */
    private fun at(path: String) = "http://127.0.0.1:${server.port}$path"

    private fun redirect(location: String) = MockResponse().setResponseCode(302).setHeader("Location", location)

    /**
     * The player's factory as the service builds it; [wired] false leaves the add-on source out.
     * [resolveFirst]: placeholders resolve before their source is picked, as they do while a Tidal
     * server is set or an add-on can answer DASH ([AddonPlayback.resolveBeforeSourcePick]).
     */
    private fun factory(wired: Boolean = true, resolveFirst: Boolean = false) = StashMediaSourceFactory(
        context = ApplicationProvider.getApplicationContext(),
        streamingFactory = streaming,
        streamingTrackId = { null },
        isJioSaavnOrigin = { false },
        httpClient = shared,
        resolver = mockk(relaxed = true),
        urlCache = cache,
        trackDao = dao,
        resolveBeforeSourcePick = { resolveFirst },
        resolveDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        addonDataSourceFactory = if (wired) AddonPlayback.dataSourceFactory(addons) else null,
        isAddonOrigin = { AddonPlayback.isAddonOrigin(it.mediaMetadata.extras?.getString(EXTRA_STREAM_ORIGIN)) },
        isDashStream = { it.mediaMetadata.extras?.getBoolean(EXTRA_STREAM_DASH) == true },
    )

    /** An item resolved to [url], stamped with the rung that answered it, as the player stamps it. */
    private fun item(url: String, origin: String? = "addon:stranger", dash: Boolean = false): MediaItem = MediaItem.Builder()
        .setMediaId("42")
        .setUri(url)
        .setMediaMetadata(
            MediaMetadata.Builder().setExtras(
                Bundle().apply {
                    origin?.let { putString(EXTRA_STREAM_ORIGIN, it) }
                    if (dash) putBoolean(EXTRA_STREAM_DASH, true)
                },
            ).build(),
        )
        .build()

    private fun addonStream(url: String) = StreamUrl(url, System.currentTimeMillis() + 600_000L, codec = "flac", origin = "addon:stranger")

    private fun readAll(source: DataSource, uri: String): String {
        source.open(DataSpec(Uri.parse(uri)))
        try {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(1024)
            while (true) {
                val n = source.read(buf, 0, buf.size)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toString("UTF-8")
        } finally {
            source.close()
        }
    }

    private fun failure(source: DataSource, uri: String): Throwable =
        try {
            readAll(source, uri)
            throw AssertionError("the stream opened")
        } catch (e: IOException) {
            e
        }

    private fun Throwable.causes() = generateSequence(this) { it.cause }.toList()

    // ------------------------------------------------------------------------------------------ a resolved item

    @Test fun `a resolved add-on item is read on the add-on client`() {
        server.enqueue(MockResponse().setBody("FLAC"))
        val url = at("/a.flac")
        assertThat(readAll(factory().dataSourceFactoryFor(item(url)).createDataSource(), url)).isEqualTo("FLAC")
        assertThat(server.takeRequest().getHeader("User-Agent")).isEqualTo("Stash/9.9.9-test")
    }

    @Test fun `a resolved add-on item that redirects to a private address is refused, and no second connection is attempted`() {
        for (target in listOf("https://192.168.1.1/a.flac", "https://nas.lan/a.flac", "https://media.attacker.example/a.flac")) {
            server.enqueue(redirect(target))
            val url = at("/a.flac")
            failure(factory().dataSourceFactoryFor(item(url)).createDataSource(), url)
        }
        assertThat(server.requestCount).isEqualTo(3)
        assertThat(wire.connections).isNotEmpty()
        assertThat(wire.offLoopback).isEmpty()
        // Only the name that passes the URL rule was looked up at all, and its private answer was dropped.
        assertThat(wire.lookups).containsExactly("media.attacker.example")
    }

    @Test fun `the refusal names the reason, never the address`() {
        server.enqueue(redirect("https://192.168.1.1/a.flac"))
        val url = at("/a.flac")
        val e = failure(factory().dataSourceFactoryFor(item(url)).createDataSource(), url)
        assertThat(e.causes().any { it is PrivateAddressException }).isTrue()
        assertThat(e.causes().mapNotNull { it.message }.none { "192.168" in it }).isTrue()
    }

    @Test fun `with no add-on source wired, a resolved add-on item is refused and nothing is fetched`() {
        server.enqueue(MockResponse().setBody("FLAC"))
        val url = at("/a.flac")
        val e = failure(factory(wired = false).dataSourceFactoryFor(item(url)).createDataSource(), url)
        // And it says why: it used to read "Download mode: https streams don't play".
        assertThat(e.message).isEqualTo(ADDON_NOT_WIRED)
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(wire.connections).isEmpty()
    }

    // ------------------------------------------------------------------------------------------ a placeholder

    @Test fun `a placeholder whose stream is an add-on's opens it on the add-on client`() {
        server.enqueue(MockResponse().setBody("FLAC"))
        cache.put(42, addonStream(at("/a.flac")))
        val placeholder = "stash-resolve://track/42"
        assertThat(readAll(factory().dataSourceFactoryFor(MediaItem.fromUri(placeholder)).createDataSource(), placeholder)).isEqualTo("FLAC")
        assertThat(server.takeRequest().getHeader("User-Agent")).isEqualTo("Stash/9.9.9-test")
    }

    @Test fun `a placeholder whose add-on stream redirects to a private address is refused, and no second connection is attempted`() {
        server.enqueue(redirect("https://192.168.1.1/a.flac"))
        cache.put(42, addonStream(at("/a.flac")))
        val placeholder = "stash-resolve://track/42"
        val e = failure(factory().dataSourceFactoryFor(MediaItem.fromUri(placeholder)).createDataSource(), placeholder)
        assertThat(e.causes().any { it is PrivateAddressException }).isTrue()
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(wire.offLoopback).isEmpty()
    }

    @Test fun `with no add-on source wired, a placeholder's add-on stream is refused and nothing is fetched`() {
        server.enqueue(MockResponse().setBody("FLAC"))
        cache.put(42, addonStream(at("/a.flac")))
        val placeholder = "stash-resolve://track/42"
        failure(factory(wired = false).dataSourceFactoryFor(MediaItem.fromUri(placeholder)).createDataSource(), placeholder)
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(wire.connections).isEmpty()
    }

    // ------------------------------------------------------------------------------------------ DASH

    @Test fun `an add-on's DASH manifest gets the add-on client for the manifest and every segment`() {
        val http = slot<DataSource.Factory>()
        val mpd = at("/dash/1.mpd")
        factory().createMediaSource(item(mpd))
        verify(exactly = 1) { streaming.createDashMediaSource(any(), mpd, capture(http)) }

        // The factory the DASH source was given is the guarded one: a segment that redirects to
        // the user's own network is refused with no second connection.
        server.enqueue(redirect("https://10.0.0.5/seg-1.m4s"))
        val segment = at("/seg-1.m4s")
        val e = failure(http.captured.createDataSource(), segment)
        assertThat(e.causes().any { it is PrivateAddressException }).isTrue()
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(wire.offLoopback).isEmpty()
    }

    // ------------------------------------------------------------------------------------------ a placeholder resolved first (review-882 round 2, F5 and F9)

    /** Prepares [source] as the player does and lets its resolve run, so its child source is made. */
    private fun prepared(source: androidx.media3.exoplayer.source.MediaSource): androidx.media3.exoplayer.source.MediaSource.MediaSourceCaller {
        val caller = androidx.media3.exoplayer.source.MediaSource.MediaSourceCaller { _, _ -> }
        source.prepareSource(caller, null, androidx.media3.exoplayer.analytics.PlayerId.UNSET)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        return caller
    }

    private val placeholder: MediaItem = MediaItem.Builder().setMediaId("42").setUri("stash-resolve://track/42").build()

    @Test fun `a placeholder resolved first to an add-on's DASH manifest gets its DASH source on the add-on client`() {
        val mpd = at("/dash?id=1")
        cache.put(42, addonStream(mpd).copy(isDash = true))
        val http = slot<DataSource.Factory>()
        val child = mockk<androidx.media3.exoplayer.source.MediaSource>(relaxed = true) {
            io.mockk.every { mediaItem } returns placeholder.buildUpon().setUri(mpd).build()
        }
        io.mockk.every { streaming.createDashMediaSource(any(), mpd, capture(http)) } returns child

        val source = factory(resolveFirst = true).createMediaSource(placeholder)
        val caller = prepared(source)

        verify(exactly = 1) { streaming.createDashMediaSource(any(), mpd, any()) }
        // The factory the DASH child was given is the guarded one, not the default HTTP source.
        server.enqueue(redirect("https://10.0.0.5/seg-1.m4s"))
        val segment = at("/seg-1.m4s")
        assertThat(failure(http.captured.createDataSource(), segment).causes().any { it is PrivateAddressException }).isTrue()
        assertThat(server.takeRequest().getHeader("User-Agent")).isEqualTo("Stash/9.9.9-test")
        assertThat(wire.offLoopback).isEmpty()
        source.releaseSource(caller)
    }

    @Test fun `with no add-on source wired, a placeholder resolved first to an add-on's DASH manifest is refused`() {
        val mpd = at("/dash?id=1")
        cache.put(42, addonStream(mpd).copy(isDash = true))
        val source = factory(wired = false, resolveFirst = true).createMediaSource(placeholder)
        val caller = prepared(source)
        // Never the default DASH source, which would fetch the manifest and its segments unguarded.
        verify(exactly = 0) { streaming.createDashMediaSource(any(), any(), any()) }
        assertThat(server.requestCount).isEqualTo(0)
        source.releaseSource(caller)
    }

    /** review-882 round 2, F9: the resolve-first path must not depend on a Tidal server being set. */
    @Test fun `placeholders resolve first while a Tidal server is set, or an add-on that is on can answer DASH`() {
        fun rungs(vararg manifests: List<String>) = AddonStreamRungs(
            object : com.stash.core.addons.AddonRegistry {
                override val addons = kotlinx.coroutines.flow.MutableStateFlow(
                    manifests.mapIndexed { i, m ->
                        com.stash.core.addons.InstalledAddon(
                            base = "https://addon$i.example/",
                            manifest = com.stash.addons.contract.Manifest(
                                stash = 1, id = "a$i", name = "A$i", version = "1", kind = com.stash.addons.contract.AddonKind.SERVER,
                                resources = listOf("search", "stream"),
                                stream = com.stash.addons.contract.StreamCapabilities(cors = false, manifests = m),
                            ),
                            userAdded = true,
                        )
                    },
                )
            },
            // Strict: the question asks nothing of any add-on.
            mockk(),
        )
        val none = rungs()
        val filesOnly = rungs(listOf("none"))
        val dash = rungs(listOf("none"), listOf("none", "dash", "hls"))
        assertThat(none.mayAnswerDash()).isFalse()
        assertThat(filesOnly.mayAnswerDash()).isFalse()
        assertThat(dash.mayAnswerDash()).isTrue()
        // No Tidal server: only an add-on that can answer a manifest makes placeholders resolve first.
        assertThat(AddonPlayback.resolveBeforeSourcePick(tidalConfigured = false, addons = none)).isFalse()
        assertThat(AddonPlayback.resolveBeforeSourcePick(tidalConfigured = false, addons = filesOnly)).isFalse()
        assertThat(AddonPlayback.resolveBeforeSourcePick(tidalConfigured = false, addons = dash)).isTrue()
        // With one, as before.
        assertThat(AddonPlayback.resolveBeforeSourcePick(tidalConfigured = true, addons = none)).isTrue()
    }

    @Test fun `with no add-on source wired, an add-on's DASH manifest is refused, never handed to the default source`() {
        factory(wired = false).createMediaSource(item(at("/dash/1.mpd")))
        verify(exactly = 0) { streaming.createDashMediaSource(any(), any(), any()) }
    }

    /** review-882 N4: only a path ending `.mpd` was taken for a manifest; `/dash?id=1` went to the progressive source. */
    @Test fun `an add-on's manifest at any path is a DASH source when its item says so, on the add-on client`() {
        val http = slot<DataSource.Factory>()
        val manifest = at("/dash?id=1")
        factory().createMediaSource(item(manifest, dash = true))
        verify(exactly = 1) { streaming.createDashMediaSource(any(), manifest, capture(http)) }
        server.enqueue(redirect("https://192.168.1.1/seg-1.m4s"))
        val segment = at("/seg-1.m4s")
        assertThat(failure(http.captured.createDataSource(), segment).causes().any { it is PrivateAddressException }).isTrue()
        assertThat(wire.offLoopback).isEmpty()
    }

    @Test fun `without that word an add-on's file is not taken for a manifest, and only an add-on's word counts`() {
        factory().createMediaSource(item(at("/a.flac")))
        // Tidal's manifests are known by their path alone: the flag on another origin changes nothing.
        factory().createMediaSource(item(at("/track/1"), origin = "tidal", dash = true))
        verify(exactly = 0) { streaming.createDashMediaSource(any(), any(), any()) }
    }

    @Test fun `a manifest that is not an add-on's keeps the default source`() {
        val mpd = at("/dash/td/1.mpd")
        factory().createMediaSource(item(mpd, origin = "tidal"))
        verify(exactly = 1) { streaming.createDashMediaSource(any(), mpd, isNull()) }
    }
}
