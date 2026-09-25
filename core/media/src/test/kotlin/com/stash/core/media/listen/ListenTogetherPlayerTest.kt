package com.stash.core.media.listen

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.collect.ImmutableList
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ListenTogetherPlayerTest {
    /** A one-song player that records what reaches it. */
    private class StubPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        val calls = mutableListOf<String>()
        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(ImmutableList.of(MediaItemData.Builder("one").setMediaItem(MediaItem.Builder().setMediaId("1").build()).build()))
            .setPlaybackState(Player.STATE_READY)
            .build()
        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> { calls += "playWhenReady=$playWhenReady"; return Futures.immediateVoidFuture() }
        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> { calls += "seek"; return Futures.immediateVoidFuture() }
    }

    private class Recorder : SessionInterceptor {
        val calls = mutableListOf<String>()
        override fun onPlay() { calls += "play" }
        override fun onPause() { calls += "pause" }
        override fun onSeek(positionMs: Long) { calls += "seek:$positionMs" }
        override fun onNext() { calls += "next" }
        override fun onPrevious() { calls += "previous" }
        override fun onAdd(items: List<MediaItem>) { calls += "add:${items.single().mediaId}" }
        override fun onSet(items: List<MediaItem>, startIndex: Int) { calls += "set:$startIndex" }
    }

    @Test fun `a listener's headset, notification and lock-screen commands go nowhere`() {
        val stub = StubPlayer()
        val recorder = Recorder()
        val player = ListenTogetherPlayer(stub).apply { configure(isHost = false, interceptor = recorder) }
        player.play(); player.pause(); player.seekTo(5_000); player.seekToNext(); player.seekToPrevious(); player.stop()
        assertThat(recorder.calls).isEmpty()
        assertThat(stub.calls).isEmpty()
        assertThat(player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)).isFalse()
        assertThat(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)).isFalse()
    }

    @Test fun `a listener's add from a track menu becomes a suggestion`() {
        val recorder = Recorder()
        val player = ListenTogetherPlayer(StubPlayer()).apply { configure(isHost = false, interceptor = recorder) }
        assertThat(player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)).isTrue()
        player.addMediaItem(MediaItem.Builder().setMediaId("7").build())
        assertThat(recorder.calls).containsExactly("add:7")
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
}
