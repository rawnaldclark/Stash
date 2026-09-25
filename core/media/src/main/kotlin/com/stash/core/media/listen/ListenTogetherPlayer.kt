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
 * room replies. A listener's transport commands are withdrawn entirely. With no interceptor
 * (outside a session) every call passes straight through.
 *
 * SimpleBasePlayer reports a SEEK discontinuity after every intercepted seek even though the
 * position did not move. Harmless: the next state read shows the real position.
 *
 * ponytail: one instance per service, rewrapped on each session. ForwardingSimpleBasePlayer
 * listens to the wrapped player from construction and has no detach short of release().
 */
@OptIn(UnstableApi::class)
class ListenTogetherPlayer(player: Player) : ForwardingSimpleBasePlayer(player) {
    private var isHost = false
    private var interceptor: SessionInterceptor? = null

    /** Main thread only: invalidateState checks the application looper. */
    fun configure(isHost: Boolean, interceptor: SessionInterceptor?) {
        this.isHost = isHost
        this.interceptor = interceptor
        invalidateState()
    }

    fun rewrap(player: Player) = setPlayer(player)

    override fun getState(): State {
        val state = super.getState()
        if (interceptor == null) return state
        val commands = state.availableCommands.buildUpon()
        if (isHost) commands.addAll(*HOST_ADDS) else commands.removeAll(*LISTENER_REMOVES)
        return state.buildUpon().setAvailableCommands(commands.build()).build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        val target = interceptor ?: return super.handleSetPlayWhenReady(playWhenReady)
        if (isHost) if (playWhenReady) target.onPlay() else target.onPause()
        return DONE
    }

    /** The session loads and prepares the local player itself; a controller's prepare must not restart it. */
    override fun handlePrepare(): ListenableFuture<*> = if (interceptor == null) super.handlePrepare() else DONE

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val target = interceptor ?: return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        if (isHost) {
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
        val target = interceptor ?: return super.handleStop()
        if (isHost) target.onPause()
        return DONE
    }

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        val target = interceptor ?: return super.handleSetMediaItems(mediaItems, startIndex, startPositionMs)
        target.onSet(mediaItems, startIndex)
        return DONE
    }

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        val target = interceptor ?: return super.handleAddMediaItems(index, mediaItems)
        target.onAdd(mediaItems)
        return DONE
    }

    // The room owns the queue, and the session owns speed (drift correction) and the single item.
    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> =
        if (interceptor == null) super.handleRemoveMediaItems(fromIndex, toIndex) else DONE
    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> =
        if (interceptor == null) super.handleMoveMediaItems(fromIndex, toIndex, newIndex) else DONE
    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>): ListenableFuture<*> =
        if (interceptor == null) super.handleReplaceMediaItems(fromIndex, toIndex, mediaItems) else DONE
    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> =
        if (interceptor == null) super.handleSetShuffleModeEnabled(shuffleModeEnabled) else DONE
    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> =
        if (interceptor == null) super.handleSetRepeatMode(repeatMode) else DONE
    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> =
        if (interceptor == null) super.handleSetPlaybackParameters(playbackParameters) else DONE

    /** Never release the service's ExoPlayer from here; the crossfade engine owns it. */
    override fun handleRelease(): ListenableFuture<*> = DONE

    private companion object {
        val DONE: ListenableFuture<*> = Futures.immediateVoidFuture()
        val HOST_ADDS = intArrayOf(
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )

        /**
         * Everything but volume, metadata, COMMAND_CHANGE_MEDIA_ITEMS (a listener's "Suggest" rides
         * on add) and COMMAND_SET_MEDIA_ITEM (a single-song tap reaches onSet, which explains the refusal).
         */
        val LISTENER_REMOVES = intArrayOf(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SET_SPEED_AND_PITCH, Player.COMMAND_SET_SHUFFLE_MODE, Player.COMMAND_SET_REPEAT_MODE,
        )
    }
}
