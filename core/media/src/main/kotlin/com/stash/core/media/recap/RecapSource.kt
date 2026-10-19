package com.stash.core.media.recap

import com.stash.core.model.Track
import com.stash.core.model.isScannedSong
import com.stash.core.model.isUnavailableForDisplay

/**
 * Where a Recap story card's song is read from. Plain data (no Media3 / android.net types) so the
 * choice is unit-testable on the JVM; [ExoRecapPreviewEngine] turns it into a MediaItem.
 */
sealed interface RecapSource {
    /** A file on this phone: a plain path, a `file://` URI or a SAF `content://` URI. */
    data class Local(val path: String) : RecapSource

    /**
     * A song streamed through the same resolver chain the main player uses (a
     * `stash-resolve://track/<id>` placeholder, resolved at open time by LazyResolvingDataSource).
     * The ids ride along so a track with no Room row can still be resolved.
     */
    data class Stream(
        val trackId: Long,
        val youtubeId: String?,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val isrc: String?,
    ) : RecapSource
}

/** Why a card stays quiet instead of playing its song. Logged, never shown. */
enum class RecapSkipReason { OFFLINE, CELLULAR_REFUSED, UNAVAILABLE, LOCAL_FILE_MISSING }

/** The outcome of [recapSourceFor]: a source to play, or the reason the card stays quiet. */
sealed interface RecapSourceChoice {
    data class Play(val source: RecapSource) : RecapSourceChoice
    data class Skip(val reason: RecapSkipReason) : RecapSourceChoice
}

/**
 * Picks where [track] plays from, mirroring the main player's routing
 * (PlayerRepositoryImpl.toInitialQueueMediaItem / buildMediaItemForTrack):
 *  - a downloaded song whose file is really there plays from the file;
 *  - an All Songs (scanned) file plays from its file or not at all — never a stream matched by name;
 *  - a song the library marks unavailable is skipped;
 *  - anything else streams, but only with a connection, and on mobile data only when the user
 *    allows streaming on cellular. Offline or refused → the card is silently quiet (owner rule:
 *    no error toast on a story card).
 *
 * @param localPlayable whether [Track.filePath] exists and is big enough to be real audio
 *   (only consulted when the track records a download).
 * @param online [com.stash.core.media.streaming.ConnectivityMonitor.isConnected].
 * @param streamAllowedOnThisNetwork false on mobile data with "stream on cellular" off.
 */
internal fun recapSourceFor(
    track: Track,
    localPlayable: Boolean,
    online: Boolean,
    streamAllowedOnThisNetwork: Boolean,
): RecapSourceChoice {
    val path = track.filePath
    val hasRecordedFile = !path.isNullOrBlank() && (track.isDownloaded || track.isScannedSong)
    if (hasRecordedFile && localPlayable) return RecapSourceChoice.Play(RecapSource.Local(path!!))
    if (track.isScannedSong) return RecapSourceChoice.Skip(RecapSkipReason.LOCAL_FILE_MISSING)
    if (track.isUnavailableForDisplay) return RecapSourceChoice.Skip(RecapSkipReason.UNAVAILABLE)
    if (!online) return RecapSourceChoice.Skip(RecapSkipReason.OFFLINE)
    if (!streamAllowedOnThisNetwork) return RecapSourceChoice.Skip(RecapSkipReason.CELLULAR_REFUSED)
    return RecapSourceChoice.Play(
        RecapSource.Stream(
            trackId = track.id,
            youtubeId = track.youtubeId,
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMs = track.durationMs,
            isrc = track.isrc,
        ),
    )
}

/** A preview never starts so late that less than this is left to hear. */
internal const val RECAP_MIN_REMAINING_MS = 25_000L

/** Default start point: about a third in, past most intros and into the song proper. */
const val RECAP_DEFAULT_START_FRACTION = 0.3f

/**
 * Where a preview of a [durationMs]-long song starts: [fraction] of the way in, but never later
 * than [RECAP_MIN_REMAINING_MS] before the end (a 4:00 song starts at 1:12; a 30 s song at 5 s,
 * not 9 s). Songs of unknown length, or no longer than 25 s, start at 0.
 * [fraction] is clamped to 0..0.9 so a bad argument can't land past the end.
 */
internal fun recapStartPositionMs(durationMs: Long, fraction: Float): Long {
    if (durationMs <= 0L) return 0L
    val latestStart = durationMs - RECAP_MIN_REMAINING_MS
    if (latestStart <= 0L) return 0L
    val safeFraction = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 0.9f)
    return (durationMs * safeFraction).toLong().coerceIn(0L, latestStart)
}
