package com.stash.core.media.streaming

import android.content.Context
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.ExoPlayerTestRunner
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.FakeMediaSource
import androidx.media3.test.utils.FakeRenderer
import androidx.media3.test.utils.FakeTimeline
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.RobolectricUtil
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.media.service.CrossfadeEngine
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_DASH
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_ORIGIN
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_ID
import com.stash.core.media.service.isResolvedForCrossfade
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #880: with Tidal on (or an add-on that answers DASH), each streamed song is a `stash-resolve://`
 * placeholder the player resolves itself ([ResolveFirstMediaSource]). Once it has resolved the NEXT
 * song, reading ahead into it, the player shows that slot as its DASH child: the manifest's URL. That
 * item said nothing of the stream, so the crossfade's gate ([isResolvedForCrossfade]) took it for an
 * unresolved queue-fill URL, every 250 ms to the end of the song: no fade, a plain cut (three of three
 * song changes on the Pixel 5, 11-13). The child's item now carries the stream's stamp ([StreamStamp]).
 *
 * Real players on the player's own [StashMediaSourceFactory]; only the DASH source is a stand-in,
 * which shows its slot as [StreamingMediaSourceFactory.createDashMediaSource] does: the item it was
 * given, with the manifest's URL and MIME type.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ResolvedManifestCrossfadeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Song 1 fades out at its end; 2 fades in; 3 comes after. */
    private val lengthsUs = mapOf("1" to 20_000_000L, "2" to 60_000_000L, "3" to 60_000_000L)

    private fun tidalManifest(id: Long) = "https://relay.test/dash/td/$id.mpd"
    private fun addonManifest(id: Long) = "https://addon.test/dash?id=$id" // an add-on's manifest can sit at any path

    private fun tidal(id: Long) = StreamUrl(
        tidalManifest(id), System.currentTimeMillis() + 600_000L,
        codec = "flac", bitsPerSample = 24, sampleRateHz = 48_000, origin = TidalStreamResolver.ORIGIN, isDash = true,
    )

    private fun addon(id: Long) = StreamUrl(addonManifest(id), System.currentTimeMillis() + 600_000L, codec = "flac", origin = ADDON, isDash = true)

    /** A queue song as the queue builds it: its placeholder, which the player resolves itself. */
    private fun song(id: Long) = MediaItem.Builder()
        .setMediaId("$id")
        .setUri("stash-resolve://track/$id")
        .setMediaMetadata(MediaMetadata.Builder().setTitle("Song $id").setExtras(Bundle().apply { putLong(EXTRA_TRACK_ID, id) }).build())
        .build()

    private data class DashSource(val url: String, val onAddonClient: Boolean)

    /** Every DASH source made: for which manifest, and on which client. */
    private val dashSources = mutableListOf<DashSource>()

    private val streaming: StreamingMediaSourceFactory = mockk(relaxed = true) {
        every { createDashMediaSource(any(), any(), any()) } answers {
            val item = firstArg<MediaItem>()
            val url = secondArg<String>()
            dashSources += DashSource(url, onAddonClient = thirdArg<DataSource.Factory?>() != null)
            val shown = item.buildUpon().setUri(url).setMimeType(MimeTypes.APPLICATION_MPD).build()
            FakeMediaSource(
                FakeTimeline(
                    FakeTimeline.TimelineWindowDefinition.Builder()
                        .setDurationUs(lengthsUs.getValue(item.mediaId)).setMediaItem(shown).build(),
                ),
                ExoPlayerTestRunner.AUDIO_FORMAT,
            )
        }
    }

    /** What the resolver found: each song's resolve takes it from here. */
    private val cache = StreamUrlCache()
    private val dao: TrackDao = mockk { coEvery { getById(any()) } returns null }

    /** The player's factory as the service builds it while Tidal is on: placeholders resolve before their source is picked. */
    private val factory = StashMediaSourceFactory(
        context = context,
        streamingFactory = streaming,
        streamingTrackId = { null },
        isJioSaavnOrigin = { false },
        httpClient = OkHttpClient(),
        resolver = mockk(relaxed = true),
        urlCache = cache,
        trackDao = dao,
        resolveBeforeSourcePick = { true },
        resolveDispatcher = Dispatchers.Unconfined,
        addonDataSourceFactory = DataSource.Factory { mockk(relaxed = true) },
        isAddonOrigin = { AddonPlayback.isAddonOrigin(it.mediaMetadata.extras?.getString(EXTRA_STREAM_ORIGIN)) },
        isDashStream = { it.mediaMetadata.extras?.getBoolean(EXTRA_STREAM_DASH) == true },
    )

    private val clock = FakeClock(/* isAutoAdvancing= */ true)
    private val players = mutableListOf<ExoPlayer>()

    private fun player(): ExoPlayer =
        TestExoPlayerBuilder(context).setClock(clock).setRenderers(FakeRenderer(C.TRACK_TYPE_AUDIO))
            .setMediaSourceFactory(factory).build().also { players += it }

    @After fun release() = players.forEach { it.release() }

    /**
     * [master] playing song 1 near its end, having resolved song 2 itself: the slot shows its DASH
     * child, the manifest's URL. Returns that slot's item, as the crossfade's poll reads it.
     */
    private fun nextSongResolvedAhead(master: ExoPlayer, manifest: String): MediaItem {
        master.setMediaItems((1L..3L).map(::song))
        master.prepare()
        TestPlayerRunHelper.playUntilPosition(master, 0, 14_000)
        RobolectricUtil.runMainLooperUntil { master.getMediaItemAt(1).localConfiguration?.uri?.toString() == manifest }
        return master.getMediaItemAt(master.nextMediaItemIndex)
    }

    @Test fun `a next song the player resolved itself to Tidal's manifest is one the crossfade fades into`() {
        (1L..3L).forEach { cache.put(it, tidal(it)) }
        val next = nextSongResolvedAhead(player(), tidalManifest(2))

        assertThat(isResolvedForCrossfade(next) { false }).isTrue()
        // What it plays, as the look-ahead says it of a slot it swaps to a URL.
        assertThat(next.mediaMetadata.extras?.getString(EXTRA_STREAM_ORIGIN)).isEqualTo(TidalStreamResolver.ORIGIN)
        assertThat(next.mediaMetadata.extras?.getBoolean(EXTRA_STREAM_DASH)).isTrue()
    }

    @Test fun `the fade into it starts, and the next song plays on from where the fade had it`() = runTest {
        (1L..3L).forEach { cache.put(it, tidal(it)) }
        val master = player()
        val spare = player()
        val engine = CrossfadeEngine(context, ArrayDeque(listOf(master, spare))::removeFirst, backgroundScope)
            .also { it.initialize() }
        val next = nextSongResolvedAhead(master, tidalManifest(2))

        // StashPlaybackService.evaluateCrossfade: the spare is primed on the next slot, and the fade fired,
        // only when the gate calls that slot resolved.
        var swapped: ExoPlayer? = null
        if (isResolvedForCrossfade(next) { false }) {
            engine.prepareNext(next)
            TestPlayerRunHelper.run(spare).untilState(Player.STATE_READY)
            engine.performTransition(fadeMs = 5_000L) { swapped = it }
        }
        runCurrent()
        val twoAt = spare.currentPosition
        advanceTimeBy(10_000)
        runCurrent()
        TestPlayerRunHelper.run(master).untilPendingCommandsAreFullyHandled()
        TestPlayerRunHelper.run(spare).untilPendingCommandsAreFullyHandled()

        assertThat(swapped).isSameInstanceAs(spare)
        assertThat(engine.masterPlayer.currentMediaItem?.mediaId).isEqualTo("2")
        assertThat(engine.masterPlayer.currentPosition).isAtLeast(twoAt)
        // The spare read song 2's manifest as the slot's own child did: Tidal's, on the default HTTP client.
        val twos = dashSources.filter { it.url == tidalManifest(2) }
        assertThat(twos.size).isAtLeast(2)
        assertThat(twos.none { it.onAddonClient }).isTrue()
    }

    @Test fun `an add-on's manifest resolved ahead is faded into, and not while that add-on is set aside`() {
        cache.put(1L, tidal(1L))
        cache.put(2L, addon(2L))
        cache.put(3L, tidal(3L))
        val next = nextSongResolvedAhead(player(), addonManifest(2))

        assertThat(isResolvedForCrossfade(next) { false }).isTrue()
        assertThat(isResolvedForCrossfade(next) { it == ADDON }).isFalse()
    }

    @Test fun `the spare primed on an add-on's manifest resolved ahead reads it on the add-on client`() {
        cache.put(1L, tidal(1L))
        cache.put(2L, addon(2L))
        cache.put(3L, tidal(3L))
        val next = nextSongResolvedAhead(player(), addonManifest(2))
        dashSources.clear()

        // The crossfade's spare (or the new master after a fade) builds its source from the slot's item as the player shows it.
        factory.createMediaSource(next)

        // Never the default HTTP source, which would fetch the add-on's manifest and segments from the user's own network.
        assertThat(dashSources).containsExactly(DashSource(addonManifest(2), onAddonClient = true))
    }

    private companion object {
        const val ADDON = "addon:stranger"
    }
}
