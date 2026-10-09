package com.stash.core.media.cast

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CastSessionPlayerTest {

    /** The stopped local player: a queue, an index and a position, nothing more. */
    private class QueuePlayer(ids: List<String>) : SimpleBasePlayer(Looper.getMainLooper()) {
        val items = ids.map(::item).toMutableList()
        var index = 0
        var positionMs = 0L
        var wantPlay = false
        var repeat = Player.REPEAT_MODE_OFF
        val calls = mutableListOf<String>()

        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(items.mapIndexed { i, it -> MediaItemData.Builder("uid$i").setMediaItem(it).build() })
            .setCurrentMediaItemIndex(if (items.isEmpty()) C.INDEX_UNSET else index)
            .setContentPositionMs(positionMs)
            .setPlayWhenReady(wantPlay, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(Player.STATE_IDLE)
            .setRepeatMode(repeat)
            .build()

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            index = mediaItemIndex
            this.positionMs = if (positionMs == C.TIME_UNSET) 0 else positionMs
            return done()
        }

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            calls += "local playWhenReady=$playWhenReady"
            wantPlay = playWhenReady
            return done()
        }

        override fun handlePrepare(): ListenableFuture<*> { calls += "local prepare"; return done() }
        override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> { repeat = repeatMode; return done() }

        override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
            repeat(toIndex - fromIndex) { items.removeAt(fromIndex) }
            items.addAll(fromIndex, mediaItems)
            return done()
        }

        override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
            items.clear()
            items.addAll(mediaItems)
            index = if (startIndex == C.INDEX_UNSET) 0 else startIndex
            positionMs = if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs
            return done()
        }

        override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
            repeat(toIndex - fromIndex) { items.removeAt(fromIndex) }
            if (index >= toIndex) index -= toIndex - fromIndex
            return done()
        }

        private fun done() = Futures.immediateVoidFuture()
    }

    /** A speaker that records commands; tests push its status with [report]. */
    private class FakeRemote : CastRemote {
        override var status = CastStatus.EMPTY
        val loads = mutableListOf<Triple<String, Long, Boolean>>()
        val calls = mutableListOf<String>()

        /** Every setNext, in order: the queued contentId, or null for "cleared". */
        val nexts = mutableListOf<String?>()
        private val listeners = mutableListOf<() -> Unit>()

        val lastContentId: String get() = loads.last().first

        override fun load(media: CastMedia, startPositionMs: Long, autoplay: Boolean) {
            loads += Triple(media.contentId, startPositionMs, autoplay)
        }
        override fun setNext(media: CastMedia?) { nexts += media?.contentId }
        /** What playNext answers: whether the receiver really holds a queued song. */
        var hasQueued = true
        override fun playNext(): Boolean { calls += "playNext"; return hasQueued }
        override fun play() { calls += "play" }
        override fun pause() { calls += "pause" }
        override fun seekTo(positionMs: Long) { calls += "seek:$positionMs" }
        override fun stop() { calls += "stop" }
        override fun setVolume(level: Float) { calls += "volume:$level" }
        override fun setMuted(muted: Boolean) { calls += "muted:$muted" }
        override fun addListener(listener: () -> Unit) { listeners += listener }
        override fun removeListener(listener: () -> Unit) { listeners -= listener }

        fun report(
            state: CastStatus.PlayerState,
            idle: CastStatus.IdleReason = CastStatus.IdleReason.NONE,
            positionMs: Long = 0L,
            durationMs: Long = 200_000L,
            contentId: String? = lastContentId,
        ) {
            status = CastStatus(state, idle, contentId, positionMs, durationMs, 0.5f, false)
            listeners.toList().forEach { it() }
        }
    }

    private var now = 0L

    private fun cast(
        local: QueuePlayer,
        remote: FakeRemote,
        positionMs: Long = 0L,
        play: Boolean = true,
        prepare: (MediaItem, (Boolean) -> Unit) -> Unit = { _, done -> done(true) },
    ) = CastSessionPlayer(local, mediaFor = { item, contentId -> media(item, contentId) }, prepare = prepare, clock = { now })
        .apply { attach(local, remote, positionMs, play) }

    // ── Preparing songs before the speaker sees them ──────────────────────

    @Test fun `the speaker gets a song only once it's prepared, and buffers until then`() {
        var pending: ((Boolean) -> Unit)? = null
        val remote = FakeRemote()
        val player = cast(QueuePlayer(listOf("a")), remote, prepare = { _, done -> pending = done })

        assertThat(remote.loads).isEmpty()
        assertThat(player.playbackState).isEqualTo(Player.STATE_BUFFERING)

        pending!!(true)
        assertThat(remote.loads).hasSize(1)
    }

    @Test fun `a song that couldn't be prepared is an error the queue can skip`() {
        val remote = FakeRemote()
        val player = cast(QueuePlayer(listOf("a", "b")), remote, prepare = { _, done -> done(false) })

        assertThat(remote.loads).isEmpty()
        assertThat(player.playerError?.errorCode).isEqualTo(PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
    }

    @Test fun `a skip while a song is still being prepared drops it`() {
        val pending = mutableListOf<(Boolean) -> Unit>()
        val remote = FakeRemote()
        val player = cast(QueuePlayer(listOf("a", "b")), remote, prepare = { _, done -> pending += done })

        player.seekToNextMediaItem()
        pending.forEach { it(true) } // a's prepare finishes too, after b's load was asked for

        assertThat(remote.loads.map { it.first.substringBefore('#') }).containsExactly("b")
    }

    @Test fun `a pause while the song is prepared loads it paused`() {
        var pending: ((Boolean) -> Unit)? = null
        val remote = FakeRemote()
        val player = cast(QueuePlayer(listOf("a")), remote, play = true, prepare = { _, done -> pending = done })

        player.pause()
        pending!!(true)

        assertThat(remote.loads.single().third).isFalse()
    }

    // ── The next song queued on the speaker ───────────────────────────────

    @Test fun `once a song plays, the next one is queued on the speaker`() {
        val remote = FakeRemote()
        cast(QueuePlayer(listOf("a", "b")), remote)
        assertThat(remote.nexts).isEmpty() // nothing while the load is in flight

        remote.report(CastStatus.PlayerState.PLAYING)

        assertThat(remote.nexts.single()).startsWith("b#n")
    }

    @Test fun `when the speaker moves on by itself the queue follows, without a reload`() {
        val local = QueuePlayer(listOf("a", "b", "c"))
        val remote = FakeRemote()
        val player = cast(local, remote)
        remote.report(CastStatus.PlayerState.PLAYING)
        val queuedB = remote.nexts.single()!!

        remote.report(CastStatus.PlayerState.PLAYING, positionMs = 1_000L, contentId = queuedB)

        assertThat(local.index).isEqualTo(1)
        assertThat(remote.loads).hasSize(1) // b came from the speaker's own queue
        assertThat(player.currentPosition).isEqualTo(1_000L)
        assertThat(remote.nexts.last()).startsWith("c#n") // and c is queued behind it
    }

    @Test fun `next jumps to the song the speaker already buffered, without a new load`() {
        val local = QueuePlayer(listOf("a", "b", "c"))
        val remote = FakeRemote()
        val player = cast(local, remote)
        remote.report(CastStatus.PlayerState.PLAYING)
        val queuedB = remote.nexts.single()!!

        player.seekToNextMediaItem()

        assertThat(remote.calls).containsExactly("playNext")
        assertThat(remote.loads).hasSize(1)
        assertThat(local.index).isEqualTo(1)

        remote.report(CastStatus.PlayerState.PLAYING, contentId = queuedB)
        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
        assertThat(remote.nexts.last()).startsWith("c#n") // the one after is queued in turn
    }

    @Test fun `next loads the song when the speaker turns out to hold nothing queued`() {
        val local = QueuePlayer(listOf("a", "b"))
        val remote = FakeRemote()
        val player = cast(local, remote)
        remote.report(CastStatus.PlayerState.PLAYING)
        remote.hasQueued = false // queueing failed on the receiver

        player.seekToNextMediaItem()

        assertThat(remote.loads).hasSize(2)
        assertThat(remote.lastContentId).startsWith("b#")
    }

    @Test fun `a song the speaker plays that we no longer queued moves the queue on`() {
        val local = QueuePlayer(listOf("a", "b", "c"))
        val remote = FakeRemote()
        cast(local, remote)
        remote.report(CastStatus.PlayerState.PLAYING)

        remote.report(CastStatus.PlayerState.PLAYING, contentId = "x#n99") // a stale leftover

        assertThat(local.index).isEqualTo(1)
        assertThat(remote.lastContentId).startsWith("b#")
    }

    @Test fun `the previous song still reported during our load is not mistaken for a stray one`() {
        val local = QueuePlayer(listOf("a", "b", "c"))
        val remote = FakeRemote()
        val player = cast(local, remote)
        remote.report(CastStatus.PlayerState.PLAYING)
        val songA = remote.lastContentId
        player.seekTo(2, 0L) // c: not the queued song, so a fresh load

        remote.report(CastStatus.PlayerState.PLAYING, contentId = songA) // the speaker hasn't switched yet

        assertThat(local.index).isEqualTo(2)
        assertThat(remote.loads).hasSize(2)
    }

    @Test fun `next before the following song is queued loads it`() {
        val local = QueuePlayer(listOf("a", "b"))
        val remote = FakeRemote()
        val player = cast(local, remote) // no status yet, so nothing is queued

        player.seekToNextMediaItem()

        assertThat(remote.calls).doesNotContain("playNext")
        assertThat(remote.loads).hasSize(2)
    }

    @Test fun `a queue edit that changes the next song replaces the queued one`() {
        val local = QueuePlayer(listOf("a", "b", "c"))
        val remote = FakeRemote()
        val player = cast(local, remote)
        remote.report(CastStatus.PlayerState.PLAYING)

        player.removeMediaItem(1) // b goes; c is next now

        assertThat(remote.nexts).hasSize(3)
        assertThat(remote.nexts[0]).startsWith("b#n")
        assertThat(remote.nexts[1]).isNull()
        assertThat(remote.nexts[2]).startsWith("c#n")
    }

    @Test fun `repeat one queues nothing, and switching to it takes the queued song back`() {
        val local = QueuePlayer(listOf("a", "b"))
        val remote = FakeRemote()
        val player = cast(local, remote)
        remote.report(CastStatus.PlayerState.PLAYING)

        player.repeatMode = Player.REPEAT_MODE_ONE

        assertThat(remote.nexts).hasSize(2)
        assertThat(remote.nexts.last()).isNull()
    }

    @Test fun `the last song queues nothing`() {
        val remote = FakeRemote()
        cast(QueuePlayer(listOf("a")), remote)
        remote.report(CastStatus.PlayerState.PLAYING)

        assertThat(remote.nexts).isEmpty()
    }

    // ── Playing ───────────────────────────────────────────────────────────

    @Test fun `attaching loads the current song where the phone was`() {
        val local = QueuePlayer(listOf("a", "b")).apply { index = 1 }
        val remote = FakeRemote()
        val player = cast(local, remote, positionMs = 42_000L, play = true)

        assertThat(remote.loads.single().second).isEqualTo(42_000L)
        assertThat(remote.loads.single().third).isTrue()
        assertThat(remote.lastContentId).startsWith("b#")
        assertThat(player.playbackState).isEqualTo(Player.STATE_BUFFERING)
        assertThat(player.deviceInfo.playbackType).isEqualTo(androidx.media3.common.DeviceInfo.PLAYBACK_TYPE_REMOTE)
    }

    @Test fun `position, duration and state come from the speaker`() {
        val remote = FakeRemote()
        val player = cast(QueuePlayer(listOf("a")), remote)
        remote.report(CastStatus.PlayerState.PLAYING, positionMs = 61_000L, durationMs = 180_000L)

        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
        assertThat(player.isPlaying).isTrue()
        assertThat(player.currentPosition).isEqualTo(61_000L)
        assertThat(player.duration).isEqualTo(180_000L)
    }

    @Test fun `play and pause go to the speaker, never the phone`() {
        val local = QueuePlayer(listOf("a"))
        val remote = FakeRemote()
        val player = cast(local, remote)
        remote.report(CastStatus.PlayerState.PLAYING)

        player.pause()
        now += 5_000
        remote.report(CastStatus.PlayerState.PAUSED)
        player.play()

        assertThat(remote.calls).containsExactly("pause", "play").inOrder()
        assertThat(local.calls).isEmpty()
    }

    @Test fun `a pause that arrives while the song loads is applied when the speaker answers`() {
        val remote = FakeRemote()
        val player = cast(QueuePlayer(listOf("a")), remote, play = true)
        player.pause() // the load (autoplay) is still in flight
        assertThat(remote.calls).isEmpty()

        remote.report(CastStatus.PlayerState.PLAYING)

        assertThat(remote.calls).containsExactly("pause")
        assertThat(player.playWhenReady).isFalse()
    }

    @Test fun `the idle a speaker reports before loading is still buffering, and play doesn't load twice`() {
        val remote = FakeRemote()
        val player = cast(QueuePlayer(listOf("a")), remote, play = false)
        remote.report(CastStatus.PlayerState.IDLE)

        assertThat(player.playbackState).isEqualTo(Player.STATE_BUFFERING)
        player.play()
        assertThat(remote.loads).hasSize(1)

        remote.report(CastStatus.PlayerState.PAUSED)
        assertThat(remote.calls).containsExactly("play")
    }

    @Test fun `a seek within the song moves the speaker, not the queue`() {
        val local = QueuePlayer(listOf("a", "b"))
        val remote = FakeRemote()
        val player = cast(local, remote)
        remote.report(CastStatus.PlayerState.PLAYING)

        player.seekTo(90_000L)

        assertThat(remote.calls).containsExactly("seek:90000")
        assertThat(remote.loads).hasSize(1)
        assertThat(local.index).isEqualTo(0)
    }

    @Test fun `jumping to another song moves the phone's queue and loads it on the speaker`() {
        val local = QueuePlayer(listOf("a", "b", "c"))
        val remote = FakeRemote()
        val player = cast(local, remote)
        remote.report(CastStatus.PlayerState.PLAYING, positionMs = 10_000L)

        player.seekTo(2, 0L) // c: not the song queued on the speaker (b)

        assertThat(local.index).isEqualTo(2)
        assertThat(remote.loads).hasSize(2)
        assertThat(remote.lastContentId).startsWith("c#")
        assertThat(remote.loads.last().second).isEqualTo(0L)
        assertThat(remote.loads.last().third).isTrue()
    }

    @Test fun `when the speaker finishes a song the queue moves on`() {
        val local = QueuePlayer(listOf("a", "b"))
        val remote = FakeRemote()
        cast(local, remote)
        remote.report(CastStatus.PlayerState.PLAYING)

        remote.report(CastStatus.PlayerState.IDLE, CastStatus.IdleReason.FINISHED)
        remote.report(CastStatus.PlayerState.IDLE, CastStatus.IdleReason.FINISHED, contentId = remote.loads.first().first)

        assertThat(local.index).isEqualTo(1)
        assertThat(remote.loads).hasSize(2) // the repeated FINISHED status didn't skip twice
        assertThat(remote.lastContentId).startsWith("b#")
    }

    @Test fun `finishing the last song ends playback`() {
        val remote = FakeRemote()
        val player = cast(QueuePlayer(listOf("a")), remote)
        remote.report(CastStatus.PlayerState.PLAYING)

        remote.report(CastStatus.PlayerState.IDLE, CastStatus.IdleReason.FINISHED)

        assertThat(player.playbackState).isEqualTo(Player.STATE_ENDED)
        assertThat(remote.loads).hasSize(1)
    }

    @Test fun `repeat one plays the same song again`() {
        val local = QueuePlayer(listOf("a", "b")).apply { repeat = Player.REPEAT_MODE_ONE }
        val remote = FakeRemote()
        cast(local, remote)
        remote.report(CastStatus.PlayerState.PLAYING)

        remote.report(CastStatus.PlayerState.IDLE, CastStatus.IdleReason.FINISHED)

        assertThat(local.index).isEqualTo(0)
        assertThat(remote.loads).hasSize(2)
        assertThat(remote.lastContentId).startsWith("a#")
    }

    @Test fun `repeat all over a single song loads it again`() {
        val local = QueuePlayer(listOf("a")).apply { repeat = Player.REPEAT_MODE_ALL }
        val remote = FakeRemote()
        cast(local, remote)
        remote.report(CastStatus.PlayerState.PLAYING)

        remote.report(CastStatus.PlayerState.IDLE, CastStatus.IdleReason.FINISHED)

        assertThat(remote.loads).hasSize(2)
    }

    @Test fun `playing the song that just ended again loads it on the speaker`() {
        val remote = FakeRemote()
        val player = cast(QueuePlayer(listOf("a")), remote)
        remote.report(CastStatus.PlayerState.PLAYING)
        remote.report(CastStatus.PlayerState.IDLE, CastStatus.IdleReason.FINISHED)
        assertThat(player.playbackState).isEqualTo(Player.STATE_ENDED)

        // What PlayerRepositoryImpl.playQueue does when the user taps the song again.
        player.setMediaItems(listOf(item("a"), item("b")), 0, 0L)
        player.prepare()
        player.play()

        assertThat(remote.loads).hasSize(2)
        assertThat(remote.lastContentId).startsWith("a#")
        assertThat(remote.loads.last().second).isEqualTo(0L)
        assertThat(player.playbackState).isEqualTo(Player.STATE_BUFFERING)
    }

    @Test fun `starting a new queue on the song that is playing restarts it from the new position`() {
        val remote = FakeRemote()
        val player = cast(QueuePlayer(listOf("a", "b")), remote)
        remote.report(CastStatus.PlayerState.PLAYING, positionMs = 130_000L)

        player.setMediaItems(listOf(item("x"), item("a")), 1, 0L)

        assertThat(remote.loads).hasSize(2)
        assertThat(remote.lastContentId).startsWith("a#")
        assertThat(remote.loads.last().second).isEqualTo(0L)
    }

    @Test fun `a new queue starting on a different song loads it once`() {
        val remote = FakeRemote()
        val player = cast(QueuePlayer(listOf("a")), remote)
        remote.report(CastStatus.PlayerState.PLAYING)

        player.setMediaItems(listOf(item("c"), item("d")), 0, 5_000L)

        assertThat(remote.loads).hasSize(2)
        assertThat(remote.lastContentId).startsWith("c#")
        assertThat(remote.loads.last().second).isEqualTo(5_000L)
    }

    @Test fun `swapping the current song's placeholder for its resolved URL doesn't restart it`() {
        val local = QueuePlayer(listOf("a", "b"))
        val remote = FakeRemote()
        val player = cast(local, remote)
        remote.report(CastStatus.PlayerState.PLAYING)

        player.replaceMediaItem(0, MediaItem.Builder().setMediaId("a").setUri("https://cdn.example/a.flac").build())
        player.removeMediaItem(1)

        assertThat(remote.loads).hasSize(1)
    }

    @Test fun `a speaker error becomes the player error`() {
        val remote = FakeRemote()
        val player = cast(QueuePlayer(listOf("a", "b")), remote)

        remote.report(CastStatus.PlayerState.IDLE, CastStatus.IdleReason.ERROR)

        assertThat(player.playbackState).isEqualTo(Player.STATE_IDLE)
        assertThat(player.playerError?.errorCode).isEqualTo(PlaybackException.ERROR_CODE_REMOTE_ERROR)
    }

    @Test fun `a song that can't be served is an error, not a silent load`() {
        val local = QueuePlayer(listOf("a"))
        val remote = FakeRemote()
        val player = CastSessionPlayer(local, mediaFor = { _, _ -> null }, clock = { now })
            .apply { attach(local, remote, 0L, true) }

        assertThat(remote.loads).isEmpty()
        assertThat(player.playbackState).isEqualTo(Player.STATE_IDLE)
        assertThat(player.playerError?.errorCode).isEqualTo(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
    }

    @Test fun `prepare after an error loads the song again`() {
        val remote = FakeRemote()
        val player = cast(QueuePlayer(listOf("a")), remote)
        remote.report(CastStatus.PlayerState.IDLE, CastStatus.IdleReason.ERROR)

        player.prepare()

        assertThat(remote.loads).hasSize(2)
        assertThat(player.playerError).isNull()
    }

    @Test fun `seeking after the queue ended starts the song again`() {
        val remote = FakeRemote()
        val player = cast(QueuePlayer(listOf("a")), remote)
        remote.report(CastStatus.PlayerState.PLAYING)
        remote.report(CastStatus.PlayerState.IDLE, CastStatus.IdleReason.FINISHED)

        player.seekTo(0, 0L)

        assertThat(remote.loads).hasSize(2)
        assertThat(player.playbackState).isEqualTo(Player.STATE_BUFFERING)
    }

    @Test fun `play after stop loads the song where it stopped`() {
        val remote = FakeRemote()
        val player = cast(QueuePlayer(listOf("a")), remote)
        remote.report(CastStatus.PlayerState.PLAYING, positionMs = 30_000L)

        player.stop()
        player.play()

        assertThat(remote.calls).contains("stop")
        assertThat(remote.loads).hasSize(2)
        assertThat(remote.loads.last().second).isEqualTo(30_000L)
    }

    @Test fun `a pause from the speaker itself shows on the phone`() {
        val remote = FakeRemote()
        val player = cast(QueuePlayer(listOf("a")), remote)
        remote.report(CastStatus.PlayerState.PLAYING)
        now += 10_000 // well past our own last command

        remote.report(CastStatus.PlayerState.PAUSED)

        assertThat(player.playWhenReady).isFalse()
    }

        @Test fun `volume keys drive the speaker`() {
        val remote = FakeRemote()
        val player = cast(QueuePlayer(listOf("a")), remote)
        remote.report(CastStatus.PlayerState.PLAYING) // volume 0.5 → 10 of 20

        @Suppress("DEPRECATION") player.increaseDeviceVolume()

        assertThat(player.deviceVolume).isEqualTo(10)
        assertThat(remote.calls).containsExactly("volume:0.55")
    }

    @Test fun `speed isn't offered while casting`() {
        val player = cast(QueuePlayer(listOf("a")), FakeRemote())
        assertThat(player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)).isFalse()
    }

    @Test fun `re-pointed at a new receiver, it loads the song there where the old one was`() {
        val local = QueuePlayer(listOf("a", "b"))
        val old = FakeRemote()
        val player = cast(local, old)
        old.report(CastStatus.PlayerState.PLAYING, positionMs = 42_000L)

        val fresh = FakeRemote()
        player.attach(local, fresh, player.currentPosition, player.playWhenReady)

        assertThat(player.isAttachedTo(fresh)).isTrue()
        assertThat(fresh.loads.single().first).startsWith("a#")
        assertThat(fresh.loads.single().second).isEqualTo(42_000L)
        old.report(CastStatus.PlayerState.PAUSED, positionMs = 50_000L) // the old one is ignored now
        assertThat(player.playWhenReady).isTrue()
    }

    @Test fun `once the session is torn down, the position keeps moving from the last status`() {
        val local = QueuePlayer(listOf("a"))
        val remote = FakeRemote()
        val player = cast(local, remote)
        remote.report(CastStatus.PlayerState.PLAYING, positionMs = 30_000L, durationMs = 200_000L)

        // The receiver sends nothing while it plays; then the SDK drops the
        // media client (suspend, end) and the status carries no song at all.
        now += 45_000
        remote.status = CastStatus.EMPTY

        assertThat(player.currentPosition).isEqualTo(75_000L)
        assertThat(player.detach().positionMs).isEqualTo(75_000L)
    }

    @Test fun `a paused song's position doesn't move on, and a playing one stops at its end`() {
        val remote = FakeRemote()
        val player = cast(QueuePlayer(listOf("a")), remote)
        remote.report(CastStatus.PlayerState.PAUSED, positionMs = 30_000L, durationMs = 200_000L)
        player.pause()
        now += 45_000
        remote.status = CastStatus.EMPTY
        assertThat(player.currentPosition).isEqualTo(30_000L)

        player.play()
        remote.report(CastStatus.PlayerState.PLAYING, positionMs = 190_000L, durationMs = 200_000L)
        now += 60_000
        remote.status = CastStatus.EMPTY
        assertThat(player.currentPosition).isEqualTo(200_000L)
    }

    @Test fun `detaching hands back the speaker's song and position`() {
        val local = QueuePlayer(listOf("a", "b"))
        val remote = FakeRemote()
        val player = cast(local, remote)
        player.seekToNextMediaItem()
        remote.report(CastStatus.PlayerState.PLAYING, positionMs = 73_000L)

        val handoff = player.detach()

        assertThat(handoff).isEqualTo(CastSessionPlayer.Handoff(mediaItemIndex = 1, positionMs = 73_000L))
        assertThat(player.isAttached).isFalse()
    }

    private companion object {
        fun item(id: String) = MediaItem.Builder().setMediaId(id).setUri("file:///music/$id.flac").build()
        fun media(item: MediaItem, contentId: String) = CastMedia(
            contentId = contentId,
            url = "http://192.168.1.2:1234/t/m/${item.mediaId}",
            contentType = "audio/flac",
            title = item.mediaId,
            artist = null,
            album = null,
            artworkUrl = null,
            durationMs = 0L,
        )
    }
}
