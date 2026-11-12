package com.stash.core.media.streaming

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.addons.AddonHostOptions
import com.stash.core.addons.HttpAddonClient
import com.stash.core.addons.NoFirstPartyAddons
import com.stash.core.addons.OpenAddonRateGate
import com.stash.core.addons.PrivateAddressException
import com.stash.core.addons.UnsignedAddons
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_ORIGIN
import io.mockk.coEvery
import io.mockk.mockk
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList

/**
 * review-882 round 2, F5: the player's most used add-on route, seen through a real player. A
 * tapped song, and a next-up the look-ahead swapped in, are items already resolved to the add-on's
 * URL; [StashMediaSourceFactory.createMediaSource] gives them the add-on's own media source.
 * `AddonPlaybackWiringTest` reads bytes through `dataSourceFactoryFor` (Cast's entry) and through
 * the factory handed to DASH; nothing there read through the source `createMediaSource` makes for
 * a plain file, so that line could go and every test stay green.
 *
 * Here an ExoPlayer is given the factory and prepares such an item. What reaches the add-on's
 * media host, and what the player's error is, show which source read it: the add-on client sends
 * `Stash/<version>` and refuses a redirect to a private address before any connection; the default
 * source sends Media3's own agent and follows the redirect.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AddonPlaybackPlayerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Every connection attempt of any client built from [shared]. */
    private class Wire : EventListener() {
        val connections = CopyOnWriteArrayList<InetSocketAddress>()

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            connections += inetSocketAddress
            if (!inetSocketAddress.address.isLoopbackAddress) call.cancel()
        }

        val offLoopback get() = connections.filterNot { it.address.isLoopbackAddress }
    }

    private val wire = Wire()
    private val shared: OkHttpClient = OkHttpClient.Builder().eventListener(wire).build()

    /** A debug host: the test's server is plain http on 127.0.0.1, which only such a host may call. */
    private val host = object : AddonHostOptions {
        override val allowLocalHttp = true
        override val userAgent = "Stash/9.9.9-test"
    }

    private val server = MockWebServer()
    private val addons = HttpAddonClient(shared, OpenAddonRateGate, NoFirstPartyAddons, UnsignedAddons, host)
    private val dao: TrackDao = mockk { coEvery { getById(any()) } returns null }
    private var player: ExoPlayer? = null

    @Before fun setUp() {
        // The add-on's media host answers every request with a redirect to the user's own network.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(302).setHeader("Location", "https://192.168.1.1/a.flac")
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After fun tearDown() {
        player?.release()
        server.shutdown()
    }

    private fun factory(wired: Boolean) = StashMediaSourceFactory(
        context = context,
        streamingFactory = mockk(relaxed = true),
        streamingTrackId = { null },
        isJioSaavnOrigin = { false },
        httpClient = shared,
        resolver = mockk(relaxed = true),
        urlCache = StreamUrlCache(),
        trackDao = dao,
        addonDataSourceFactory = if (wired) AddonPlayback.dataSourceFactory(addons) else null,
        isAddonOrigin = { AddonPlayback.isAddonOrigin(it.mediaMetadata.extras?.getString(EXTRA_STREAM_ORIGIN)) },
    )

    /** An item resolved to the add-on's URL and stamped with its origin, as the player stamps it. */
    private fun resolvedAddonItem(): MediaItem = MediaItem.Builder()
        .setMediaId("42")
        .setUri("http://127.0.0.1:${server.port}/a.flac")
        .setMediaMetadata(
            MediaMetadata.Builder().setExtras(Bundle().apply { putString(EXTRA_STREAM_ORIGIN, "addon:stranger") }).build(),
        )
        .build()

    private fun errorOfPreparing(factory: StashMediaSourceFactory): Throwable {
        val p = TestExoPlayerBuilder(context).setMediaSourceFactory(factory).build().also { player = it }
        p.setMediaItem(resolvedAddonItem())
        p.prepare()
        return TestPlayerRunHelper.runUntilError(p)
    }

    private fun Throwable.causes() = generateSequence(this) { it.cause }.toList()

    @Test fun `a resolved add-on item the player prepares is read on the add-on client - a redirect to a private address ends there`() {
        val error = errorOfPreparing(factory(wired = true))

        assertThat(error.causes().any { it is PrivateAddressException }).isTrue()
        // The add-on client's request, not the default source's.
        assertThat(server.takeRequest().getHeader("User-Agent")).isEqualTo("Stash/9.9.9-test")
        // The add-on's own host was dialled, and nothing else.
        assertThat(wire.connections).isNotEmpty()
        assertThat(wire.offLoopback).isEmpty()
    }

    @Test fun `with no add-on source wired the player is refused the item, and nothing is fetched`() {
        val error = errorOfPreparing(factory(wired = false))

        assertThat(error.causes().mapNotNull { it.message }).contains(ADDON_NOT_WIRED)
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(wire.connections).isEmpty()
    }
}
