package com.stash.core.media.listen

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * What the MediaSession exposes during a Listen Together session (spec §5). Every controller —
 * Now Playing via PlayerRepositoryImpl, the notification, the lock screen, headset and Bluetooth
 * buttons, Android Auto — reaches the player through here, so this is the one place that catches
 * them all. The host's commands become room messages and the local player moves only when the
 * room replies. A listener's transport commands are withdrawn entirely.
 *
 * ponytail: one instance per service, rewrapped on each session. ForwardingSimpleBasePlayer
 * listens to the wrapped player from construction and has no detach short of release().
 */
@OptIn(UnstableApi::class)
class ListenTogetherPlayer(player: Player) : ForwardingSimpleBasePlayer(player) {
    private var isHost = false
    private var interceptor: SessionInterceptor? = null

    fun configure(isHost: Boolean, interceptor: SessionInterceptor?) {
        this.isHost = isHost
        this.interceptor = interceptor
        invalidateState()
    }

    fun rewrap(player: Player) = setPlayer(player)

    override fun getState(): State {
        val state = super.getState()
        val commands = state.availableCommands.buildUpon()
        if (isHost) commands.addAll(*HOST_ADDS) else commands.removeAll(*LISTENER_REMOVES)
        return state.buildUpon().setAvailableCommands(commands.build()).build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (isHost) interceptor?.let { if (playWhenReady) it.onPlay() else it.onPause() }
        return DONE
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val target = interceptor
        if (isHost && target != null) {
            when (seekCommand) {
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> target.onNext()
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> target.onPrevious()
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD ->
                    target.onSeek(positionMs.coerceAtLeast(0))
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION -> target.onSeek(0)
                else -> Unit // COMMAND_SEEK_TO_MEDIA_ITEM: the session player only ever holds one song
            }
        }
        return DONE
    }

    override fun handleStop(): ListenableFuture<*> {
        if (isHost) interceptor?.onPause()
        return DONE
    }

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        interceptor?.onSet(mediaItems, startIndex)
        return DONE
    }

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        interceptor?.onAdd(mediaItems)
        return DONE
    }

    // The room owns the queue, and the session owns speed (drift correction) and the single item.
    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> = DONE
    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> = DONE
    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>): ListenableFuture<*> = DONE
    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> = DONE
    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> = DONE
    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> = DONE

    /** Never release the service's ExoPlayer from here; the crossfade engine owns it. */
    override fun handleRelease(): ListenableFuture<*> = DONE

    private companion object {
        val DONE: ListenableFuture<*> = Futures.immediateVoidFuture()
        val HOST_ADDS = intArrayOf(
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )

        /** Everything but volume, metadata and COMMAND_CHANGE_MEDIA_ITEMS (a listener's "Suggest" rides on add). */
        val LISTENER_REMOVES = intArrayOf(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SET_SPEED_AND_PITCH, Player.COMMAND_SET_SHUFFLE_MODE, Player.COMMAND_SET_REPEAT_MODE,
            Player.COMMAND_SET_MEDIA_ITEM,
        )
    }
}
