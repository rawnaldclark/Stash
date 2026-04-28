package com.stash.core.data.sync.workers

import com.stash.data.ytmusic.model.MusicVideoType
import com.stash.data.ytmusic.model.YTMusicTrack
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the pure filter helper that backs the
 * "Studio recordings only" Liked Songs preference.
 *
 * Spec rule: include ATV + OMV + OFFICIAL_SOURCE_MUSIC + null; drop UGC + PODCAST_EPISODE.
 */
class PlaylistFetchWorkerStudioFilterTest {

    private fun track(type: MusicVideoType?, id: String): YTMusicTrack =
        YTMusicTrack(videoId = id, title = "T-$id", artists = "A", musicVideoType = type)

    @Test fun `keeps ATV OMV OFFICIAL_SOURCE_MUSIC and null`() {
        val tracks = listOf(
            track(MusicVideoType.ATV, "atv"),
            track(MusicVideoType.OMV, "omv"),
            track(MusicVideoType.OFFICIAL_SOURCE_MUSIC, "osm"),
            track(null, "null"),
        )
        assertEquals(tracks, filterStudioOnly(tracks))
    }

    @Test fun `removes UGC`() {
        val tracks = listOf(
            track(MusicVideoType.ATV, "atv"),
            track(MusicVideoType.UGC, "ugc"),
            track(MusicVideoType.OMV, "omv"),
        )
        val filtered = filterStudioOnly(tracks)
        assertEquals(listOf("atv", "omv"), filtered.map { it.videoId })
    }

    @Test fun `removes PODCAST_EPISODE`() {
        val tracks = listOf(
            track(MusicVideoType.ATV, "atv"),
            track(MusicVideoType.PODCAST_EPISODE, "pod"),
        )
        assertEquals(listOf("atv"), filterStudioOnly(tracks).map { it.videoId })
    }

    @Test fun `mixed bag drops UGC and PODCAST_EPISODE only`() {
        val tracks = listOf(
            track(MusicVideoType.ATV, "1"),
            track(MusicVideoType.UGC, "2"),
            track(MusicVideoType.OMV, "3"),
            track(MusicVideoType.PODCAST_EPISODE, "4"),
            track(MusicVideoType.OFFICIAL_SOURCE_MUSIC, "5"),
            track(null, "6"),
        )
        val filtered = filterStudioOnly(tracks)
        assertEquals(listOf("1", "3", "5", "6"), filtered.map { it.videoId })
    }

    @Test fun `empty input returns empty`() {
        assertEquals(emptyList<YTMusicTrack>(), filterStudioOnly(emptyList()))
    }
}
