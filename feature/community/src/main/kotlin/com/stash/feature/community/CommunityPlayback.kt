package com.stash.feature.community

import com.stash.core.data.community.CommunityRepository
import com.stash.core.data.community.CommunityResult
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.share.SharedMixRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.model.Track
import com.stash.core.model.community.CommunityPost
import com.stash.core.model.share.toTrack
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * A post's songs as playable rows, the way a shared mix's Play makes them: each kept as a
 * hidden stream-only row first. [limit] takes the first songs as posted (null = all).
 */
class CommunityTracks @Inject constructor(
    private val sharedMixRepository: SharedMixRepository,
    private val musicRepository: MusicRepository,
) {
    suspend fun of(post: CommunityPost, limit: Int? = null): List<Track> {
        if (post.kind == "song") {
            val song = post.track ?: return emptyList()
            return listOfNotNull(musicRepository.observeTrackById(musicRepository.ensureTrackPersisted(song.toTrack())).first())
        }
        val doc = post.toDocument()
        return sharedMixRepository.tracksFor(if (limit == null) doc else doc.copy(tracks = doc.tracks.take(limit)))
    }
}

/**
 * Play all / Shuffle on the Community list: a taste of the chart. The top [CHART_POSTS] posts are
 * opened one at a time (a list carries no songs, and every open is one read of the Worker's
 * 120-a-minute budget), taking up to [SONGS_PER_POST] songs from each playlist or mix and a song
 * post's song. Playback starts with the first post that has songs; the rest are added behind it.
 * Shuffle shuffles the posts and the songs within each.
 *
 * 30 s of a post's songs played from here lets you vote on that post, as from its own Play
 * ([CommunityListenTracker.beginChart]).
 */
class CommunityChartPlayer @Inject constructor(
    private val repository: CommunityRepository,
    private val tracks: CommunityTracks,
    private val player: PlayerRepository,
    private val listenTracker: CommunityListenTracker,
) {
    /** Returns false when nothing could be played (offline, every post gone). [onStarted] fires once playback begins. */
    suspend fun play(chart: List<CommunityPost>, shuffle: Boolean, onStarted: () -> Unit): Boolean {
        val posts = chart.take(CHART_POSTS).let { if (shuffle) it.shuffled() else it }
        var started = false
        for (summary in posts) {
            val post = (repository.open(summary.id) as? CommunityResult.Ok)?.value ?: continue
            val songs = tracks.of(post, SONGS_PER_POST).let { if (shuffle) it.shuffled() else it }
            if (songs.isEmpty()) continue
            if (!started) {
                listenTracker.beginChart()
                countFor(post, songs)
                player.setQueue(songs, 0, CommunityListenTracker.CHART_SOURCE)
                started = true
                onStarted()
            } else {
                countFor(post, songs)
                player.addToQueue(songs)
            }
        }
        return started
    }

    /** [post]'s [songs] count toward a listen of it, unless it's yours or heard already (as its own Play has it). */
    private fun countFor(post: CommunityPost, songs: List<Track>) {
        if (!post.mine && !post.heard) listenTracker.chartPost(post.id, songs.map { it.id })
    }

    companion object {
        const val CHART_POSTS = 10
        const val SONGS_PER_POST = 10
    }
}
