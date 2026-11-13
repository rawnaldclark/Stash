package com.stash.core.media

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.TrackIdentityEvents
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_CODEC
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_ORIGIN
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_ID
import com.stash.core.media.streaming.AddonStreamCheck
import com.stash.core.media.streaming.AddonStreamRefusedException
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.media.streaming.STASH_RESOLVE_SCHEME
import com.stash.core.media.streaming.StreamSourceRegistry
import com.stash.core.media.streaming.StreamUrl
import com.stash.core.media.streaming.StreamUrlCache
import com.stash.core.media.streaming.isPutBack
import com.stash.core.media.streaming.stashResolveUri
import com.stash.core.model.Track
import com.stash.data.download.lossless.LosslessSourceHealthGate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Every queued song whose link came from an add-on that must not play any more is resolved again:
 * one that left (removed, switched off, turned off by Stash) and one the player's stream check set
 * aside. One path serves both ([PlayerRepositoryImpl.resolveAgainLinksFrom]).
 *
 * #885 item 281 (Pixel 5, 2026-11-13): an add-on removed during a song, and the next TWO songs,
 * which the autoplay radio had queued as placeholders and the player had already read ahead from
 * the add-on, still played it. Only the next slot was looked at, and only for an origin a
 * placeholder never has.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerRepositoryAddonLinksTest {

    private val playbackStateStore: PlaybackStateStore = mockk(relaxed = true)
    private val musicRepository: MusicRepository = mockk { every { trackDeletions } returns MutableSharedFlow() }
    private val streamingPreference: StreamingPreference = mockk(relaxed = true) { coEvery { current() } returns true }
    private val streamResolver: StreamSourceRegistry = mockk()
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val controller: MediaController = mockk(relaxed = true)
    private val trackIdentityEvents: TrackIdentityEvents = mockk { every { changes } returns MutableSharedFlow() }
    private val gate = LosslessSourceHealthGate()
    private val check = AddonStreamCheck(gate).apply { log = {} }
    private val cache = StreamUrlCache()
    private lateinit var repo: PlayerRepositoryImpl

    private val playing = Track(id = 1L, title = "Playing", artist = "Artist")
    private val second = Track(id = 2L, title = "Radio two", artist = "Artist", durationMs = 200_000L, isStreamable = true)
    private val third = Track(id = 3L, title = "Radio three", artist = "Artist", durationMs = 210_000L, isStreamable = true)
    private val newUrl = "https://cdn.example/two.flac"

    /** The player's queue: index 0 plays, 1 is next, 2 after it. Every replace lands here, in order in [swaps]. */
    private val slots = mutableListOf<MediaItem>()
    private val swaps = mutableListOf<Pair<Int, MediaItem>>()

    private fun addon(id: String) = com.stash.core.addons.InstalledAddon(
        base = "https://$id.example/",
        manifest = com.stash.addons.contract.Manifest(
            stash = 1, id = id, name = id, version = "1", kind = com.stash.addons.contract.AddonKind.SERVER, resources = listOf("search", "stream"),
        ),
        userAdded = true,
    )

    /** A slot the autoplay radio queued: a placeholder, no origin of its own. */
    private fun radioSlot(track: Track) = MediaItem.Builder()
        .setMediaId(track.id.toString())
        .setUri(stashResolveUri(track.id, title = track.title, artist = track.artist, durationMs = track.durationMs))
        .setMediaMetadata(MediaMetadata.Builder().setExtras(Bundle().apply { putLong(EXTRA_TRACK_ID, track.id) }).build())
        .build()

    /** A slot holding a URL [origin] handed out, stamped as the look-ahead and a tap stamp it. */
    private fun urlSlot(id: Long, origin: String) = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri("https://media.${origin.replace(":", "-")}.example/$id.flac")
        .setMediaMetadata(
            MediaMetadata.Builder().setExtras(
                Bundle().apply {
                    putLong(EXTRA_TRACK_ID, id)
                    putString(EXTRA_STREAM_ORIGIN, origin)
                    putString(EXTRA_STREAM_CODEC, "flac")
                },
            ).build(),
        )
        .build()

    /**
     * A slot as the player shows a DASH stream: the manifest's own item, its MPD and MIME type. With
     * [origin] it is an add-on's item resolved to its manifest; without, a resolve-first source's
     * DASH child (its own URI is the placeholder, which the slot does not show).
     */
    private fun manifestSlot(id: Long, origin: String?) = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri("https://media.example/$id/manifest.mpd")
        .setMimeType(MimeTypes.APPLICATION_MPD)
        .setMediaMetadata(
            MediaMetadata.Builder().setExtras(
                Bundle().apply {
                    putLong(EXTRA_TRACK_ID, id)
                    origin?.let {
                        putString(EXTRA_STREAM_ORIGIN, it)
                        putString(EXTRA_STREAM_CODEC, "flac")
                    }
                },
            ).build(),
        )
        .build()

    private fun link(origin: String, url: String = "https://media.example/x.flac") =
        StreamUrl(url = url, expiresAtMs = System.currentTimeMillis() + 3_600_000L, codec = "flac", origin = origin)

    /** Lets the watcher run: a resolve may take it off the test's scheduler for a moment. */
    private fun TestScope.settle(rounds: Int = 200, until: () -> Boolean = { false }) {
        for (round in 0 until rounds) {
            testScheduler.runCurrent()
            if (until()) return
            Thread.sleep(5)
        }
    }

    /** The repository over [urlCache], wired to the test's controller. */
    private fun repoOver(urlCache: StreamUrlCache) = PlayerRepositoryImpl(
        context = ApplicationProvider.getApplicationContext(),
        playbackStateStore = playbackStateStore,
        musicRepository = musicRepository,
        streamingPreference = streamingPreference,
        streamResolver = streamResolver,
        streamUrlCache = urlCache,
        connectivity = mockk<ConnectivityMonitor>(relaxed = true),
        trackDao = trackDao,
        playbackResumer = PlaybackResumer(playbackStateStore, trackDao),
        radioGenerator = mockk(relaxed = true),
        trackIdentityEvents = trackIdentityEvents,
        playbackSessionBus = PlaybackSessionBus(),
        addonStreamCheck = check,
    ).also {
        it.controllerDeferred = controller
        it.currentQueueTracks = listOf(playing, second, third)
    }

    @Before
    fun setUp() {
        every { controller.isConnected } returns true
        repo = repoOver(cache)
        every { controller.currentMediaItemIndex } returns 0
        every { controller.nextMediaItemIndex } returns 1
        every { controller.mediaItemCount } answers { slots.size }
        every { controller.getMediaItemAt(any()) } answers { slots[firstArg()] }
        every { controller.currentMediaItem } answers { slots[0] }
        every { controller.replaceMediaItem(any(), any()) } answers {
            swaps += firstArg<Int>() to secondArg<MediaItem>()
            slots[firstArg()] = secondArg()
        }
        coEvery { trackDao.getById(any()) } returns null
        coEvery { streamResolver.resolve(any(), allowYouTube = true, allowYtDlp = true, speculative = true) } returns
            StreamUrl(url = newUrl, expiresAtMs = Long.MAX_VALUE, codec = "flac", origin = "qbdlx")
    }

    // ------------------------------------------------------------------------------------- an add-on leaves

    @Test
    fun `item 281 - the radio's next two songs, read ahead from an add-on that is removed, are both resolved again`() = runTest {
        // The song playing came from the add-on; the radio queued the next two as placeholders, and the
        // player opened both from the same add-on while reading ahead (32 s files: two fit in its buffer).
        slots += listOf(urlSlot(1L, "addon:radio"), radioSlot(second), radioSlot(third))
        check.opened(2L, "addon:radio")
        check.opened(3L, "addon:radio")
        cache.put(2L, link("addon:radio"))
        cache.put(3L, link("addon:radio"))
        val on = MutableStateFlow(listOf(addon("radio")))
        val watching = launch { repo.resolveAgainWhenAnAddonLeaves(on) }
        settle(rounds = 5)
        assertThat(swaps).isEmpty()

        on.value = emptyList() // removed, mid-song
        settle { swaps.size >= 3 }
        watching.cancel()

        // Both slots were put back as placeholders the player hasn't seen (so it builds new sources,
        // dropping what it read ahead), without the add-on's stamps; then the next one was looked up again.
        val putBack = swaps.filter { it.second.localConfiguration?.uri?.scheme == STASH_RESOLVE_SCHEME }
        assertThat(putBack.map { it.first }).containsExactly(1, 2)
        assertThat(putBack[0].second.localConfiguration?.uri).isNotEqualTo(radioSlot(second).localConfiguration?.uri)
        assertThat(putBack[1].second.localConfiguration?.uri).isNotEqualTo(radioSlot(third).localConfiguration?.uri)
        assertThat(putBack[1].second.localConfiguration?.uri?.getQueryParameter("d")).isEqualTo("210000")
        assertThat(slots[1].localConfiguration?.uri?.toString()).isEqualTo(newUrl)
        coVerify(exactly = 1) { streamResolver.resolve(match { it.id == 2L }, allowYouTube = true, allowYtDlp = true, speculative = true) }
        // Its links are out of the cache, and its notes are gone; the song playing plays on.
        assertThat(cache.get(3L)).isNull()
        assertThat(check.openedFrom(2L)).isNull()
        assertThat(check.openedFrom(3L)).isNull()
        assertThat(swaps.none { it.first == 0 }).isTrue()
    }

    @Test
    fun `a link the add-on handed out further down the queue is resolved again too, and another source's next song is left alone`() = runTest {
        slots += listOf(urlSlot(1L, "qbdlx"), urlSlot(2L, "qbdlx"), urlSlot(3L, "addon:gone"))
        val on = MutableStateFlow(listOf(addon("gone")))
        val watching = launch { repo.resolveAgainWhenAnAddonLeaves(on) }
        settle(rounds = 5)
        on.value = emptyList()
        settle { swaps.isNotEmpty() }
        settle(rounds = 10)
        watching.cancel()

        assertThat(swaps.map { it.first }).containsExactly(2)
        assertThat(slots[2].localConfiguration?.uri?.scheme).isEqualTo(STASH_RESOLVE_SCHEME)
        assertThat(slots[2].mediaMetadata.extras?.getString(EXTRA_STREAM_ORIGIN)).isNull()
        assertThat(slots[2].mediaMetadata.extras?.getString(EXTRA_STREAM_CODEC)).isNull()
        // The next song came from elsewhere: nothing to look up again.
        coVerify(exactly = 0) { streamResolver.resolve(any(), any(), any(), any()) }
    }

    // ------------------------------------------------------------------------------------- an add-on is set aside

    @Test
    fun `a refused stream read ahead two songs deep is put back, with every slot of that add-on, and nothing of another`() = runTest {
        slots += listOf(urlSlot(1L, "qbdlx"), urlSlot(2L, "addon:other"), radioSlot(third))
        check.opened(3L, "addon:liar")
        cache.put(3L, link("addon:liar"))

        repo.resolveAgainWhenAStreamIsRefused(flowOf(AddonStreamCheck.Refused("addon:liar", 3L, "3", AddonStreamCheck.Why.LOSSY)))

        assertThat(swaps.map { it.first }).containsExactly(2)
        assertThat(slots[2].localConfiguration?.uri?.scheme).isEqualTo(STASH_RESOLVE_SCHEME)
        assertThat(cache.get(3L)).isNull()
        // Put back before the player reached it: no error will come for it.
        assertThat(check.takeRefusal(3L)).isFalse()
    }

    @Test
    fun `a refused stream in the next slot is put back and looked up again from the next source`() = runTest {
        slots += listOf(urlSlot(1L, "qbdlx"), urlSlot(2L, "addon:liar"))

        repo.resolveAgainWhenAStreamIsRefused(flowOf(AddonStreamCheck.Refused("addon:liar", 2L, "2", AddonStreamCheck.Why.VIDEO)))

        assertThat(swaps.map { it.first }).containsExactly(1, 1).inOrder()
        assertThat(swaps[0].second.localConfiguration?.uri?.scheme).isEqualTo(STASH_RESOLVE_SCHEME)
        assertThat(slots[1].localConfiguration?.uri?.toString()).isEqualTo(newUrl)
    }

    @Test
    fun `a second refusal of the same read-ahead leaves the slot's new answer alone - review-888 m3`() = runTest {
        // The player read the next song ahead from the add-on, and so did the crossfade spare: two refusals.
        slots += listOf(urlSlot(1L, "qbdlx"), radioSlot(second))
        check.opened(2L, "addon:liar")
        val refused = AddonStreamCheck.Refused("addon:liar", 2L, "2", AddonStreamCheck.Why.LOSSY)

        repo.resolveAgainWhenAStreamIsRefused(flowOf(refused, refused))

        // Put back once, the next source's link swapped in; the second finds nothing of the add-on in the slot.
        assertThat(swaps.map { it.first }).containsExactly(1, 1).inOrder()
        assertThat(slots[1].localConfiguration?.uri?.toString()).isEqualTo(newUrl)
        coVerify(exactly = 1) { streamResolver.resolve(any(), any(), any(), any()) }
    }

    @Test
    fun `a second refusal of the same stream whose open noted itself after the first put-back is handled once - review-888 R2-n1`() = runTest {
        slots += listOf(urlSlot(1L, "qbdlx"), radioSlot(second))
        check.opened(2L, "addon:liar")
        val refused = AddonStreamCheck.Refused("addon:liar", 2L, "2", AddonStreamCheck.Why.LOSSY)

        repo.resolveAgainWhenAStreamIsRefused(
            kotlinx.coroutines.flow.flow {
                emit(refused)
                check.opened(2L, "addon:liar") // the spare's open of the same stream returned just now
                emit(refused)
            },
        )

        assertThat(swaps.map { it.first }).containsExactly(1, 1).inOrder()
        assertThat(slots[1].localConfiguration?.uri?.toString()).isEqualTo(newUrl)
        coVerify(exactly = 1) { streamResolver.resolve(any(), any(), any(), any()) }
    }

    // ------------------------------------------------------------------------------------- a manifest's slot (review-888 M1)

    @Test
    fun `a slot showing a DASH add-on's manifest is put back with no MIME type and a URI its source never had`() = runTest {
        // Resolve-first (a Tidal server set, a DASH add-on on): the slot shows the source's DASH child, with no origin
        // of its own; the source's own URI is the queue's placeholder. The add-on is removed.
        slots += listOf(urlSlot(1L, "qbdlx"), manifestSlot(2L, origin = null))
        check.opened(2L, "addon:dash")
        val on = MutableStateFlow(listOf(addon("dash")))
        val watching = launch { repo.resolveAgainWhenAnAddonLeaves(on) }
        settle(rounds = 5)
        on.value = emptyList()
        settle { swaps.size >= 2 }
        watching.cancel()

        val (index, putBack) = swaps.first()
        assertThat(index).isEqualTo(1)
        // Not the queue's placeholder, which the source would keep (with the add-on's manifest in it)...
        assertThat(putBack.localConfiguration?.uri?.isPutBack()).isTrue()
        assertThat(putBack.localConfiguration?.uri).isNotEqualTo(radioSlot(second).localConfiguration?.uri)
        // ...and no manifest type, which would make the next source's file read as a manifest.
        assertThat(putBack.localConfiguration?.mimeType).isNull()
        assertThat(putBack.mediaMetadata.extras?.getLong(EXTRA_TRACK_ID)).isEqualTo(2L)
        // The look-ahead's answer, a file, carries none either.
        assertThat(slots[1].localConfiguration?.uri?.toString()).isEqualTo(newUrl)
        assertThat(slots[1].localConfiguration?.mimeType).isNull()
    }

    // ------------------------------------------------------------------------------------- the song playing is refused

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    /** Where the refused song stood: a session resumed at 2:10, say. */
    private val resumedAtMs = 130_000L

    /**
     * The song playing (song 1, [slot]) fails with [cause]. [refused]: its stream was refused, which the
     * check records before the player's error is raised (AddonCheckedMediaSource).
     */
    private fun refusalOfPlaying(cause: Throwable?, refused: Boolean = true, slot: MediaItem = urlSlot(1L, "addon:liar")) {
        slots += listOf(slot, urlSlot(2L, "qbdlx"))
        every { controller.currentPosition } returns resumedAtMs
        if (refused) check.judge(AddonStreamCheck.Claim("addon:liar", 1L, "1", statedLossless = true, songMs = 0L), listOf("audio/mpeg"), null)
        repo.playerListener.onPlayerError(PlaybackException("Source error", cause, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
        idleMain()
    }

    private fun assertResolvedAgainNotRetried() {
        assertThat(swaps.map { it.first }).containsExactly(0)
        val placeholder = swaps.single().second
        assertThat(placeholder.localConfiguration?.uri?.scheme).isEqualTo(STASH_RESOLVE_SCHEME)
        assertThat(placeholder.localConfiguration?.mimeType).isNull()
        assertThat(placeholder.mediaMetadata.extras?.getString(EXTRA_STREAM_ORIGIN)).isNull()
        verify { controller.prepare() }
        // From where it was, on the next source (review-888 m5): the slot was put back first, so this is not
        // the cascade guard's same-link retry. Nor a skip.
        verifyOrder {
            controller.replaceMediaItem(0, any())
            controller.seekTo(0, resumedAtMs)
            controller.prepare()
        }
        verify(exactly = 0) { controller.seekToNextMediaItem() }
        assertThat(repo.streamingHaltedEvents.replayCache).isEmpty()
    }

    @Test
    fun `the song playing whose add-on stream was refused is resolved again from where it was, not retried on the same link`() {
        refusalOfPlaying(AddonStreamRefusedException(AddonStreamCheck.Why.LOSSY))
        assertResolvedAgainNotRetried()
    }

    @Test
    fun `the refusal is known when the session sends the cause on as another exception`() {
        refusalOfPlaying(android.os.RemoteException(AddonStreamRefusedException(AddonStreamCheck.Why.LENGTH).message))
        assertResolvedAgainNotRetried()
    }

    @Test
    fun `the refusal is known by the check's record when the session sends no cause at all`() {
        refusalOfPlaying(cause = null)
        assertResolvedAgainNotRetried()
    }

    @Test
    fun `a refused DASH stream of the song playing is put back with no manifest type - review-888 M1`() {
        refusalOfPlaying(AddonStreamRefusedException(AddonStreamCheck.Why.LOSSY), slot = manifestSlot(1L, origin = "addon:liar"))
        assertResolvedAgainNotRetried()
        assertThat(swaps.single().second.localConfiguration?.uri?.isPutBack()).isTrue()
    }

    @Test
    fun `any other error of an add-on stream still goes to the cascade guard`() {
        refusalOfPlaying(java.io.IOException("connection reset"), refused = false)
        assertThat(swaps).isEmpty()
        verify { controller.seekTo(0, any<Long>()) } // its one same-item retry
    }

    @Test
    fun `a refusal of another song - the user skipped before the error came - plays the song shown as it is - review-888 m9`() {
        // Song 9's stream was refused; the controller already shows song 1, which the user picked.
        slots += listOf(urlSlot(1L, "qbdlx"), urlSlot(2L, "qbdlx"))
        check.judge(AddonStreamCheck.Claim("addon:liar", 9L, "9", statedLossless = true, songMs = 0L), listOf("audio/mpeg"), null)
        repo.playerListener.onPlayerError(
            PlaybackException("Source error", AddonStreamRefusedException(AddonStreamCheck.Why.LOSSY), PlaybackException.ERROR_CODE_IO_UNSPECIFIED),
        )
        idleMain()

        assertThat(swaps).isEmpty()
        verify { controller.prepare() }
        verify(exactly = 0) { controller.seekTo(any<Int>(), any<Long>()) }
        verify(exactly = 0) { controller.seekToNextMediaItem() }
        assertThat(repo.streamingHaltedEvents.replayCache).isEmpty()
    }

    @Test
    fun `a refusal whose song the player moved off leaves the player playing what it shows - review-888 m4`() {
        // Song 2 (index 1) was refused, but a queue edit moved the player to index 0 before the put-back ran.
        slots += listOf(urlSlot(1L, "qbdlx"), urlSlot(2L, "addon:liar"))
        every { controller.playbackState } returns Player.STATE_IDLE
        every { controller.playerError } returns PlaybackException("refused", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)

        repo.resolveAgainAfterRefusal(controller, slots[1], failedIndex = 1, trackId = 2L)

        assertThat(swaps).isEmpty()
        verify { controller.prepare() }
    }

    // ------------------------------------------------------------------------------------- the song playing when its add-on leaves (R2-m3)

    /** The add-ons that are on, as the app's registry has them; the cache serves an add-on's links only while it is among them. */
    private val on = MutableStateFlow(listOf(addon("gone")))

    /** The repository over a cache that knows which add-ons are on, as the app's does; song 1 plays from add-on "gone". */
    private fun TestScope.playingFromAnAddonThatIsThenRemoved() {
        val registry = object : com.stash.core.addons.AddonRegistry { override val addons = on }
        repo = repoOver(StreamUrlCache(com.stash.core.data.prefs.DataSaver.Off, registry, gate))
        idleMain()
        repo.controllerDeferred = controller // the session bus's initial "not alive" released the seam
        slots += listOf(urlSlot(1L, "addon:gone"), urlSlot(2L, "qbdlx"), urlSlot(3L, "qbdlx"))
        val watching = launch { repo.resolveAgainWhenAnAddonLeaves(on) }
        settle(rounds = 5)
        on.value = emptyList() // removed, mid-song
        settle(rounds = 10)
        watching.cancel()
    }

    @Test
    fun `the song playing when its add-on is removed plays on, and its slot is put back once the player moves off it`() = runTest {
        playingFromAnAddonThatIsThenRemoved()
        assertThat(swaps).isEmpty() // not interrupted

        // The song ends; the next one plays. Previous, repeat-all or a tap would now play the slot again.
        every { controller.currentMediaItemIndex } returns 1
        every { controller.currentMediaItem } answers { slots[1] }
        repo.playerListener.onMediaItemTransition(slots[1], Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        idleMain()

        assertThat(swaps.map { it.first }).containsExactly(0)
        val putBack = slots[0]
        assertThat(putBack.localConfiguration?.uri?.isPutBack()).isTrue()
        assertThat(putBack.mediaMetadata.extras?.getString(EXTRA_STREAM_ORIGIN)).isNull()
        // Nothing interrupted: the song playing now is left alone.
        verify(exactly = 0) { controller.prepare() }
        verify(exactly = 0) { controller.seekTo(any<Int>(), any<Long>()) }
    }

    @Test
    fun `the song playing when its add-on is removed, repeated, resolves again from its start`() = runTest {
        playingFromAnAddonThatIsThenRemoved()
        every { controller.currentPosition } returns 0L
        // A metadata change of the song playing is not leaving it.
        repo.playerListener.onMediaItemTransition(slots[0], Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        idleMain()
        assertThat(swaps).isEmpty()

        repo.playerListener.onMediaItemTransition(slots[0], Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
        idleMain()

        assertThat(swaps.map { it.first }).containsExactly(0)
        assertThat(slots[0].localConfiguration?.uri?.isPutBack()).isTrue()
        verifyOrder {
            controller.replaceMediaItem(0, any())
            controller.seekTo(0, 0L)
            controller.prepare()
        }
    }

    @Test
    fun `a new play of a removed add-on's link the player would not open is resolved again from where it was, not counted as a failure`() = runTest {
        playingFromAnAddonThatIsThenRemoved()
        every { controller.currentPosition } returns resumedAtMs
        // Repeat-one: the player's source would not open the link for the song's next play (AddonCheckedMediaSource).
        repo.playerListener.onPlayerError(
            PlaybackException("Source error", com.stash.core.media.streaming.AddonStreamGoneException(1L), PlaybackException.ERROR_CODE_IO_UNSPECIFIED),
        )
        idleMain()

        assertResolvedAgainNotRetried()
    }

    @Test
    fun `that error on a song from elsewhere plays the song as it is`() = runTest {
        playingFromAnAddonThatIsThenRemoved()
        every { controller.currentMediaItemIndex } returns 1
        every { controller.currentMediaItem } answers { slots[1] }
        every { controller.playbackState } returns Player.STATE_IDLE
        every { controller.playerError } returns PlaybackException("gone", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        val before = swaps.size

        repo.playerListener.onPlayerError(
            PlaybackException("Source error", com.stash.core.media.streaming.AddonStreamGoneException(1L), PlaybackException.ERROR_CODE_IO_UNSPECIFIED),
        )
        idleMain()

        assertThat(swaps.drop(before).map { it.first }).doesNotContain(1)
        verify { controller.prepare() }
        verify(exactly = 0) { controller.seekToNextMediaItem() }
    }

    // ------------------------------------------------------------------------------------- an add-on set aside by a download (R3-m1)

    @Test
    fun `an add-on a download set aside has its queued links put back before the player reaches them - review-888 R3-m1`() = runTest {
        // The look-ahead swapped the add-on's URL into the next slot; then one of its downloads took too long.
        slots += listOf(urlSlot(1L, "qbdlx"), urlSlot(2L, "addon:slow"), urlSlot(3L, "qbdlx"))
        val watching = launch { repo.resolveAgainWhenAnAddonIsSetAside(check.setAsideAddons) }
        settle(rounds = 5)
        assertThat(swaps).isEmpty()

        gate.recordDegraded("addon:slow")
        settle { swaps.size >= 2 }
        watching.cancel()

        // Put back now and looked up again: the join plays the next source's file, not a gone error.
        assertThat(swaps.map { it.first }).containsExactly(1, 1).inOrder()
        assertThat(swaps[0].second.localConfiguration?.uri?.isPutBack()).isTrue()
        assertThat(slots[1].localConfiguration?.uri?.toString()).isEqualTo(newUrl)
    }

    @Test
    fun `a refusal and the set-aside it makes put the add-on's links back once, and take the refusal with them`() = runTest {
        slots += listOf(urlSlot(1L, "qbdlx"), radioSlot(second), radioSlot(third))
        check.opened(2L, "addon:liar")
        check.opened(3L, "addon:liar")
        val asides = launch { repo.resolveAgainWhenAnAddonIsSetAside(check.setAsideAddons) }
        val refusals = launch { repo.resolveAgainWhenAStreamIsRefused(check.refusals) }
        settle(rounds = 5)

        // Song 3, read ahead two deep, is refused: the check records it, sets the add-on aside, and says so.
        check.judge(AddonStreamCheck.Claim("addon:liar", 3L, "3", statedLossless = true, songMs = 0L), listOf("audio/mpeg"), null)
        settle { swaps.size >= 3 }
        settle(rounds = 10)
        asides.cancel()
        refusals.cancel()

        assertThat(swaps.map { it.first }).containsExactly(1, 2, 1).inOrder()
        coVerify(exactly = 1) { streamResolver.resolve(any(), any(), any(), any()) }
        // Put back before the player reached it, whichever word came first: no error will read its refusal.
        assertThat(check.takeRefusal(3L)).isFalse()
    }

    // ------------------------------------------------------------------------------------- only the error path prepares (R3-m2)

    @Test
    fun `moving off a held-over song whose add-on came back prepares nothing - review-888 R3-m2`() = runTest {
        playingFromAnAddonThatIsThenRemoved()
        on.value = listOf(addon("gone")) // back on: the held slot is no longer an add-on's that left
        // The move lands on a song whose read-ahead failed: the player is stopped on that error, onPlayerError's to handle.
        every { controller.currentMediaItemIndex } returns 1
        every { controller.currentMediaItem } answers { slots[1] }
        every { controller.playbackState } returns Player.STATE_IDLE
        every { controller.playerError } returns PlaybackException("file gone", null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)

        repo.playerListener.onMediaItemTransition(slots[1], Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        idleMain()

        assertThat(swaps).isEmpty()
        verify(exactly = 0) { controller.prepare() }
    }

    @Test
    fun `a gone error whose song the user skipped before the put-back ran still prepares the song shown - review-888 R3-m2`() = runTest {
        playingFromAnAddonThatIsThenRemoved()
        repo.playerListener.onPlayerError(
            PlaybackException("Source error", com.stash.core.media.streaming.AddonStreamGoneException(1L), PlaybackException.ERROR_CODE_IO_UNSPECIFIED),
        )
        // Before the put-back runs, the user skips to song 2; the player is still stopped on the error.
        every { controller.currentMediaItemIndex } returns 1
        every { controller.currentMediaItem } answers { slots[1] }
        every { controller.playbackState } returns Player.STATE_IDLE
        every { controller.playerError } returns PlaybackException("gone", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        idleMain()

        // Song 1's slot is put back as it stands, and the song the user picked is prepared, once.
        assertThat(swaps.map { it.first }).containsExactly(0)
        assertThat(slots[0].localConfiguration?.uri?.isPutBack()).isTrue()
        verify(exactly = 1) { controller.prepare() }
        verify(exactly = 0) { controller.seekTo(any<Int>(), any<Long>()) }
    }

    // ------------------------------------------------------------------------------------- a crossfade's incoming (R3-n5)

    @Test
    fun `a song landed on that plays a removed add-on's stream is held over too - review-888 R3-n5`() = runTest {
        playingFromAnAddonThatIsThenRemoved() // song 1 held over
        // Mid-fade: the incoming, song 2, was primed on the add-on's URL before it left, and the swap lands on it.
        slots[1] = urlSlot(2L, "addon:gone")
        every { controller.currentMediaItemIndex } returns 1
        every { controller.currentMediaItem } answers { slots[1] }
        repo.playerListener.onMediaItemTransition(slots[1], Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        idleMain()
        assertThat(swaps.map { it.first }).containsExactly(0) // song 1 put back; song 2 plays on

        every { controller.currentMediaItemIndex } returns 2
        every { controller.nextMediaItemIndex } returns androidx.media3.common.C.INDEX_UNSET // the last song: nothing to look ahead to
        every { controller.currentMediaItem } answers { slots[2] }
        repo.playerListener.onMediaItemTransition(slots[2], Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        idleMain()

        assertThat(swaps.map { it.first }).containsExactly(0, 1).inOrder()
        assertThat(slots[1].localConfiguration?.uri?.isPutBack()).isTrue()
    }
}
