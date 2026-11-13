package com.stash.core.media.streaming

import android.content.Context
import android.os.Bundle
import android.os.Looper
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
import androidx.media3.test.utils.robolectric.RobolectricUtil
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
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_DASH
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_ORIGIN
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_SONG_MS
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_DURATION_MS
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_ID
import com.stash.core.media.service.isResolvedForCrossfade
import com.stash.data.download.lossless.LosslessSourceHealthGate
import io.mockk.coEvery
import io.mockk.coVerify
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
import org.robolectric.Shadows.shadowOf
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

    /** The stream URL cache the players read; every add-on is on unless a test says otherwise. */
    private var cache = StreamUrlCache(DataSaver.Off, NoAddonGate, gate)

    /**
     * The factory the playback service builds, with the check (or, for the "before" case, without).
     * [resolveFirst]: a Tidal server set or a DASH add-on on, so a placeholder resolves before its
     * source is picked ([ResolveFirstMediaSource]).
     */
    private fun factory(checked: Boolean = true, resolveFirst: Boolean = false) = StashMediaSourceFactory(
        context = context,
        streamingFactory = StreamingMediaSourceFactory(mockk(relaxed = true), resolver, cache, dao),
        streamingTrackId = { null },
        isJioSaavnOrigin = { false },
        httpClient = shared,
        resolver = resolver,
        urlCache = cache,
        trackDao = dao,
        resolveBeforeSourcePick = { resolveFirst },
        addonDataSourceFactory = AddonPlayback.dataSourceFactory(addons),
        isAddonOrigin = { AddonPlayback.isAddonOrigin(it.mediaMetadata.extras?.getString(EXTRA_STREAM_ORIGIN)) },
        isDashStream = { it.mediaMetadata.extras?.getBoolean(EXTRA_STREAM_DASH) == true },
        addonCheck = if (checked) check else null,
    )

    private fun player(renderer: FakeRenderer, checked: Boolean = true, resolveFirst: Boolean = false): ExoPlayer =
        TestExoPlayerBuilder(context).setRenderers(renderer).setMediaSourceFactory(factory(checked, resolveFirst)).build().also { players += it }

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/a.mp3" -> mp3(seconds = 60)
                "/short.mp3" -> mp3(seconds = 8)
                "/b48.mp3" -> mp3(seconds = 60, sampleRate = 48_000)
                "/unsized.mp3" -> mp3WithoutLength(seconds = 60)
                "/aac.mpd" -> mpd(codecs = "mp4a.40.2")
                "/flac.mpd" -> mpd(codecs = "flac")
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
    private fun mp3(seconds: Int, sampleRate: Int = 44_100): MockResponse =
        MockResponse().setHeader("Content-Type", "audio/mpeg").setBody(Buffer().write(mp3Bytes(seconds, sampleRate)))

    /** The same file sent in chunks, with no length: the player cannot tell how long it is. */
    private fun mp3WithoutLength(seconds: Int): MockResponse =
        MockResponse().setHeader("Content-Type", "audio/mpeg").setChunkedBody(Buffer().write(mp3Bytes(seconds, 44_100)), 16 * 1024)

    private fun mp3Bytes(seconds: Int, sampleRate: Int): ByteArray {
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
        return bytes
    }

    /**
     * An add-on's DASH manifest: one audio set of [codecs] (`flac`, or `mp4a.40.2` for AAC), 60 s. The
     * player's DASH source reads its tracks from this alone; no segment is served, and none is needed
     * before the check.
     */
    private fun mpd(codecs: String): MockResponse = MockResponse().setHeader("Content-Type", "application/dash+xml").setBody(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT60S" minBufferTime="PT2S"
             profiles="urn:mpeg:dash:profile:isoff-on-demand:2011">
          <Period id="0" start="PT0S">
            <AdaptationSet id="0" contentType="audio" mimeType="audio/mp4" lang="und">
              <Representation id="a" codecs="$codecs" bandwidth="900000" audioSamplingRate="44100">
                <AudioChannelConfiguration schemeIdUri="urn:mpeg:dash:23003:3:audio_channel_configuration:2011" value="2"/>
                <BaseURL>seg.mp4</BaseURL>
                <SegmentBase indexRange="0-99"/>
              </Representation>
            </AdaptationSet>
          </Period>
        </MPD>
        """.trimIndent(),
    )

    private fun url(path: String) = "http://127.0.0.1:${server.port}$path"

    private fun link(path: String, codec: String, origin: String, isDash: Boolean = false) =
        StreamUrl(url = url(path), expiresAtMs = System.currentTimeMillis() + 3_600_000L, codec = codec, origin = origin, isDash = isDash)

    /** A song queued as a placeholder, as the queue and the radio queue it: resolved as it plays. */
    private fun placeholderOf(id: Long): MediaItem = MediaItem.Builder().setMediaId(id.toString())
        .setUri(stashResolveUri(id, title = "Song $id", artist = "Artist", durationMs = 60_000L))
        .setMediaMetadata(
            MediaMetadata.Builder().setExtras(
                Bundle().apply {
                    putLong(EXTRA_TRACK_ID, id)
                    putLong(EXTRA_TRACK_DURATION_MS, 60_000L)
                },
            ).build(),
        )
        .build()

    /** What the repository puts back in a slot whose link must not play: the song's placeholder, its stream stamps gone. */
    private fun MediaItem.putBack(id: Long): MediaItem {
        val extras = Bundle(mediaMetadata.extras ?: Bundle()).apply {
            remove(EXTRA_STREAM_ORIGIN)
            remove(EXTRA_STREAM_CODEC)
            remove(EXTRA_STREAM_DASH)
        }
        return putBackAs(placeholderOf(id).localConfiguration!!.uri, extras)
    }

    private fun sampleRatesRead(audio: FakeRenderer) = audio.formatsRead.map { it.sampleRate }.distinct()

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

    // ------------------------------------------------------------------------------------- a DASH add-on's stream put back (review-888 M1)

    @Test fun `a refused DASH stream of the song playing is put back and plays from the next source`() {
        // Tapped, the song resolved to an add-on's manifest it called FLAC, whose only audio is AAC.
        coEvery { resolver.resolve(any(), any(), any(), any()) } returns link("/b48.mp3", codec = "mp3", origin = "qbdlx")
        val audio = FakeRenderer(C.TRACK_TYPE_AUDIO)
        val p = player(audio)
        val manifest = addonItem(12L, "/aac.mpd", codec = "flac")
        p.setMediaItem(manifest)
        p.prepare()
        p.play()

        val error = TestPlayerRunHelper.runUntilError(p)
        assertThat(AddonStreamRefusedException.isIn(error)).isTrue()
        assertThat(lines).containsExactly("liar refused for 12: lossy")
        // The slot, as the player shows it, is the manifest's own item: its MIME type rides along.
        val shown = p.currentMediaItem!!
        assertThat(shown.localConfiguration?.mimeType).isEqualTo(MimeTypes.APPLICATION_MPD)

        // What the repository does: the song's placeholder, no MIME type, prepared again.
        val putBack = shown.putBack(12L)
        assertThat(putBack.localConfiguration?.mimeType).isNull()
        p.replaceMediaItem(0, putBack)
        p.prepare()
        TestPlayerRunHelper.runUntilPlaybackState(p, Player.STATE_ENDED)

        assertThat(sampleRatesRead(audio)).containsExactly(48_000)
        assertThat(lines).hasSize(1)
    }

    @Test fun `a placeholder still carrying a manifest's MIME type plays the file it resolves to`() {
        // The factory's own guard, for an item put back by any other path that copied a manifest's item.
        coEvery { resolver.resolve(any(), any(), any(), any()) } returns link("/b48.mp3", codec = "mp3", origin = "qbdlx")
        val audio = FakeRenderer(C.TRACK_TYPE_AUDIO)
        val p = player(audio)
        p.setMediaItem(placeholderOf(13L).buildUpon().setMimeType(MimeTypes.APPLICATION_MPD).build())
        p.prepare()
        p.play()

        TestPlayerRunHelper.runUntilPlaybackState(p, Player.STATE_ENDED)

        assertThat(sampleRatesRead(audio)).containsExactly(48_000)
    }

    @Test fun `resolve-first - a next song resolved to a DASH add-on that leaves gets a new source and plays from the next source`() {
        // A Tidal server set, or a DASH add-on on: the next song's placeholder resolved before its source was
        // picked, to the add-on's manifest. Then the add-on is removed, mid-song.
        val dash = link("/flac.mpd", codec = "flac", origin = "addon:dash", isDash = true)
        val next = link("/b48.mp3", codec = "mp3", origin = "qbdlx")
        coEvery { resolver.resolve(any(), any(), any(), any()) } returnsMany listOf(dash, next)
        val audio = FakeRenderer(C.TRACK_TYPE_AUDIO)
        val p = player(audio, resolveFirst = true)
        // 60 s songs: more than the player buffers while paused, so the next one is not read ahead yet.
        p.setMediaItems(listOf(addonItem(1L, "/a.mp3", codec = "mp3"), placeholderOf(2L)))
        p.prepare()
        RobolectricUtil.runMainLooperUntil { p.getMediaItemAt(1).localConfiguration?.uri?.toString() == url("/flac.mpd") }
        // The slot shows the source's DASH child: the manifest and its MIME type, not the placeholder.
        assertThat(p.getMediaItemAt(1).localConfiguration?.mimeType).isEqualTo(MimeTypes.APPLICATION_MPD)
        assertThat(check.openedFrom(2L)).isEqualTo("addon:dash")

        // Put back as the queue's own placeholder (the resolve-first source's own URI): the player keeps that
        // source, with the departed add-on's manifest in it. Nothing resolves again.
        p.replaceMediaItem(1, p.getMediaItemAt(1).buildUpon().setUri(placeholderOf(2L).localConfiguration!!.uri).build())
        TestPlayerRunHelper.runUntilPendingCommandsAreFullyHandled(p)
        coVerify(exactly = 1) { resolver.resolve(any(), any(), any(), any()) }
        assertThat(check.openedFrom(2L)).isEqualTo("addon:dash")

        // Put back as the repository does: its links dropped, a URI no source had, no MIME type.
        cache.invalidateOrigin("addon:dash")
        cache.invalidate(2L)
        p.replaceMediaItem(1, p.getMediaItemAt(1).putBack(2L))
        RobolectricUtil.runMainLooperUntil { p.getMediaItemAt(1).localConfiguration?.uri?.scheme == STASH_RESOLVE_SCHEME && check.openedFrom(2L) == null }
        coVerify(exactly = 2) { resolver.resolve(any(), any(), any(), any()) }

        p.play()
        TestPlayerRunHelper.runUntilPlaybackState(p, Player.STATE_ENDED)
        assertThat(p.playerError).isNull()
        // The first song at 44.1 kHz, then the next source's file at 48 kHz.
        assertThat(sampleRatesRead(audio)).containsExactly(44_100, 48_000)
    }

    // ------------------------------------------------------------------------------------- an add-on that leaves while it answers (review-888 m1)

    /** The cache as the app has it, its add-ons from a registry where the add-on that answered is no longer on. */
    private fun withTheAddonGone() {
        val none = object : com.stash.core.addons.AddonRegistry {
            override val addons = kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.stash.core.addons.InstalledAddon>())
        }
        cache = StreamUrlCache(DataSaver.Off, none, gate)
    }

    private fun playsTheNextSourceWhenTheAddonLeftWhileItAnswered(resolveFirst: Boolean, answer: StreamUrl) {
        withTheAddonGone()
        // The resolve was under way when the add-on was removed: it still answers with the add-on's link.
        coEvery { resolver.resolve(any(), any(), any(), any()) } returnsMany listOf(answer, link("/b48.mp3", codec = "mp3", origin = "qbdlx"))
        val audio = FakeRenderer(C.TRACK_TYPE_AUDIO)
        val p = player(audio, resolveFirst = resolveFirst)
        p.setMediaItem(placeholderOf(14L))
        p.prepare()
        p.play()

        TestPlayerRunHelper.runUntilPlaybackState(p, Player.STATE_ENDED)

        // Asked once more, and the add-on's file (44.1 kHz) never reached the renderer.
        assertThat(sampleRatesRead(audio)).containsExactly(48_000)
        coVerify(exactly = 2) { resolver.resolve(any(), any(), any(), any()) }
        assertThat(check.openedFrom(14L)).isNull()
    }

    @Test fun `a placeholder whose resolve answered from an add-on removed meanwhile resolves once more`() =
        playsTheNextSourceWhenTheAddonLeftWhileItAnswered(resolveFirst = false, answer = link("/a.mp3", codec = "mp3", origin = "addon:gone"))

    @Test fun `resolve-first - a manifest answered by an add-on removed meanwhile is not played - the song resolves once more`() =
        // A manifest gets a DASH source of its own, which never asks the cache whether its add-on is still on.
        playsTheNextSourceWhenTheAddonLeftWhileItAnswered(
            resolveFirst = true,
            answer = link("/flac.mpd", codec = "flac", origin = "addon:gone", isDash = true),
        )

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

    @Test fun `a placeholder prepared again is judged by its new stream's length, not the last stream's - review-888 m8`() {
        coEvery { resolver.resolve(any(), any(), any(), any()) } returnsMany listOf(
            link("/short.mp3", codec = "mp3", origin = "qbdlx"), // 8 s, another source's: never judged
            link("/unsized.mp3", codec = "mp3", origin = "addon:liar"), // a whole song, sent with no length
        )
        val audio = FakeRenderer(C.TRACK_TYPE_AUDIO)
        val p = player(audio)
        p.setMediaItem(placeholderOf(15L)) // a 60 s song
        p.prepare()
        TestPlayerRunHelper.runUntilPlaybackState(p, Player.STATE_READY)

        // Prepared again from the start, as after an error: the same source, a new period, a new resolve. The
        // source still says 8 s, the last stream's length; the add-on's stream is not a stub for that.
        cache.invalidate(15L)
        p.stop()
        p.prepare()
        p.play()
        TestPlayerRunHelper.runUntilPlaybackState(p, Player.STATE_ENDED)

        assertThat(lines).isEmpty()
        assertThat(gate.isDegraded("addon:liar")).isFalse()
        coVerify(exactly = 2) { resolver.resolve(any(), any(), any(), any()) }
    }

    // #888 phone run 3b: "Feel Good Inc." tapped in Search, its result with no length; the liar's 10 s file played.

    @Test fun `a tapped song with no length of its own is judged by the length its stream's source knew`() {
        // The tap resolved to the add-on's URL; the rung stamped the song's length it knew (the add-on's matched item's).
        val audio = FakeRenderer(C.TRACK_TYPE_AUDIO)
        val p = player(audio)
        val tapped = addonItem(16L, "/short.mp3", codec = "mp3", songMs = null).let { item ->
            item.buildUpon().setMediaMetadata(
                item.mediaMetadata.buildUpon().setExtras(Bundle(item.mediaMetadata.extras).apply { putLong(EXTRA_STREAM_SONG_MS, 222_640L) }).build(),
            ).build()
        }
        p.setMediaItem(tapped)
        p.prepare()
        p.play()

        val error = TestPlayerRunHelper.runUntilError(p)

        assertThat(AddonStreamRefusedException.isIn(error)).isTrue()
        assertThat(lines).containsExactly("liar refused for 16: length")
        assertThat(audio.sampleBufferReadCount).isEqualTo(0)
    }

    @Test fun `a radio placeholder with no length is judged by the length its resolve knew`() {
        coEvery { resolver.resolve(any(), any(), any(), any()) } returns
            link("/short.mp3", codec = "mp3", origin = "addon:liar").copy(songMs = 222_640L)
        val audio = FakeRenderer(C.TRACK_TYPE_AUDIO)
        val p = player(audio)
        p.setMediaItem(
            MediaItem.Builder().setMediaId("17").setUri(stashResolveUri(17L, title = "Feel Good Inc.", artist = "Gorillaz"))
                .setMediaMetadata(MediaMetadata.Builder().setExtras(Bundle().apply { putLong(EXTRA_TRACK_ID, 17L) }).build())
                .build(),
        )
        p.prepare()
        p.play()

        val error = TestPlayerRunHelper.runUntilError(p)

        assertThat(AddonStreamRefusedException.isIn(error)).isTrue()
        assertThat(lines).containsExactly("liar refused for 17: length")
        assertThat(audio.sampleBufferReadCount).isEqualTo(0)
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

    @Test fun `the spare follows its slot - a song put back is primed again, new metadata or its own URL swapped in is not`() {
        coEvery { resolver.resolve(any(), any(), any(), any()) } returns null // a placeholder spare need not open here
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

        // Primed on the placeholder, the spare is kept when the look-ahead swaps in the URL it resolved to
        // (review-888 m2: priming it again fetched the same file from its first byte, near the seam).
        engine.prepareNext(stashPlaceholder(honest))
        assertThat(engine.isPreparedFor(honest)).isTrue()
        // Primed on a put-back, it is kept on that and on what the look-ahead swaps in, until the slot is put back again.
        engine.prepareNext(putBack)
        assertThat(engine.isPreparedFor(putBack)).isTrue()
        assertThat(engine.isPreparedFor(honest)).isTrue()
        assertThat(engine.isPreparedFor(honest.buildUpon().setUri(stashResolveUri(3L).resolvedAgain()).build())).isFalse()
        // Another song is another song.
        assertThat(engine.isPreparedFor(addonItem(4L, "/a.mp3", codec = "mp3"))).isFalse()

        // Another song's refusal leaves this spare alone.
        assertThat(engine.dropSpareFor("4")).isFalse()
        assertThat(built[1].player.mediaItemCount).isEqualTo(1)
    }

    private fun stashPlaceholder(item: MediaItem) = item.buildUpon().setUri(stashResolveUri(item.mediaId.toLong())).build()

    /**
     * review-888 R2-M1: song 1 paused in its last 90 s, the spare primed on song 3 from the add-on. The
     * add-on is removed: song 3's slot is put back, and the look-ahead fills it with another source's
     * URL before the poll (which runs only while playing) looks. The spare held the removed add-on's
     * stream, and the fade played it.
     */
    @Test fun `the spare goes when its slot is put back, even if the look-ahead fills the slot again before the poll looks`() {
        coEvery { resolver.resolve(any(), any(), any(), any()) } returns null // the put-back placeholder isn't opened here
        val built = mutableListOf<Spare>()
        val engine = engine(built)
        val master = engine.masterPlayer
        val fromX = addonItem(3L, "/a.mp3", codec = "mp3")
        master.setMediaItems(listOf(MediaItem.Builder().setMediaId("1").setUri(url("/a.mp3")).build(), fromX))
        engine.prepareNext(fromX)
        assertThat(engine.isPreparedFor(master.getMediaItemAt(1))).isTrue()

        // X leaves: the slot is put back, then the look-ahead swaps in Y's URL for the same song. No poll ran between.
        master.replaceMediaItem(1, fromX.buildUpon().setUri(stashResolveUri(3L).resolvedAgain()).build())
        val fromY = fromX.buildUpon().setUri(url("/b48.mp3")).setMediaMetadata(
            fromX.mediaMetadata.buildUpon().setExtras(Bundle(fromX.mediaMetadata.extras).apply { putString(EXTRA_STREAM_ORIGIN, "qbdlx") }).build(),
        ).build()
        master.replaceMediaItem(1, fromY)
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(built[1].player.mediaItemCount).isEqualTo(0)
        assertThat(engine.isPreparedFor(master.getMediaItemAt(1))).isFalse()

        // Primed again on Y's URL, it stays through what doesn't change the stream: new metadata, its own URL.
        engine.prepareNext(master.getMediaItemAt(1))
        master.replaceMediaItem(1, fromY.buildUpon().setMediaMetadata(fromY.mediaMetadata.buildUpon().setTitle("Stamped").build()).build())
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(engine.isPreparedFor(master.getMediaItemAt(1))).isTrue()
        // R2-n2: the slot became the song's downloaded file; the fade must play the file, not the spare's stream.
        assertThat(engine.isPreparedFor(fromY.buildUpon().setUri("file:///music/3.flac").build())).isFalse()
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
