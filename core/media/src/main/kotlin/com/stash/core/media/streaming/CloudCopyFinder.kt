package com.stash.core.media.streaming

import com.stash.core.common.TrackCanonical
import com.stash.core.data.db.dao.CloudCopyCandidate
import com.stash.core.data.db.dao.TrackDao
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * "Play my cloud copies first" (#679): finds the user's own lossless file in their connected cloud
 * storage for a song about to stream, so it plays from there instead of asking Qobuz or Tidal (and
 * spending the daily lossless allowance on a song they already own).
 *
 * Cloud rows keep blank canonical words on purpose (a cloud song is never merged with or swapped
 * for an online row), so the match is computed here: the same canonical title and artist
 * ([TrackCanonical]), the same version (a live take or remix is another recording), and a length
 * within [DURATION_TOLERANCE_MS] when both are known. The index is rebuilt only when the cloud
 * library's fingerprint changes. A copy that fails to open is skipped for the rest of the session
 * ([markFailed]), so playback falls back to the normal chain.
 */
@Singleton
class CloudCopyFinder @Inject constructor(private val trackDao: TrackDao) {
    private val mutex = Mutex()
    private var signature: String? = null
    private var index: Map<String, List<CloudCopyCandidate>> = emptyMap()
    private val failed = ConcurrentHashMap.newKeySet<String>()

    /** The best lossless cloud copy of this song, or null. */
    suspend fun find(title: String, artist: String, durationMs: Long): CloudCopyCandidate? {
        val key = matchKey(title, artist) ?: return null
        val candidates = currentIndex()[key] ?: return null
        return pickCloudCopy(candidates, title, durationMs, failed)
    }

    /** [cloudUri] couldn't be opened: don't offer it again this session. */
    fun markFailed(cloudUri: String) {
        failed += cloudUri
    }

    private suspend fun currentIndex(): Map<String, List<CloudCopyCandidate>> = mutex.withLock {
        val now = trackDao.cloudCopySignature()
        if (now != signature) {
            index = trackDao.cloudCopyCandidates()
                .filter { it.fileFormat.lowercase() in LOSSLESS_FORMATS }
                .groupBy { matchKey(it.title, it.artist) ?: "" }
                .filterKeys { it.isNotEmpty() }
            signature = now
        }
        index
    }

    companion object {
        /** [StreamUrl.origin] of a cloud copy; its url is the song's `stash-cloud://` address. */
        const val ORIGIN = "cloud"

        /** Lengths this close are the same recording (tags and catalogs round differently). */
        const val DURATION_TOLERANCE_MS = 3_000L

        /** What a cloud file's format must be to stand in for a lossless stream. */
        val LOSSLESS_FORMATS = setOf("flac", "alac", "wav", "aiff", "aif")
    }
}

/** Canonical title + artist, or null when either is blank (nothing reliable to match on). */
internal fun matchKey(title: String, artist: String): String? {
    val t = TrackCanonical.title(title)
    val a = TrackCanonical.artist(artist)
    if (TrackCanonical.isBlankKey(t, a)) return null
    return "$t\u0000$a"
}

/**
 * Of the cloud songs with the same words: the ones of the same version, a length within the
 * tolerance (when both lengths are known), and not [failed]; the highest bit depth wins.
 */
internal fun pickCloudCopy(
    candidates: List<CloudCopyCandidate>,
    title: String,
    durationMs: Long,
    failed: Set<String> = emptySet(),
): CloudCopyCandidate? = candidates
    .asSequence()
    .filter { it.cloudUri !in failed }
    .filter { TrackCanonical.sameVersion(title, it.title) }
    .filter { durationMs <= 0 || it.durationMs <= 0 || abs(durationMs - it.durationMs) <= CloudCopyFinder.DURATION_TOLERANCE_MS }
    .maxByOrNull { it.bitsPerSample ?: 0 }
