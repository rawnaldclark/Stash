package com.stash.core.media.listen

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.Util
import com.google.common.collect.ImmutableList
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.stash.core.media.PlayNext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ListenTogetherPlayerTest {
    /** A one-song player that records what reaches it. [playing]: its own playWhenReady (the session started it). */
    private class StubPlayer(val playing: Boolean = false) : SimpleBasePlayer(Looper.getMainLooper()) {
        val calls = mutableListOf<String>()
        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(ImmutableList.of(MediaItemData.Builder("one").setMediaItem(MediaItem.Builder().setMediaId("1").build()).build()))
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build()
        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> { calls += "playWhenReady=$playWhenReady"; return Futures.immediateVoidFuture() }
        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> { calls += "seek"; return Futures.immediateVoidFuture() }
        override fun handlePrepare(): ListenableFuture<*> { calls += "prepare"; return Futures.immediateVoidFuture() }
        override fun handleStop(): ListenableFuture<*> { calls += "stop"; return Futures.immediateVoidFuture() }
    }

    private class Recorder : SessionInterceptor {
        val calls = mutableListOf<String>()
        /** Runs inside onPause, as the session's own [ListenTogetherPlayer.setPlayIntent] does. */
        var afterPause: () -> Unit = {}
        override fun onPlay() { calls += "play" }
        override fun onPause() { calls += "pause"; afterPause() }
        override fun onSeek(positionMs: Long) { calls += "seek:$positionMs" }
        override fun onNext() { calls += "next" }
        override fun onPrevious() { calls += "previous" }
        override fun onAdd(items: List<MediaItem>) {
            calls += items.joinToString(",", prefix = "add:") { it.mediaId + if (PlayNext.isTagged(it)) "+next" else "" }
        }
        override fun onSet(items: List<MediaItem>, startIndex: Int) { calls += "set:$startIndex" }
    }

    @Test fun `a listener's seek, skip and stop go nowhere, play and pause go to the session`() {
        val stub = StubPlayer()
        val recorder = Recorder()
        val player = ListenTogetherPlayer(stub).apply { configure(isHost = false, interceptor = recorder) }
        player.seekTo(5_000); player.seekToNext(); player.seekToPrevious(); player.stop()
        player.play(); player.pause()
        assertThat(recorder.calls).containsExactly("play", "pause").inOrder()
        assertThat(stub.calls).isEmpty()
        assertThat(player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)).isTrue()
        assertThat(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)).isFalse()
    }

    @Test fun `a listener's Play next and Add to queue reach the session, telling one from the other`() {
        val recorder = Recorder()
        val player = ListenTogetherPlayer(StubPlayer()).apply { configure(isHost = false, interceptor = recorder) }
        assertThat(player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)).isTrue()
        player.addMediaItem(MediaItem.Builder().setMediaId("7").build())
        // Play next inserts after the current song, with the tag that rides the MediaController round trip.
        player.addMediaItem(1, PlayNext.tag(MediaItem.Builder().setMediaId("8").build()))
        assertThat(recorder.calls).containsExactly("add:7", "add:8+next").inOrder()
    }

    @Test fun `the host's commands go to the session, never straight to the player`() {
        val stub = StubPlayer()
        val recorder = Recorder()
        val player = ListenTogetherPlayer(stub).apply { configure(isHost = true, interceptor = recorder) }
        player.play(); player.pause(); player.seekTo(90_000); player.seekToNext(); player.seekToPrevious()
        player.setMediaItems(listOf(MediaItem.Builder().setMediaId("8").build()), 0, 0L)
        assertThat(recorder.calls).containsExactly("play", "pause", "seek:90000", "next", "previous", "set:0").inOrder()
        assertThat(stub.calls).isEmpty()
        assertThat(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)).isTrue()
    }

    @Test fun `prepare in a session never reaches the wrapped player`() {
        val stub = StubPlayer()
        ListenTogetherPlayer(stub).apply { configure(isHost = true, interceptor = Recorder()) }.prepare()
        ListenTogetherPlayer(stub).apply { configure(isHost = false, interceptor = Recorder()) }.prepare()
        assertThat(stub.calls).isEmpty()
    }

    @Test fun `a listener's single-song tap reaches onSet`() {
        val recorder = Recorder()
        val player = ListenTogetherPlayer(StubPlayer()).apply { configure(isHost = false, interceptor = recorder) }
        assertThat(player.isCommandAvailable(Player.COMMAND_SET_MEDIA_ITEM)).isTrue()
        player.setMediaItem(MediaItem.Builder().setMediaId("9").build())
        // setMediaItem(item) resets the position: Media3 passes startIndex = C.INDEX_UNSET, not 0.
        assertThat(recorder.calls).containsExactly("set:${C.INDEX_UNSET}")
    }

    @Test fun `a role flip from host to listener withdraws skip`() {
        val recorder = Recorder()
        val player = ListenTogetherPlayer(StubPlayer()).apply { configure(isHost = true, interceptor = recorder) }
        assertThat(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)).isTrue()
        player.configure(isHost = false, interceptor = recorder)
        assertThat(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)).isFalse()
    }

    @Test fun `outside a session every call passes straight through`() {
        val stub = StubPlayer()
        val player = ListenTogetherPlayer(stub).apply { configure(isHost = false, interceptor = null) }
        assertThat(player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)).isTrue()
        player.prepare(); player.play(); player.seekTo(5_000); player.stop()
        assertThat(stub.calls).containsExactly("prepare", "playWhenReady=true", "seek", "stop").inOrder()
    }

    /**
     * The 2026-11-07 device bug: the session holds the local player paused while a song resolves, though the room plays
     * it for the guests. Every play/pause control read that as "paused" and sent Play, which the room drops when it is
     * already playing, so the host's Pause did nothing. The room's intent is what they must read.
     */
    @Test fun `the session's intent is what every play-pause control reads, so a toggle on a held-paused player pauses`() {
        val stub = StubPlayer() // READY, playWhenReady false: the session's player before the room's start time
        val recorder = Recorder()
        val player = ListenTogetherPlayer(stub).apply { configure(isHost = true, interceptor = recorder) }
        assertThat(Util.shouldShowPlayButton(player)).isTrue()
        player.setPlayIntent(true)
        assertThat(player.playWhenReady).isTrue()
        assertThat(Util.shouldShowPlayButton(player)).isFalse() // the notification, lock screen and headset show Pause
        // The session marks the pause before onPause returns, re-entrantly, and the state read after it must say so.
        recorder.afterPause = { player.setPlayIntent(false) }
        Util.handlePlayPauseButtonAction(player) // a headset press, the notification's button
        assertThat(recorder.calls).containsExactly("pause")
        assertThat(stub.calls).isEmpty()
        assertThat(player.playWhenReady).isFalse()
        assertThat(Util.shouldShowPlayButton(player)).isTrue()
    }

    @Test fun `a ready player held for the room's start reads as loading, never as playing, until the session starts it`() {
        val held = ListenTogetherPlayer(StubPlayer()).apply { configure(isHost = false, interceptor = Recorder()) }
        held.setPlayIntent(true)
        assertThat(held.playbackState).isEqualTo(Player.STATE_BUFFERING)
        assertThat(held.isPlaying).isFalse()
        val started = ListenTogetherPlayer(StubPlayer(playing = true)).apply { configure(isHost = false, interceptor = Recorder()) }
        started.setPlayIntent(true)
        assertThat(started.playbackState).isEqualTo(Player.STATE_READY)
        assertThat(started.isPlaying).isTrue()
        held.setPlayIntent(false) // paused here: the player's own state, as before
        assertThat(held.playbackState).isEqualTo(Player.STATE_READY)
        assertThat(held.isPlaying).isFalse()
    }

    @Test fun `outside a session the intent is dropped and the wrapped player's own playWhenReady shows`() {
        val stub = StubPlayer()
        val player = ListenTogetherPlayer(stub).apply { configure(isHost = true, interceptor = Recorder()) }
        player.setPlayIntent(true)
        player.configure(isHost = false, interceptor = null)
        assertThat(player.playWhenReady).isFalse()
        player.setPlayIntent(true) // a late call after the session ended changes nothing
        assertThat(player.playWhenReady).isFalse()
        player.play()
        assertThat(stub.calls).containsExactly("playWhenReady=true")
    }

    @Test fun `the host's seek to the default position is a seek to 0 and stop is a pause`() {
        val stub = StubPlayer()
        val recorder = Recorder()
        val player = ListenTogetherPlayer(stub).apply { configure(isHost = true, interceptor = recorder) }
        player.seekToDefaultPosition(); player.stop()
        assertThat(recorder.calls).containsExactly("seek:0", "pause").inOrder()
        assertThat(stub.calls).isEmpty()
    }
}
