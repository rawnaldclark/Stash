package com.stash.core.media.streaming

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.StreamKey
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import java.io.IOException

/**
 * A media source whose periods are judged by [AddonStreamCheck] before the player may play them.
 *
 * **Why here, and why no wrong audio is heard.** A media period reports itself prepared
 * ([MediaPeriod.Callback.onPrepared]) once its tracks are known: for a progressive file, when the
 * extractor has read every track's format from the bytes and the file's length (media3's
 * `ProgressiveMediaPeriod.maybeFinishPrepare`, which refreshes the timeline with that length just
 * before it calls back); for DASH, from the manifest. That call comes on the player's playback
 * thread, and until the player hears it the period has no selected track and no sample stream: no
 * renderer can read a sample of it, so nothing of it can reach the speaker. [CheckedPeriod] judges
 * the stream there and, on a refusal, never passes the call on. The player sees a period that
 * failed to prepare ([AddonStreamRefusedException] from [MediaPeriod.maybeThrowPrepareError]), as
 * it would for a dead link: the song playing (or the one before a gapless join) plays on, and the
 * refused one is resolved again ([AddonStreamCheck.refusals], `PlayerRepositoryImpl`). An app-thread
 * listener (`onTracksChanged`) would be too late: by the time it runs a renderer may have started,
 * and for the next song of a gapless queue it runs only at the join, when that song is already
 * audible. This hook covers every player that uses the factory: the one playing, a gapless next
 * song read ahead in it, and the crossfade spare.
 *
 * Which stream a period plays is known up front for an item already resolved to an add-on's URL
 * ([claimOf] with the item's claim), and only once its data source opens for a `stash-resolve://`
 * placeholder: then the data source says what it opened ([OpenedStream]) and [claimOf] reads it.
 * A stream that is not an add-on's (claim null) is never judged.
 *
 * **A link whose add-on left.** A stream known before it opens (claim non-null without an open: an
 * add-on's URL, a manifest resolved before its source was picked) whose add-on is no longer on or is
 * set aside ([isGone]) is never opened by a new period: a repeat of the song, a replay of it, a prepare
 * after an error. That period fails with [AddonStreamGoneException] before it opens anything, and
 * `PlayerRepositoryImpl` resolves the song again from where it was. A period made before (the song
 * playing when the add-on left) plays on to its end, its seeks included (review-888 R2-m3).
 *
 * Threading: [createPeriod], [releasePeriod], [onChildSourceInfoRefreshed] and the period's
 * callbacks run on the playback thread.
 */
@OptIn(UnstableApi::class)
internal class AddonCheckedMediaSource(
    child: MediaSource,
    private val check: AddonStreamCheck,
    /** Whether the add-on of this origin (`addon:<id>`) left or is set aside. Any thread. */
    private val isGone: (String) -> Boolean = { false },
    /** The claim of the stream a period plays, from what its data source opened (null: not known yet, or not a placeholder). */
    private val claimOf: (StreamUrl?) -> AddonStreamCheck.Claim?,
) : WrappingMediaSource(child) {

    /** The child's length as its timeline last said; null when unknown. Playback thread. */
    private var durationMs: Long? = null

    override fun onChildSourceInfoRefreshed(newTimeline: Timeline) {
        durationMs = windowDurationMs(newTimeline)
        refreshSourceInfo(newTimeline)
    }

    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod {
        val opened = OpenedStream()
        val lengthBefore = durationMs
        // The child's period makes its data source now, on this thread: a placeholder's says what it opens.
        val inner = OpenedStream.during(opened) { super.createPeriod(id, allocator, startPositionUs) }
        // Known before it opens, and its add-on gone since: this new period never opens it.
        val known = claimOf(null)
        if (known != null && isGone(known.origin)) {
            android.util.Log.i(TAG, "track ${known.trackId}: its add-on is no longer on or is set aside; its link is not opened again")
            return CheckedPeriod(inner, failed = AddonStreamGoneException(known.trackId)) { null }
        }
        return CheckedPeriod(inner) { groups ->
            claimOf(opened.stream)?.let { claim -> check.judge(claim, mimeTypesOf(groups), lengthOf(opened, lengthBefore)) }
        }
    }

    /**
     * The length of the stream the period was prepared from. An item's own URL is one file, so its
     * timeline's length is that file's. A placeholder ([OpenedStream.stream] set) may open another
     * stream each time it is prepared again (a retry, a prepare after an error), and media3's
     * progressive source keeps the last length it knew when a new stream reports none: only a length
     * this period's own prepare reported counts, else it is unknown and the length rule is skipped
     * (review-888 m8). A new stream exactly as long as the last one is skipped too: it was judged then.
     */
    private fun lengthOf(opened: OpenedStream, lengthBefore: Long?): Long? =
        if (opened.stream == null) durationMs else durationMs?.takeIf { it != lengthBefore }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        super.releasePeriod((mediaPeriod as CheckedPeriod).inner)
    }

    private companion object {
        const val TAG = "AddonStreamCheck"

        fun windowDurationMs(timeline: Timeline): Long? {
            if (timeline.isEmpty) return null
            return timeline.getWindow(0, Timeline.Window()).durationMs.takeIf { it != C.TIME_UNSET && it > 0L }
        }

        fun mimeTypesOf(groups: TrackGroupArray): List<String?> = buildList {
            for (g in 0 until groups.length) {
                val group = groups.get(g)
                for (i in 0 until group.length) add(group.getFormat(i).sampleMimeType)
            }
        }
    }
}

/**
 * What a `stash-resolve://` placeholder's data source opened, for the one period it reads for.
 * Written on the loader thread when the stream opens, read on the playback thread when the period
 * is prepared (after the bytes it was prepared from were read through that open).
 */
internal class OpenedStream {
    @Volatile var stream: StreamUrl? = null

    companion object {
        /**
         * The period being created on this thread, while [AddonCheckedMediaSource] creates it. A
         * period's data source is made inside its source's `createPeriod`, on the same thread
         * (media3's `ProgressiveMediaSource.createPeriod`), so the data source made then is that
         * period's. One per thread: each player has its own playback thread.
         */
        private val creating = ThreadLocal<OpenedStream?>()

        /** The period whose data source is being made right now on this thread, if one is being checked. */
        fun beingCreated(): OpenedStream? = creating.get()

        /** Runs [block] with [opened] as the period being created on this thread. */
        fun <T> during(opened: OpenedStream, block: () -> T): T {
            val outer = creating.get()
            creating.set(opened)
            try {
                return block()
            } finally {
                if (outer == null) creating.remove() else creating.set(outer)
            }
        }
    }
}

/**
 * What the player's error says when [AddonStreamCheck] refused a stream: the rule only, never the
 * host or URL.
 *
 * The error reaches `PlayerRepositoryImpl` through the media session, which sends a cause as its
 * class name and message and makes it again from a `(String)` constructor: hence the one here, and
 * [isIn], which knows it by either.
 */
class AddonStreamRefusedException(message: String) : IOException(message) {
    constructor(why: AddonStreamCheck.Why) : this(MESSAGE_PREFIX + why.word)

    companion object {
        private const val MESSAGE_PREFIX = "an add-on's stream was refused: "

        /** Whether [error], or what caused it, is a refusal of an add-on's stream. */
        fun isIn(error: Throwable?): Boolean = generateSequence(error) { it.cause }.take(MAX_CAUSES).any {
            it is AddonStreamRefusedException || it.message?.startsWith(MESSAGE_PREFIX) == true
        }

        private const val MAX_CAUSES = 8
    }
}

/**
 * What the player's error says when a new play of a link was not opened because the add-on that
 * handed it out is no longer on, or is set aside ([AddonCheckedMediaSource]): the song's id only,
 * never the host or URL. Known by its message too, as [AddonStreamRefusedException] is.
 */
class AddonStreamGoneException(message: String) : IOException(message) {
    constructor(trackId: Long) : this(MESSAGE_PREFIX + trackId)

    companion object {
        private const val MESSAGE_PREFIX = "a link from an add-on that is no longer on was not opened: track "

        /** Whether [error], or what caused it, is such a link not opened. */
        fun isIn(error: Throwable?): Boolean = generateSequence(error) { it.cause }.take(MAX_CAUSES).any {
            it is AddonStreamGoneException || it.message?.startsWith(MESSAGE_PREFIX) == true
        }

        private const val MAX_CAUSES = 8
    }
}

/**
 * [inner], prepared only if [judge] lets it play ([AddonCheckedMediaSource]). A refused period
 * never reports itself prepared, asks for no more loading, and throws [AddonStreamRefusedException]
 * from [maybeThrowPrepareError]: the player fails it as it would a period whose link is dead.
 * One made [failed] (its add-on is gone: [AddonStreamGoneException]) never prepares [inner] at all,
 * so its link is never opened.
 */
@OptIn(UnstableApi::class)
internal class CheckedPeriod(
    val inner: MediaPeriod,
    failed: IOException? = null,
    /** Judges the stream by its tracks; non-null: refused, and why. Called once, on the playback thread. */
    private val judge: (TrackGroupArray) -> AddonStreamCheck.Why?,
) : MediaPeriod, MediaPeriod.Callback {

    private var callback: MediaPeriod.Callback? = null

    /** Set when the stream was refused, or from the start ([failed]); from then on the period is a failed one. */
    @Volatile private var refused: IOException? = failed

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        this.callback = callback
        if (refused != null) return // its link is not opened
        inner.prepare(this, positionUs)
    }

    override fun onPrepared(mediaPeriod: MediaPeriod) {
        val why = judge(inner.trackGroups)
        if (why != null) {
            refused = AddonStreamRefusedException(why)
            return
        }
        callback?.onPrepared(this)
    }

    override fun onContinueLoadingRequested(source: MediaPeriod) {
        if (refused == null) callback?.onContinueLoadingRequested(this)
    }

    override fun maybeThrowPrepareError() {
        refused?.let { throw it }
        inner.maybeThrowPrepareError()
    }

    override fun getTrackGroups(): TrackGroupArray = inner.trackGroups

    override fun getStreamKeys(trackSelections: List<ExoTrackSelection>): List<StreamKey> = inner.getStreamKeys(trackSelections)

    override fun selectTracks(
        selections: Array<ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long,
    ): Long = inner.selectTracks(selections, mayRetainStreamFlags, streams, streamResetFlags, positionUs)

    override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) = inner.discardBuffer(positionUs, toKeyframe)

    override fun readDiscontinuity(): Long = inner.readDiscontinuity()

    override fun seekToUs(positionUs: Long): Long = inner.seekToUs(positionUs)

    override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long =
        inner.getAdjustedSeekPositionUs(positionUs, seekParameters)

    override fun getBufferedPositionUs(): Long = inner.bufferedPositionUs

    /** A refused stream asks for no more loading: its loader stops at its next check, and its source is released when the slot is put back. */
    override fun getNextLoadPositionUs(): Long = if (refused != null) C.TIME_END_OF_SOURCE else inner.nextLoadPositionUs

    override fun continueLoading(loadingInfo: LoadingInfo): Boolean = refused == null && inner.continueLoading(loadingInfo)

    override fun isLoading(): Boolean = refused == null && inner.isLoading

    override fun reevaluateBuffer(positionUs: Long) = inner.reevaluateBuffer(positionUs)
}
