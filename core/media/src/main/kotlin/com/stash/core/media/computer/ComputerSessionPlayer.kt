package com.stash.core.media.computer

import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.stash.core.model.PlayerState
import com.stash.core.model.RepeatMode
import kotlin.math.roundToInt

/**
 * The computer this phone is the remote for, as a player for a media session of its own: the notification, the lock screen,
 * headphone buttons and the volume keys show and control what plays there ([ComputerRemote]). Nothing plays on this phone.
 *
 * Its own session, not the phone's: the app's player follows the phone's session, and must keep seeing this phone's own
 * player (paused) or it would take the computer's music for its own.
 *
 * Main thread only.
 */
@OptIn(UnstableApi::class)
class ComputerSessionPlayer(
    private val remote: ComputerRemote,
    looper: Looper = Looper.getMainLooper(),
) : SimpleBasePlayer(looper) {

    private var shown: PlayerState? = null
    private var volume: Double? = null

    /** What the computer plays now ([state]), at [volumeLevel] (0..1, or null). */
    fun show(state: PlayerState?, volumeLevel: Double?) {
        shown = state
        volume = volumeLevel
        invalidateState()
    }

    override fun getState(): State {
        val s = shown
        val track = s?.currentTrack
        val b = State.Builder()
            .setAvailableCommands(COMMANDS)
            .setDeviceInfo(DEVICE)
            .setDeviceVolume(((volume ?: 0.0) * MAX_VOLUME).roundToInt().coerceIn(0, MAX_VOLUME))
        if (s == null || track == null) return b.setPlaybackState(Player.STATE_IDLE).build()
        val queue = s.queue.ifEmpty { listOf(track) }
        val items = queue.mapIndexed { i, t ->
            val durationMs = if (i == 0) s.durationMs.takeIf { it > 0 } ?: t.durationMs else t.durationMs
            MediaItemData.Builder("pc:$i:${t.id}")
                .setMediaItem(
                    MediaItem.Builder()
                        .setMediaId("pc:${t.id}")
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(t.title)
                                .setArtist(t.artist)
                                .setAlbumTitle(t.album.takeIf { it.isNotBlank() })
                                .setArtworkUri(t.albumArtUrl?.takeIf { it.startsWith("https://") }?.let(Uri::parse))
                                .setIsPlayable(true)
                                .setIsBrowsable(false)
                                .build(),
                        )
                        .build(),
                )
                .setDurationUs(if (durationMs > 0) durationMs * 1000 else androidx.media3.common.C.TIME_UNSET)
                .setIsSeekable(i == 0)
                .build()
        }
        val playing = s.isPlaying
        return b.setPlaylist(items)
            .setCurrentMediaItemIndex(0)
            .setPlayWhenReady(s.playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setPlaybackState(if (s.isBuffering) Player.STATE_BUFFERING else Player.STATE_READY)
            .setContentPositionMs(PositionSupplier.getExtrapolating(remote.positionNow() ?: s.positionMs, if (playing) 1f else 0f))
            .setShuffleModeEnabled(s.isShuffleEnabled)
            .setRepeatMode(
                when (s.repeatMode) {
                    RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                    RepeatMode.ONE -> Player.REPEAT_MODE_ONE
                    RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                },
            )
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) remote.play() else remote.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> remote.next()
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> remote.previous()
            Player.COMMAND_SEEK_TO_MEDIA_ITEM -> if (mediaItemIndex <= 0) remote.seekTo(positionMs.coerceAtLeast(0)) else remote.skipTo(mediaItemIndex)
            else -> remote.seekTo(positionMs.coerceAtLeast(0))
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
        setVolumeTo(deviceVolume)
        return Futures.immediateVoidFuture()
    }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        setVolumeTo(((volume ?: 0.0) * MAX_VOLUME).roundToInt() + VOLUME_STEP)
        return Futures.immediateVoidFuture()
    }

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        setVolumeTo(((volume ?: 0.0) * MAX_VOLUME).roundToInt() - VOLUME_STEP)
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        remote.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    private fun setVolumeTo(level: Int) {
        val v = level.coerceIn(0, MAX_VOLUME) / MAX_VOLUME.toFloat()
        // Shown at once; the computer's next state confirms it.
        volume = v.toDouble()
        remote.setVolume(v)
        invalidateState()
    }

    companion object {
        const val MAX_VOLUME = 100
        private const val VOLUME_STEP = 5

        private val DEVICE = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMaxVolume(MAX_VOLUME).build()

        private val COMMANDS = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_DEVICE_VOLUME,
                Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
            )
            .build()
    }
}
