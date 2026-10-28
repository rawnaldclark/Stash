package com.stash.feature.record.bridge

import android.util.Log
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.mapper.toDomain
import com.stash.core.data.weblink.handoff.HandoffSongMatcher
import com.stash.core.data.weblink.handoff.WireSong
import com.stash.core.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** A record's song on the hand-off's wire: its words, its Spotify id, and the cover found for it (a new stream-only row takes it). */
internal fun wireOf(s: RecordSong): WireSong =
    WireSong(title = s.title, artist = s.artist, album = s.album, spotifyId = s.sid, artwork = listOfNotNull(s.cover))

/**
 * [row] with [cover] when it has none of its own, so the queue shows a cover at once: a history's songs come with their words
 * only (the web's queue stayed blank until the covers were learnt, 10-24).
 */
internal fun withCover(row: Track, cover: String?): Track = if (cover != null && row.albumArtUrl.isNullOrBlank()) row.copy(albumArtUrl = cover) else row

/**
 * The record's songs as this phone's rows, in order (null: none for that song): a song you have in the library by its own row
 * ([RecordSong.trackId]; one gone since, by its words), the rest through the hand-off's matcher (the library's row by ISRC,
 * Spotify id or the same words, else a stream-only row that Stash resolves when it plays). A row with no cover takes the one
 * found for its song ([RecordSong.cover]): on the queue at once, and kept on the row (only while it has none).
 */
@Singleton
class RecordMatch internal constructor(
    private val byId: suspend (Long) -> TrackEntity?,
    private val matchAll: suspend (List<WireSong>) -> List<TrackEntity?>,
    private val keepCover: suspend (Long, String) -> Unit,
) {
    @Inject constructor(matcher: HandoffSongMatcher, tracks: TrackDao) : this(
        byId = tracks::getById,
        matchAll = { songs -> matcher.matchAll(songs) },
        keepCover = tracks::fillMissingAlbumArtUrl,
    )

    suspend fun rows(songs: List<RecordSong>): List<Track?> = withContext(Dispatchers.IO) {
        val rows = songs.map { s -> s.trackId?.let { byId(it) } }.toMutableList()
        val ask = songs.indices.filter { rows[it] == null }
        if (ask.isNotEmpty()) {
            val found = matchAll(ask.map { wireOf(songs[it]) })
            ask.forEachIndexed { j, i -> rows[i] = found.getOrNull(j) }
        }
        rows.mapIndexed { i, row ->
            row?.let { r ->
                val cover = songs[i].cover
                if (cover != null && r.albumArtUrl.isNullOrBlank()) keep(r.id, cover)
                withCover(r.toDomain(), cover)
            }
        }
    }

    private suspend fun keep(id: Long, cover: String) {
        try {
            keepCover(id, cover)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The queue has it already; the row gets one another time
            Log.w(TAG, "a cover couldn't be kept: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "YourRecord"
    }
}
