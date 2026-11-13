package com.stash.core.media.streaming

import android.content.Context
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.ExoPlayerTestRunner
import androidx.media3.test.utils.FakeMediaSource
import androidx.media3.test.utils.FakeRenderer
import androidx.media3.test.utils.FakeTimeline
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.addons.AddonHostOptions
import com.stash.core.addons.HttpAddonClient
import com.stash.core.addons.NoFirstPartyAddons
import com.stash.core.addons.OpenAddonRateGate
import com.stash.core.addons.UnsignedAddons
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.prefs.DataSaver
import com.stash.core.media.service.CrossfadeEngine
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_CODEC
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_ORIGIN
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_DURATION_MS
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_ID
import com.stash.core.media.service.isResolvedForCrossfade
import com.stash.data.download.lossless.LosslessSourceHealthGate
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The player's check of an add-on's stream, through real players: what reaches the renderer of a
 * refused stream (nothing), whether an honest one plays, and the three places a stream is opened
 * before it is heard: the song playing, the next one read ahead for a gapless join, and the
 * crossfade spare. The add-on's media host is a local server handing out MP3 frames, which the
 * player's own extractor reads; the renderers are media3's fakes, which count what they are fed.
 *
 * Not proven here: a phone's decoders, and that the media session carries the refusal to
 * `PlayerRepositoryImpl` (its own tests take that from the error's message or the check's record).
 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AddonStreamCheckPlayerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val shared = OkHttpClient()
    private val host = object : AddonHostOptions {
        override val allowLocalHttp = true // the test's server is plain http on 127.0.0.1
        override val userAgent = "Stash/9.9.9-test"
    }
    private val addons = HttpAddonClient(shared, OpenAddonRateGate, NoFirstPartyAddons, UnsignedAddons, host)
    private val server = MockWebServer()
    private val gate = LosslessSourceHealthGate()
    private val lines = CopyOnWriteArrayList<String>()
    private val check = AddonStreamCheck(gate).apply { log = { lines += it } }
    private val resolver: StreamSourceRegistry = mockk()
    private val dao: TrackDao = mockk { coEvery { getById(any()) } returns null }
    private val players = mutableListOf<ExoPlayer>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The factory the playback service builds, with the check (or, for the "before" case, without). */
    private fun factory(checked: Boolean = true) = StashMediaSourceFactory(
        context = context,
        streamingFactory = mockk(relaxed = true),
        streamingTrackId = { null },
        isJioSaavnOrigin = { false },
        httpClient = shared,
        resolver = resolver,
        urlCache = StreamUrlCache(DataSaver.Off, NoAddonGate, gate),
        trackDao = dao,
        addonDataSourceFactory = AddonPlayback.dataSourceFactory(addons),
        isAddonOrigin = { AddonPlayback.isAddonOrigin(it.mediaMetadata.extras?.getString(EXTRA_STREAM_ORIGIN)) },
        addonCheck = if (checked) check else null,
    )

    private fun player(renderer: FakeRenderer, checked: Boolean = true): ExoPlayer =
        TestExoPlayerBuilder(context).setRenderers(renderer).setMediaSourceFactory(factory(checked)).build().also { players += it }

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/a.mp3" -> mp3(seconds = 60)
                "/short.mp3" -> mp3(seconds = 8)
                "/b48.mp3" -> mp3(seconds = 60, sampleRate = 48_000)
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After fun tearDown() {
        players.forEach { it.release() }
        scope.cancel()
        server.shutdown()
    }

    /**
     * A constant-bitrate MPEG-1 Layer III file of silent frames (128 kbps; 44.1 or 48 kHz), as an
     * add-on's media host would serve it. Its length is what the player reads from its size.
     */
    private fun mp3(seconds: Int, sampleRate: Int = 44_100): MockResponse {
        val (rateBits, frameBytes) = if (sampleRate == 48_000) 1 to 384 else 0 to 417
        val frames = seconds * sampleRate / 1152
        val bytes = ByteArray(frames * frameBytes)
        for (f in 0 until frames) {
            val at = f * frameBytes
            bytes[at] = 0xFF.toByte()
            bytes[at + 1] = 0xFB.toByte() // MPEG-1, Layer III, no CRC
            bytes[at + 2] = (0x90 or (rateBits shl 2)).toByte() // 128 kbps, the sample rate, no padding
            bytes[at + 3] = 0xC4.toByte() // mono
        }
        return MockResponse().setHeader("Content-Type", "audio/mpeg").setBody(Buffer().write(bytes))
    }

    private fun url(path: String) = "http://127.0.0.1:${server.port}$path"

    /** An item already resolved to the add-on's URL, stamped as the player stamps it: what the add-on called it, and the song's length. */
    private fun addonItem(id: Long, path: String, codec: String, songMs: Long? = 60_000L) = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(url(path))
        .setMediaMetadata(
            MediaMetadata.Builder().setExtras(
                Bundle().apply {
                    putLong(EXTRA_TRACK_ID, id)
                    putString(EXTRA_STREAM_ORIGIN, "addon:liar")
                    putString(EXTRA_STREAM_CODEC, codec)
                    songMs?.let { putLong(EXTRA_TRACK_DURATION_MS, it) }
                },
            ).build(),
        )
        .build()

    // ------------------------------------------------------------------------------------- the song playing

    @Test fun `an MP3 the add-on called FLAC is refused before a sample of it reaches the renderer`() {
        val audio = FakeRenderer(C.TRACK_TYPE_AUDIO)
        val p = player(audio)
        p.setMediaItem(addonItem(7L, "/a.mp3", codec = "flac"))
        p.prepare()
        p.play()

        val error = TestPlayerRunHelper.runUntilError(p)

        assertThat(AddonStreamRefusedException.isIn(error)).isTrue()
        assertThat(audio.enabledCount).isEqualTo(0)
        assertThat(audio.sampleBufferReadCount).isEqualTo(0)
        assertThat(lines).containsExactly("liar refused for 7: lossy")
        assertThat(gate.isDegraded("addon:liar")).isTrue()
        // The repository's error path reads this to resolve the song again instead of retrying the link.
        assertThat(check.takeRefusal(7L)).isTrue()
    }

    @Test fun `the same file called MP3 plays`() {
        val audio = FakeRenderer(C.TRACK_TYPE_AUDIO)
        val p = player(audio)
        p.setMediaItem(addonItem(8L, "/a.mp3", codec = "mp3"))
        p.prepare()
        p.play()

        TestPlayerRunHelper.runUntilPlaybackState(p, Player.STATE_ENDED)

        assertThat(audio.sampleBufferReadCount).isGreaterThan(0)
        assertThat(lines).isEmpty()
        assertThat(gate.isDegraded("addon:liar")).isFalse()
    }

    @Test fun `the file is a playable MP3 - without the check the one the add-on called FLAC is played`() {
        val audio = FakeRenderer(C.TRACK_TYPE_AUDIO)
        val p = player(audio, checked = false)
        p.setMediaItem(addonItem(7L, "/a.mp3", codec = "flac"))
        p.prepare()
        p.play()
        TestPlayerRunHelper.runUntilPlaybackState(p, Player.STATE_ENDED)
        assertThat(audio.sampleBufferReadCount).isGreaterThan(0)
    }

    // ------------------------------------------------------------------------------------- the next song, read ahead

    @Test fun `the next song read ahead for a gapless join is refused, and none of it is fed to the renderer`() {
        val audio = FakeRenderer(C.TRACK_TYPE_AUDIO)
        val p = player(audio)
        // An honest short song, then the add-on's lie at another sample rate, so its samples would show.
        p.setMediaItems(listOf(addonItem(1L, "/short.mp3", codec = "mp3", songMs = null), addonItem(2L, "/b48.mp3", codec = "flac")))
        p.prepare()
        p.play()

        val error = TestPlayerRunHelper.runUntilError(p)

        assertThat(AddonStreamRefusedException.isIn(error)).isTrue()
        assertThat(p.currentMediaItemIndex).isEqualTo(1) // reported at the join, for the refused song
        assertThat(audio.formatsRead).isNotEmpty()
        assertThat(audio.formatsRead.map { it.sampleRate }.distinct()).containsExactly(44_100)
        assertThat(lines).containsExactly("liar refused for 2: lossy")
    }

    // ------------------------------------------------------------------------------------- a placeholder, resolved as it plays

    @Test fun `a placeholder that resolves to the add-on's lie is refused, noted as the add-on's, and plays from the next source`() {
        val lie = StreamUrl(url = url("/a.mp3"), expiresAtMs = System.currentTimeMillis() + 3_600_000L, codec = "flac", origin = "addon:liar")
        val honest = lie.copy(codec = "mp3", origin = "qbdlx") // the next source in the user's order
        coEvery { resolver.resolve(any(), any(), any(), any()) } returnsMany listOf(lie, honest)
        val audio = FakeRenderer(C.TRACK_TYPE_AUDIO)
        val p = player(audio)
        val placeholderUri = stashResolveUri(9L, title = "Song", artist = "Artist", durationMs = 60_000L)
        val placeholder = MediaItem.Builder().setMediaId("9").setUri(placeholderUri)
            .setMediaMetadata(MediaMetadata.Builder().setExtras(Bundle().apply { putLong(EXTRA_TRACK_ID, 9L) }).build()).build()
        p.setMediaItem(placeholder)
        p.prepare()
        p.play()

        val error = TestPlayerRunHelper.runUntilError(p)

        assertThat(AddonStreamRefusedException.isIn(error)).isTrue()
        assertThat(audio.sampleBufferReadCount).isEqualTo(0)
        assertThat(lines).containsExactly("liar refused for 9: lossy")
        // Its slot keeps no origin of its own; the check knows whose link it opened.
        assertThat(check.openedFrom(9L)).isEqualTo("addon:liar")

        // What the repository does then: the same song, a URI the player hasn't seen, prepared again.
        // The cached lie is not served (the add-on is set aside); the resolve answers from the next source.
        p.replaceMediaItem(0, placeholder.buildUpon().setUri(placeholderUri.resolvedAgain()).build())
        p.prepare()
        TestPlayerRunHelper.runUntilPlaybackState(p, Player.STATE_ENDED)
        assertThat(audio.sampleBufferReadCount).isGreaterThan(0)
        assertThat(lines).hasSize(1)
    }

    // ------------------------------------------------------------------------------------- what the extractor found: video, length

    private fun claimed(songMs: Long) = AddonStreamCheck.Claim("addon:liar", 11L, "11", statedLossless = true, songMs = songMs)

    private fun fake(durationSec: Long, vararg formats: Format) =
        FakeMediaSource(FakeTimeline(FakeTimeline.TimelineWindowDefinition(1, 0, true, false, durationSec * C.MICROS_PER_SECOND)), *formats)

    private val flac = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_FLAC).setChannelCount(2).setSampleRate(44_100).build()

    private fun errorOf(source: androidx.media3.exoplayer.source.MediaSource, songMs: Long): Throwable {
        val p = TestExoPlayerBuilder(context).setRenderers(FakeRenderer(C.TRACK_TYPE_AUDIO)).build().also { players += it }
        p.setMediaSource(AddonCheckedMediaSource(source, check) { claimed(songMs) })
        p.prepare()
        return TestPlayerRunHelper.runUntilError(p)
    }

    @Test fun `a file with a video track is refused`() {
        val error = errorOf(fake(67, ExoPlayerTestRunner.VIDEO_FORMAT, ExoPlayerTestRunner.AUDIO_FORMAT), songMs = 240_000L)
        assertThat(AddonStreamRefusedException.isIn(error)).isTrue()
        assertThat(lines).containsExactly("liar refused for 11: video")
    }

    @Test fun `a 10 s file for a 4 minute song is refused, and a whole one plays`() {
        val error = errorOf(fake(10, flac), songMs = 240_000L)
        assertThat(AddonStreamRefusedException.isIn(error)).isTrue()
        assertThat(lines).containsExactly("liar refused for 11: length")

        val p = TestExoPlayerBuilder(context).setRenderers(FakeRenderer(C.TRACK_TYPE_AUDIO)).build().also { players += it }
        p.setMediaSource(AddonCheckedMediaSource(fake(240, flac), check) { claimed(240_000L) })
        p.prepare()
        TestPlayerRunHelper.runUntilPlaybackState(p, Player.STATE_READY)
        assertThat(lines).hasSize(1)
    }

    @Test fun `with the song's length unknown a short file plays`() {
        val p = TestExoPlayerBuilder(context).setRenderers(FakeRenderer(C.TRACK_TYPE_AUDIO)).build().also { players += it }
        p.setMediaSource(AddonCheckedMediaSource(fake(10, flac), check) { claimed(0L) })
        p.prepare()
        TestPlayerRunHelper.runUntilPlaybackState(p, Player.STATE_READY)
        assertThat(lines).isEmpty()
    }

    // ------------------------------------------------------------------------------------- the crossfade spare

    private class Spare(val player: ExoPlayer, val audio: FakeRenderer)

    /** A crossfade engine over two real players built with the service's factory; [spares] gets the second. */
    private fun engine(spares: MutableList<Spare>): CrossfadeEngine {
        val f = factory()
        return CrossfadeEngine(context, {
            val audio = FakeRenderer(C.TRACK_TYPE_AUDIO)
            val built = TestExoPlayerBuilder(context).setRenderers(audio).setMediaSourceFactory(f).build().also { players += it }
            spares += Spare(built, audio)
            built
        }, scope).also { it.initialize() }
    }

    @Test fun `the crossfade spare primed on the add-on's lie is refused before it buffers a sample, and is let go`() {
        val built = mutableListOf<Spare>()
        val engine = engine(built)
        val spare = built[1]
        val lie = addonItem(2L, "/a.mp3", codec = "flac")

        engine.prepareNext(lie)
        val error = TestPlayerRunHelper.runUntilError(spare.player)

        assertThat(AddonStreamRefusedException.isIn(error)).isTrue()
        assertThat(spare.audio.sampleBufferReadCount).isEqualTo(0)
        assertThat(lines).containsExactly("liar refused for 2: lossy")
        // The service hears the refusal and lets the spare go; the poll primes it again on the slot as it then stands.
        assertThat(engine.dropSpareFor("2")).isTrue()
        assertThat(spare.player.mediaItemCount).isEqualTo(0)
        assertThat(engine.isPreparedFor(lie)).isFalse()
    }

    @Test fun `the spare follows its slot - a song put back with another URI is primed again, new metadata is not`() {
        val built = mutableListOf<Spare>()
        val engine = engine(built)
        val honest = addonItem(3L, "/a.mp3", codec = "mp3")
        engine.prepareNext(honest)

        assertThat(engine.isPreparedFor(honest)).isTrue()
        val newCover = honest.buildUpon().setMediaMetadata(honest.mediaMetadata.buildUpon().setTitle("Stamped").build()).build()
        assertThat(engine.isPreparedFor(newCover)).isTrue()
        // The add-on left: its slot is a fresh placeholder now. The spare must not fade in what the slot no longer holds.
        val putBack = honest.buildUpon().setUri(stashResolveUri(3L).resolvedAgain()).build()
        assertThat(engine.isPreparedFor(putBack)).isFalse()
        // Another song's refusal leaves this spare alone.
        assertThat(engine.dropSpareFor("4")).isFalse()
        assertThat(built[1].player.mediaItemCount).isEqualTo(1)
    }

    @Test fun `the spare is never primed on a URL from an add-on that is set aside`() {
        val fromLiar = addonItem(5L, "/a.mp3", codec = "flac")
        val setAside = { origin: String? -> origin == "addon:liar" }
        assertThat(isResolvedForCrossfade(fromLiar) { false }).isTrue()
        assertThat(isResolvedForCrossfade(fromLiar, setAside)).isFalse()
        // A placeholder or a file is primed as before: it resolves (without the add-on) as it opens.
        assertThat(isResolvedForCrossfade(fromLiar.buildUpon().setUri(stashResolveUri(5L)).build(), setAside)).isTrue()
        assertThat(isResolvedForCrossfade(fromLiar.buildUpon().setUri("file:///music/5.flac").build(), setAside)).isTrue()
        // A URL with no origin is not resolved yet, as before.
        val bare = MediaItem.Builder().setMediaId("6").setUri(url("/a.mp3")).build()
        assertThat(isResolvedForCrossfade(bare) { false }).isFalse()
    }
}
