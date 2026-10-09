package com.stash.core.media.cast

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ForwardingTimeline
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.stash.core.media.service.StashPlaybackService
import kotlin.math.roundToInt

/**
 * What the MediaSession holds while casting (spec 2026-10-06 §3).
 *
 * The phone keeps the queue: the timeline, current index, shuffle order,
 * repeat mode and metadata all come from the wrapped local player, which sits
 * stopped (nothing plays or buffers on the phone). The speaker holds only the
 * current song: play/pause, seeks within the song, position, duration, errors
 * and volume go to and come from [CastRemote]. When the current song changes —
 * a skip, a queue edit, the speaker finishing — the new one is loaded there.
 * Once a song plays, the next one is queued on the speaker ([CastRemote.setNext])
 * so it moves on without a gap; when it does, the local queue follows.
 * Every song is [prepare]d before the speaker sees it, so a stream that still
 * has to be resolved never keeps the speaker's request waiting.
 *
 * Every controller (Now Playing via PlayerRepositoryImpl, the notification,
 * the lock screen, Bluetooth, Android Auto) reaches the speaker through here,
 * the same seam [com.stash.core.media.listen.ListenTogetherPlayer] uses.
 *
 * One instance per service, re-pointed with [attach]/[detach]:
 * ForwardingSimpleBasePlayer listens to the wrapped player from construction
 * and has no detach short of release().
 *
 * Main thread only.
 */
@OptIn(UnstableApi::class)
class CastSessionPlayer(
    local: Player,
    /** Builds what the speaker loads for an item, or null when it can't be served. */
    private val mediaFor: (item: MediaItem, contentId: String) -> CastMedia?,
    /**
     * Gets [item]'s bytes ready to serve (resolves a stream that hasn't been
     * yet), then calls back on the main thread: true when it can be served.
     * Immediate for local files.
     */
    private val prepare: (item: MediaItem, done: (Boolean) -> Unit) -> Unit = { _, done -> done(true) },
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : ForwardingSimpleBasePlayer(local) {

    private var remote: CastRemote? = null

    /** What the user wants: the session's playWhenReady. The local player stays paused regardless. */
    private var wantPlay = false
    private var playWhenReadyReason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST

    /** The song the speaker was last told to load: its mediaId, and the contentId that load carried. */
    private var loadedMediaId: String? = null
    private var loadedContentId: String? = null
    private var loadCounter = 0

    /**
     * The contentId whose first status we've seen. A play/pause that arrives
     * while a load is in flight is applied then, against what the speaker
     * actually reports, instead of racing the load.
     */
    private var syncedContentId: String? = null

    /** The contentId whose FINISHED we already acted on — status repeats it until the next load. */
    private var finishedContentId: String? = null

    /** The song queued on the speaker after the current one, and where it sits in the local queue. */
    private data class Queued(val contentId: String, val mediaId: String, val index: Int)
    private var queued: Queued? = null

    /** Bumped whenever a queued-next request goes stale, so its late prepare callback is dropped. */
    private var nextRequest = 0
    private var nextCounter = 0

    /** The queue ran out on the speaker (no next item, repeat off). */
    private var ended = false
    private var error: PlaybackException? = null
    private var lastPositionMs = 0L

    /**
     * When [lastPositionMs] was taken, and whether the song was playing then.
     * The receiver sends status on changes only, not as it plays, and once its
     * session is suspended or ended the SDK drops the media client, so the
     * status has no position at all. Playback then continues from
     * [lastPositionMs] moved on by the time since, not from where the last
     * status (maybe minutes ago) left it: handing back to the phone after a
     * lost connection, or moving to a receiver the SDK resumed as a new session.
     */
    private var positionTakenAtMs = 0L
    private var positionAdvancing = false
    private var remoteDurationMs = 0L

    /** When we last sent a play/pause; a status inside the grace window is ours echoing back, not the speaker's own. */
    private var lastCommandAtMs = Long.MIN_VALUE / 2

    private val remoteListener: () -> Unit = { onRemoteStatus() }

    private val localListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            reconcile()
            scheduleNext()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            reconcile()
            scheduleNext()
        }

        // Both change which song comes next.
        override fun onRepeatModeChanged(repeatMode: Int) = scheduleNext()
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = scheduleNext()
    }

    val isAttached: Boolean get() = remote != null

    /** Whether this plays through [remote] now (the service re-points it when the receiver changes). */
    fun isAttachedTo(remote: CastRemote): Boolean = this.remote === remote

    /**
     * Starts casting to [remote], picking up where [local] is. The caller has
     * already stopped [local]; [positionMs] and [playWhenReady] are what it
     * was doing before.
     */
    fun attach(local: Player, remote: CastRemote, positionMs: Long, playWhenReady: Boolean) {
        if (local !== player) setPlayer(local)
        detachRemote()
        this.remote = remote
        wantPlay = playWhenReady
        playWhenReadyReason = Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE
        ended = false
        error = null
        notePosition(positionMs.coerceAtLeast(0), advancing = false)
        remote.addListener(remoteListener)
        local.addListener(localListener)
        if (local.currentMediaItem != null) load(lastPositionMs) else invalidateState()
    }

    /**
     * Where the speaker got to, for handing playback back to the phone. The
     * phone always takes over paused (spec §3), so play state isn't carried.
     */
    data class Handoff(val mediaItemIndex: Int, val positionMs: Long)

    /**
     * Stops listening to the speaker and reports where it was. Doesn't stop the
     * speaker itself: when the session ended the receiver is already gone.
     */
    fun detach(): Handoff {
        val handoff = Handoff(player.currentMediaItemIndex, currentPositionMs())
        detachRemote()
        player.removeListener(localListener)
        loadedMediaId = null
        loadedContentId = null
        syncedContentId = null
        finishedContentId = null
        dropQueued()
        invalidateState()
        return handoff
    }

    private fun detachRemote() {
        remote?.removeListener(remoteListener)
        remote = null
    }

    // ── Loading ─────────────────────────────────────────────────────────

    /**
     * Loads the local player's current item on the speaker, once [prepare] has
     * it ready. Until then the speaker isn't asked; the player reports
     * buffering. A newer load, or the speaker going away, drops this one.
     */
    private fun load(positionMs: Long) {
        val target = remote ?: return
        val item = player.currentMediaItem
        dropQueued() // a load replaces the receiver's whole queue
        if (item == null) {
            target.stop()
            loadedMediaId = null
            loadedContentId = null
            invalidateState()
            return
        }
        val contentId = "${item.mediaId}#${++loadCounter}"
        loadedMediaId = item.mediaId
        loadedContentId = contentId
        finishedContentId = null
        remoteDurationMs = 0L
        notePosition(positionMs.coerceAtLeast(0), advancing = false)
        ended = false
        error = null
        prepare(item) { ready ->
            if (remote === target && loadedContentId == contentId) send(target, item, contentId, ready)
        }
        invalidateState()
    }

    private fun send(target: CastRemote, item: MediaItem, contentId: String, ready: Boolean) {
        val media = if (ready) mediaFor(item, contentId) else null
        if (media == null) {
            error = if (ready) {
                PlaybackException("This song can't be sent to the speaker", null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
            } else {
                PlaybackException("This song couldn't be loaded for the speaker", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
            }
        } else {
            // wantPlay as it is now: a pause may have arrived while the song was prepared.
            target.load(media, lastPositionMs, wantPlay)
            lastCommandAtMs = clock()
        }
        invalidateState()
    }

    /**
     * Queues the song after the current one on the speaker, or takes a queued
     * one back when the queue, shuffle or repeat changed what comes next. Only
     * while the current song is playing there: a load in flight replaces the
     * receiver's queue anyway. Repeat-one queues nothing; FINISHED restarts it.
     */
    private fun scheduleNext() {
        val target = remote ?: return
        if (loadedContentId == null || syncedContentId != loadedContentId || ended || error != null) return
        val local = player
        val nextIndex = if (local.repeatMode == Player.REPEAT_MODE_ONE) C.INDEX_UNSET else local.nextMediaItemIndex
        val item = nextIndex.takeIf { it != C.INDEX_UNSET }?.let { runCatching { local.getMediaItemAt(it) }.getOrNull() }
        val current = queued
        if (item != null && current != null && current.mediaId == item.mediaId && current.index == nextIndex) return
        if (current != null) target.setNext(null)
        dropQueued()
        if (item == null) return
        val request = nextRequest
        val contentId = "${item.mediaId}#n${++nextCounter}"
        prepare(item) { ready ->
            if (remote !== target || request != nextRequest || !ready) return@prepare
            val media = mediaFor(item, contentId) ?: return@prepare
            queued = Queued(contentId, item.mediaId, nextIndex)
            target.setNext(media)
        }
    }

    private fun dropQueued() {
        queued = null
        nextRequest++
    }

    /**
     * The speaker moved on to the song we queued: that is the current song now.
     * The local queue follows without a reload — reconcile sees the same mediaId.
     */
    private fun onSpeakerAdvanced(next: Queued) {
        queued = null
        loadedMediaId = next.mediaId
        loadedContentId = next.contentId
        syncedContentId = null
        finishedContentId = null
        remoteDurationMs = 0L
        notePosition(0L, advancing = false)
        ended = false
        error = null
        val local = player
        val index = next.index.takeIf {
            it < local.mediaItemCount && runCatching { local.getMediaItemAt(it).mediaId }.getOrNull() == next.mediaId
        } ?: local.nextMediaItemIndex
        if (index != C.INDEX_UNSET) local.seekTo(index, 0L)
    }

    /** The local current item changed under us (skip, queue edit): follow it on the speaker. */
    private fun reconcile() {
        if (remote == null) return
        val item = player.currentMediaItem
        if (item == null) {
            if (loadedMediaId != null) load(0L)
            return
        }
        // Same song (the prefetch swaps a placeholder for a resolved URL in
        // place; a queue edit elsewhere shifts the index): leave it playing.
        if (item.mediaId == loadedMediaId) return
        load(player.currentPosition.coerceAtLeast(0))
    }

    /** The speaker finished a song: move the local queue on, the way ExoPlayer would. */
    private fun advance() {
        val local = player
        if (local.repeatMode == Player.REPEAT_MODE_ONE) {
            load(0L)
            return
        }
        val next = local.nextMediaItemIndex
        if (next == C.INDEX_UNSET) {
            ended = true
            notePosition(remoteDurationMs.takeIf { it > 0 } ?: lastPositionMs, advancing = false)
            invalidateState()
            return
        }
        wantPlay = true
        seekLocalAndLoad(next, 0L)
    }

    /**
     * Moves the stopped local queue to [index] and makes sure the speaker
     * follows. seekTo normally fires localListener → reconcile, which loads
     * the new song; when it doesn't (the same song again: repeat-all over one
     * item, a duplicate in the queue) load explicitly.
     */
    private fun seekLocalAndLoad(index: Int, positionMs: Long) {
        val before = loadCounter
        player.seekTo(index, positionMs)
        if (loadCounter == before) load(positionMs)
    }

    private fun onRemoteStatus() {
        val status = remote?.status ?: return
        queued?.let { if (status.contentId == it.contentId) onSpeakerAdvanced(it) }
        if (playsSomethingElse(status)) {
            // The speaker went on to a song we no longer meant to queue (one
            // left behind in its queue). Our song is over there, so move on the
            // way a finished song does, which loads what the phone has next.
            finishedContentId = loadedContentId
            advance()
            return
        }
        if (status.contentId != null && status.contentId == loadedContentId) {
            notePosition(status.positionMs, advancing = status.playerState == CastStatus.PlayerState.PLAYING)
            if (status.durationMs > 0) remoteDurationMs = status.durationMs
            val playing = status.playerState == CastStatus.PlayerState.PLAYING
            if (syncedContentId != loadedContentId && (playing || status.playerState == CastStatus.PlayerState.PAUSED)) {
                // First word from the speaker on this song: bring it in line with
                // any play/pause that arrived while the load was in flight.
                syncedContentId = loadedContentId
                if (playing != wantPlay) {
                    remote?.let { if (wantPlay) it.play() else it.pause() }
                    lastCommandAtMs = clock()
                }
                scheduleNext()
                invalidateState()
                return
            }
            val echoWindow = clock() - lastCommandAtMs < ECHO_GRACE_MS
            when (status.playerState) {
                CastStatus.PlayerState.PLAYING -> if (!wantPlay && !echoWindow) {
                    // Played from elsewhere (the Google Home app, the speaker's own button).
                    wantPlay = true
                    playWhenReadyReason = Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE
                }
                CastStatus.PlayerState.PAUSED -> if (wantPlay && !echoWindow) {
                    wantPlay = false
                    playWhenReadyReason = Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE
                }
                CastStatus.PlayerState.IDLE -> when (status.idleReason) {
                    CastStatus.IdleReason.FINISHED -> if (finishedContentId != loadedContentId) {
                        finishedContentId = loadedContentId
                        advance()
                        return
                    }
                    CastStatus.IdleReason.ERROR -> if (error == null) {
                        error = PlaybackException(
                            "The speaker couldn't play this song",
                            null,
                            PlaybackException.ERROR_CODE_REMOTE_ERROR,
                        )
                    }
                    else -> Unit
                }
                else -> Unit
            }
        }
        invalidateState()
    }

    /**
     * True when the speaker plays a song that is neither ours nor queued, after
     * ours had started there. During our own load the previous song can still be
     * reported for a moment; that load isn't synced yet, so it doesn't count.
     */
    private fun playsSomethingElse(status: CastStatus): Boolean {
        val id = status.contentId ?: return false
        if (id == loadedContentId || id == queued?.contentId) return false
        if (loadedContentId == null || syncedContentId != loadedContentId || ended || error != null) return false
        return status.playerState == CastStatus.PlayerState.PLAYING ||
            status.playerState == CastStatus.PlayerState.BUFFERING
    }

    private fun currentPositionMs(): Long {
        val status = remote?.status
        return if (!ended && status != null && status.contentId != null && status.contentId == loadedContentId &&
            status.playerState != CastStatus.PlayerState.IDLE
        ) {
            status.positionMs
        } else {
            extrapolatedPositionMs()
        }
    }

    private fun notePosition(positionMs: Long, advancing: Boolean) {
        lastPositionMs = positionMs
        positionTakenAtMs = clock()
        positionAdvancing = advancing
    }

    /** [lastPositionMs], moved on by the time since while the song was playing (see [positionTakenAtMs]). */
    private fun extrapolatedPositionMs(): Long {
        if (!positionAdvancing || !wantPlay || ended) return lastPositionMs
        val moved = lastPositionMs + (clock() - positionTakenAtMs).coerceAtLeast(0)
        return if (remoteDurationMs > 0) minOf(moved, remoteDurationMs) else moved
    }

    // ── State ───────────────────────────────────────────────────────────

    override fun getState(): State {
        val base = super.getState()
        val target = remote ?: return base
        val status = target.status
        val timeline = base.timeline
        val ours = status.contentId != null && status.contentId == loadedContentId
        val playbackState = when {
            timeline.isEmpty -> if (ended) Player.STATE_ENDED else Player.STATE_IDLE
            error != null -> Player.STATE_IDLE
            ended -> Player.STATE_ENDED
            loadedContentId == null -> Player.STATE_IDLE
            !ours -> Player.STATE_BUFFERING // our load is on its way
            else -> when (status.playerState) {
                CastStatus.PlayerState.LOADING, CastStatus.PlayerState.BUFFERING -> Player.STATE_BUFFERING
                CastStatus.PlayerState.PLAYING, CastStatus.PlayerState.PAUSED -> Player.STATE_READY
                CastStatus.PlayerState.IDLE -> when {
                    status.idleReason == CastStatus.IdleReason.FINISHED -> Player.STATE_BUFFERING // advancing
                    // Receivers report IDLE for a moment before LOADING; until the song has
                    // played or paused once, that is still our load on its way.
                    syncedContentId != loadedContentId && status.idleReason == CastStatus.IdleReason.NONE ->
                        Player.STATE_BUFFERING
                    else -> Player.STATE_IDLE
                }
            }
        }

        // The deprecated, flag-less volume commands too: controllers built
        // against older media3 versions still send those.
        @Suppress("DEPRECATION")
        val commands = base.availableCommands.buildUpon()
            .removeAll(Player.COMMAND_SET_SPEED_AND_PITCH, Player.COMMAND_SET_VOLUME)
            .addAll(
                Player.COMMAND_GET_DEVICE_VOLUME,
                Player.COMMAND_SET_DEVICE_VOLUME, Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                Player.COMMAND_ADJUST_DEVICE_VOLUME, Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
            )
            .build()

        val builder = base.buildUpon()
            .setAvailableCommands(commands)
            .setPlaybackState(playbackState)
            .setPlayWhenReady(wantPlay, playWhenReadyReason)
            .setPlayerError(if (playbackState == Player.STATE_IDLE) error else null)
            .setIsLoading(playbackState == Player.STATE_BUFFERING)
            .setPlaybackParameters(PlaybackParameters.DEFAULT)
            .setContentPositionMs { currentPositionMs() }
            .setContentBufferedPositionMs { currentPositionMs() }
            .setTotalBufferedDurationMs(PositionSupplier.ZERO)
            .setDeviceInfo(REMOTE_DEVICE)
            .setDeviceVolume((status.volume * MAX_VOLUME).roundToInt().coerceIn(0, MAX_VOLUME))
            .setIsDeviceMuted(status.muted)

        // The stopped local player may never have learned this song's length;
        // the speaker does, and until then the track row did.
        val index = base.currentMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: 0
        if (!timeline.isEmpty && index < timeline.windowCount) {
            val window = timeline.getWindow(index, Timeline.Window())
            val knownMs = remoteDurationMs.takeIf { ours && it > 0 }
                ?: window.mediaItem.mediaMetadata.durationMs?.takeIf { it > 0 }
                ?: window.mediaItem.mediaMetadata.extras?.getLong(StashPlaybackService.EXTRA_TRACK_DURATION_MS, 0L)
                    ?.takeIf { it > 0 }
            if (knownMs != null && window.durationUs != knownMs * 1000) {
                builder.setPlaylist(DurationOverrideTimeline(timeline, index, knownMs * 1000), base.currentTracks, base.currentMetadata)
            }
        }
        return builder.build()
    }

    // ── Commands ────────────────────────────────────────────────────────

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        val target = remote ?: return super.handleSetPlayWhenReady(playWhenReady)
        wantPlay = playWhenReady
        playWhenReadyReason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
        val status = target.status
        val ours = status.contentId != null && status.contentId == loadedContentId
        val preLoadIdle = ours && status.playerState == CastStatus.PlayerState.IDLE &&
            status.idleReason == CastStatus.IdleReason.NONE && syncedContentId != loadedContentId
        val onSpeaker = loadedContentId != null && error == null && !ended &&
            (!ours || preLoadIdle || status.playerState != CastStatus.PlayerState.IDLE)
        when {
            // Loaded and heard from: tell it. Still loading: the first status applies wantPlay.
            onSpeaker -> if (ours && syncedContentId == loadedContentId) {
                if (playWhenReady) target.play() else target.pause()
                lastCommandAtMs = clock()
            }
            // Nothing on the speaker (stopped, failed, or never loaded): play loads it.
            playWhenReady && !ended && player.currentMediaItem != null -> load(lastPositionMs)
        }
        invalidateState()
        return DONE
    }

    /** The local player must never prepare while casting; prepare means "load it on the speaker" here. */
    override fun handlePrepare(): ListenableFuture<*> {
        if (remote == null) return super.handlePrepare()
        if (loadedContentId == null || error != null) load(lastPositionMs)
        return DONE
    }

    override fun handleStop(): ListenableFuture<*> {
        val target = remote ?: return super.handleStop()
        target.stop()
        notePosition(currentPositionMs(), advancing = false)
        loadedMediaId = null
        loadedContentId = null
        dropQueued()
        invalidateState()
        return DONE
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val target = remote ?: return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        val position = if (positionMs == C.TIME_UNSET) 0L else positionMs.coerceAtLeast(0)
        val local = player
        val sameItem = mediaItemIndex == C.INDEX_UNSET || mediaItemIndex == local.currentMediaItemIndex
        if (sameItem && local.currentMediaItem?.mediaId == loadedMediaId) {
            val status = target.status
            if (!ended && error == null && status.contentId == loadedContentId &&
                status.playerState != CastStatus.PlayerState.IDLE
            ) {
                target.seekTo(position)
                notePosition(position, positionAdvancing)
            } else {
                // Finished, failed or stopped: seeking starts it again from there.
                load(position)
            }
            invalidateState()
            return DONE
        }
        // The song already queued on the speaker (the usual "next"): it is
        // buffered there, so jumping to it starts at once, where a fresh load
        // would fetch it again from the start.
        val next = queued
        if (next != null && mediaItemIndex == next.index && position == 0L && !ended && error == null &&
            target.playNext()
        ) {
            lastCommandAtMs = clock()
            onSpeakerAdvanced(next)
            invalidateState()
            return DONE
        }
        // Another song: move the local queue (stopped, so instant) and load it there.
        seekLocalAndLoad(mediaItemIndex, position)
        return DONE
    }

    /**
     * A new queue is a request to play its start item from its start position,
     * even when that is the song the speaker already holds: reconcile leaves
     * the same song alone (a placeholder swap, a queue edit), so tapping the
     * song that just finished, or the one playing, would otherwise do nothing
     * or carry on mid-song. Loads here unless the queue change already did.
     */
    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        if (remote == null) return super.handleSetMediaItems(mediaItems, startIndex, startPositionMs)
        val before = loadCounter
        val result = super.handleSetMediaItems(mediaItems, startIndex, startPositionMs)
        if (loadCounter == before && player.currentMediaItem != null) load(player.currentPosition.coerceAtLeast(0))
        return result
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> =
        if (remote == null) super.handleSetPlaybackParameters(playbackParameters) else DONE

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
        val target = remote ?: return super.handleSetDeviceVolume(deviceVolume, flags)
        target.setVolume(deviceVolume.coerceIn(0, MAX_VOLUME) / MAX_VOLUME.toFloat())
        return DONE
    }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        val target = remote ?: return super.handleIncreaseDeviceVolume(flags)
        target.setVolume(((target.status.volume * MAX_VOLUME).roundToInt() + 1).coerceAtMost(MAX_VOLUME) / MAX_VOLUME.toFloat())
        return DONE
    }

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        val target = remote ?: return super.handleDecreaseDeviceVolume(flags)
        target.setVolume(((target.status.volume * MAX_VOLUME).roundToInt() - 1).coerceAtLeast(0) / MAX_VOLUME.toFloat())
        return DONE
    }

    override fun handleSetDeviceMuted(muted: Boolean, flags: Int): ListenableFuture<*> {
        val target = remote ?: return super.handleSetDeviceMuted(muted, flags)
        target.setMuted(muted)
        return DONE
    }

    /** Never release the service's ExoPlayer from here; the crossfade engine owns it. */
    override fun handleRelease(): ListenableFuture<*> = DONE

    /** [Timeline] with one window's duration filled in. */
    private class DurationOverrideTimeline(
        timeline: Timeline,
        private val windowIndex: Int,
        private val durationUs: Long,
    ) : ForwardingTimeline(timeline) {
        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
            super.getWindow(windowIndex, window, defaultPositionProjectionUs)
            if (windowIndex == this.windowIndex) window.durationUs = durationUs
            return window
        }

        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
            super.getPeriod(periodIndex, period, setIds)
            if (period.windowIndex == windowIndex) period.durationUs = durationUs
            return period
        }
    }

    companion object {
        const val MAX_VOLUME = 20
        private const val ECHO_GRACE_MS = 2_000L
        private val DONE: ListenableFuture<*> = Futures.immediateVoidFuture()
        private val REMOTE_DEVICE = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMaxVolume(MAX_VOLUME).build()
    }
}
