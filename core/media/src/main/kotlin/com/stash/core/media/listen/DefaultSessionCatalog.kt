package com.stash.core.media.listen

import android.util.Log
import androidx.media3.common.MediaItem
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.mapper.toDomain
import com.stash.core.data.radio.RadioSeed
import com.stash.core.data.radio.RadioStationGenerator
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_DURATION_MS
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_ID
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_YOUTUBE_ID
import com.stash.core.media.service.toAutoMediaItem
import com.stash.core.model.share.SharedTrack
import com.stash.core.model.share.toSharedTrack
import java.io.File
import java.util.Collections
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Song descriptor ↔ playable item for Listen Together (spec §4 "Which recording is played").
 * A descriptor becomes a row through the EXACT persist (YouTube id, Spotify URI, ISRC — never a
 * fuzzy title match), then plays through the normal resolver: a `stash-resolve://` placeholder
 * that looks up lossless by ISRC and YouTube by id, or the downloaded file when the exact row has
 * one.
 */
class DefaultSessionCatalog @Inject constructor(
    private val musicRepository: MusicRepository,
    private val trackDao: TrackDao,
    private val radioGenerator: RadioStationGenerator,
) : SessionCatalog {

    /**
     * Descriptors this phone built from its own rows. Such a descriptor IS that row, so it plays
     * the row directly. This matters for the host's local-only files, which carry no id the exact
     * persist could match.
     * ponytail: an in-memory LRU of 500; a miss just falls back to the exact persist.
     */
    private val own: MutableMap<SharedTrack, Long> = Collections.synchronizedMap(
        object : LinkedHashMap<SharedTrack, Long>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SharedTrack, Long>?) = size > 500
        },
    )

    override suspend fun mediaItemFor(track: SharedTrack): MediaItem? = withContext(Dispatchers.IO) {
        try {
            val id = own[track] ?: musicRepository.ensureExactTrackPersisted(track)
            val row = trackDao.getById(id) ?: return@withContext null
            // An exact match may be this phone's download: the same recording, so play the file.
            // A download whose file has gone streams instead of failing, and so does one whose
            // length is more than 2 s off the host's (likely a different edit), so the listener
            // hears the host's recording.
            val path = row.filePath
            val hostMs = track.durationMs?.takeIf { it > 0 }
            val sameLength = hostMs == null || row.durationMs <= 0 || abs(row.durationMs - hostMs) <= MAX_LENGTH_GAP_MS
            val fileOk = row.isDownloaded && sameLength && !path.isNullOrBlank() &&
                (path.startsWith("content://") || File(path.removePrefix("file://")).length() > 0)
            (if (fileOk) row else row.copy(isDownloaded = false)).toAutoMediaItem()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "exact persist failed for '${track.title}'", e)
            null
        }
    }

    override suspend fun sharedTrackFor(item: MediaItem): SharedTrack? = withContext(Dispatchers.IO) {
        val extras = item.mediaMetadata.extras
        val id = item.mediaId.toLongOrNull()?.takeIf { it > 0 } ?: extras?.getLong(EXTRA_TRACK_ID, -1L)?.takeIf { it > 0 }
        id?.let { trackDao.getById(it) }?.let { row ->
            return@withContext row.toDomain().toSharedTrack().also { own[it] = row.id }
        }
        // Radio and search rows can carry synthetic ids with no Room row: fall back to the metadata.
        val title = item.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() } ?: return@withContext null
        val artist = item.mediaMetadata.artist?.toString()?.takeIf { it.isNotBlank() } ?: return@withContext null
        SharedTrack(
            title = title,
            artist = artist,
            durationMs = extras?.getLong(EXTRA_TRACK_DURATION_MS, 0L)?.takeIf { it > 0 },
            youtubeId = extras?.getString(EXTRA_TRACK_YOUTUBE_ID),
        )
    }

    override suspend fun radioAfter(track: SharedTrack): List<SharedTrack> = try {
        val (_, batch) = radioGenerator.start(RadioSeed.Song(track.title, track.artist, track.youtubeId))
        // Drop the seed itself, matched by title and artist like PlayerRepositoryImpl.startRadio's keepCurrent.
        val seed = identity(track.title, track.artist)
        batch.filter { identity(it.title, it.artist) != seed }.map { it.toSharedTrack() }.take(MAX_STATION)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "radio for '${track.title}' failed", e)
        emptyList()
    }

    private fun identity(title: String, artist: String): String {
        fun norm(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")
        return norm(title) + "|" + norm(artist)
    }

    private companion object {
        const val TAG = "ListenTogether"
        const val MAX_STATION = 200
        const val MAX_LENGTH_GAP_MS = 2_000L
    }
}
