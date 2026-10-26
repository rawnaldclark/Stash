package com.stash.feature.record.bridge

import com.stash.core.data.mapper.toDomain
import com.stash.core.data.weblink.handoff.HandoffSongMatcher
import com.stash.core.data.weblink.handoff.WireSong
import com.stash.core.media.PlayerRepository
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.model.PlaybackSource
import com.stash.core.model.Track
import javax.inject.Inject

/** What a tap in the record plays, decided without the player so it can be tested. */
internal sealed interface RecordQueuePlan {
    data class Play(val tracks: List<Track>, val startIndex: Int) : RecordQueuePlan

    data class Blocked(val message: String) : RecordQueuePlan
}

/** None of the record's songs could be found anywhere (their words matched nothing, even as a stream). */
internal const val NONE_FOUND = "Couldn't find these songs to play."

/** Offline, and none of them is on the phone (Library's and Your listening's words). */
internal const val NONE_DOWNLOADED = "None of these songs are downloaded. Connect to play them."

/**
 * The record's songs (matched, in its order; null: no row for that song) as a queue from [index]. A song that couldn't be
 * matched is left out and the start stays on the song that was asked for, else the next one that can play (the web's rule for
 * a bad song in a play). Online every song plays, streaming what isn't downloaded; offline only songs with a file here, as in
 * Library: a queue that mixes the others in stops after a few failures.
 */
internal fun planRecordQueue(tracks: List<Track?>, index: Int, online: Boolean): RecordQueuePlan {
    val playable = tracks.indices.filter { i -> tracks[i]?.let { online || it.filePath != null } == true }
    if (playable.isEmpty()) {
        return RecordQueuePlan.Blocked(if (tracks.any { it != null }) NONE_DOWNLOADED else NONE_FOUND)
    }
    val from = playable.indexOfFirst { it >= index }.takeIf { it >= 0 } ?: playable.lastIndex
    return RecordQueuePlan.Play(playable.map { tracks[it]!! }, from)
}

/**
 * Plays the record's songs in Stash (PLAN "Play"): each by its words through the hand-off's matcher (the library's own row
 * when there is one, else a stream-only row that Stash resolves when it plays, as a Search tap does), then the queue, its
 * "Playing from" the record's label ("The Record · Sunday, 3 March 2024").
 */
class RecordPlayback @Inject constructor(
    private val matcher: HandoffSongMatcher,
    private val player: PlayerRepository,
    private val connectivity: ConnectivityMonitor,
) : RecordPlayer {
    override suspend fun play(songs: List<RecordSong>, index: Int, from: String): String? {
        val rows = matcher.matchAll(songs.map { WireSong(title = it.title, artist = it.artist, album = it.album) })
        return when (val plan = planRecordQueue(rows.map { it?.toDomain() }, index, connectivity.isConnected())) {
            is RecordQueuePlan.Play -> {
                player.setQueue(plan.tracks, plan.startIndex, PlaybackSource.Listening(from))
                null
            }
            is RecordQueuePlan.Blocked -> plan.message
        }
    }
}
