package com.stash.feature.sync

import com.stash.core.common.ArtUrlUpgrader
import com.stash.data.download.ytdlp.YtDlpSearchResult
import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.ArtistSummary
import com.stash.data.ytmusic.model.SearchResultSection
import com.stash.data.ytmusic.model.TopResultItem
import com.stash.data.ytmusic.model.TrackSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Find it yourself" on Failed Matches (#19): what a search becomes on screen.
 * Every result is the same [ResyncCandidate] an automatic match is, so a pick
 * goes through the existing preview and approve/swap; and the rows come in the
 * order a listener scans them: YouTube Music's songs, its videos, then what all
 * of YouTube adds.
 */
class ManualMatchSearchTest {

    /** An 11-character id, the YouTube video-id shape, from a readable tag. */
    private fun id(tag: String) = tag.padEnd(11, 'x')

    private fun music(tag: String, type: String? = "ATV", title: String = "Title $tag") = TrackSummary(
        videoId = id(tag),
        title = title,
        artist = "Daft Punk",
        album = "Random Access Memories",
        durationSeconds = 369.0,
        thumbnailUrl = "https://lh3.googleusercontent.com/$tag",
        musicVideoType = type,
    )

    private fun youTube(tag: String, channel: String = "Some Channel", title: String = "Upload $tag") =
        YtDlpSearchResult(id = id(tag), title = title, uploader = channel, channel = channel, duration = 248.0)

    private fun songs(vararg tracks: TrackSummary) = SearchResultSection.Songs(tracks.toList())

    private fun List<ManualMatch>.ids() = map { it.candidate.videoId }

    @Test fun `the box starts with the song's artist and title`() {
        assertEquals("Daft Punk Get Lucky", manualSearchQuery("Daft Punk", "Get Lucky"))
        assertEquals("Get Lucky", manualSearchQuery("  ", " Get Lucky "))
    }

    @Test fun `YouTube Music's songs come first, then its videos, then what all of YouTube adds`() {
        val answer = listOf(
            SearchResultSection.Top(TopResultItem.TrackTop(music("top", type = "OMV"))),
            songs(music("song1"), music("video1", type = "UGC"), music("song2")),
        )
        val all = listOf(youTube("upload1"), youTube("topic1", channel = "Daft Punk - Topic"))

        val matches = manualSearchMatches(answer, all, excludeVideoId = null)

        assertEquals(
            listOf("song1", "song2", "top", "video1", "topic1", "upload1").map { id(it) },
            matches.ids(),
        )
        assertEquals(listOf(true, true, false, false, true, false), matches.map { it.isSong })
    }

    /**
     * All of YouTube answers seconds later; its rows only ever go below, so the
     * rows on screen stay where they are when it does.
     */
    @Test fun `all of YouTube's answer only adds rows below YouTube Music's`() {
        val answer = listOf(songs(music("song1"), music("video1", type = "OMV")))
        val first = manualSearchMatches(answer, emptyList(), excludeVideoId = null)
        val both = manualSearchMatches(answer, listOf(youTube("topic1", channel = "Daft Punk - Topic")), null)

        assertEquals(first, both.take(first.size))
        assertEquals(id("topic1"), both.last().candidate.videoId)
    }

    @Test fun `a video found by both searches is listed once, where YouTube Music had it`() {
        val answer = listOf(songs(music("song1")))
        val all = listOf(youTube("song1", channel = "Daft Punk - Topic"), youTube("upload1"))

        val matches = manualSearchMatches(answer, all, excludeVideoId = null)

        assertEquals(listOf("song1", "upload1").map { id(it) }, matches.ids())
        assertEquals("https://lh3.googleusercontent.com/song1", matches.first().candidate.thumbnailUrl)
    }

    @Test fun `a flagged song is never offered the video it was flagged for`() {
        val wrong = "wrong"
        val answer = listOf(songs(music(wrong), music("song1")))
        val all = listOf(youTube(wrong), youTube("upload1"))

        val matches = manualSearchMatches(answer, all, excludeVideoId = id(wrong))

        assertEquals(listOf("song1", "upload1").map { id(it) }, matches.ids())
    }

    @Test fun `rows that can't be picked are left out`() {
        val answer = listOf(
            songs(
                music("song1").copy(videoId = "too-short"),
                music("song2", title = " "),
                music("episode1", type = "PODCAST_EPISODE"),
                music("song3"),
            ),
        )
        val all = listOf(youTube("upload1").copy(id = "bad/../id!!"), youTube("upload2", title = ""), youTube("upload3"))

        val matches = manualSearchMatches(answer, all, excludeVideoId = null)

        assertEquals(listOf("song3", "upload3").map { id(it) }, matches.ids())
    }

    @Test fun `artists and albums in the answer are not results`() {
        val answer = listOf(
            SearchResultSection.Top(TopResultItem.ArtistTop(ArtistSummary("UC1", "Daft Punk", null))),
            songs(music("song1")),
            SearchResultSection.Artists(listOf(ArtistSummary("UC2", "Pharrell", null))),
            SearchResultSection.Albums(listOf(AlbumSummary("MPREb_1", "Discovery", "Daft Punk", null, "2001"))),
        )

        assertEquals(listOf(id("song1")), manualSearchMatches(answer, emptyList(), null).ids())
    }

    @Test fun `a YouTube Music result is the candidate an automatic match would be`() {
        val match = manualSearchMatches(listOf(songs(music("song1"))), emptyList(), null).single()

        assertEquals(
            ResyncCandidate(
                videoId = id("song1"),
                title = "Title song1",
                artist = "Daft Punk",
                thumbnailUrl = "https://lh3.googleusercontent.com/song1",
                durationSeconds = 369.0,
            ),
            match.candidate,
        )
        assertTrue(match.isSong)
    }

    /** A row without a type came from the Songs list; the top card is typed. */
    @Test fun `a YouTube Music row without a type counts as a song`() {
        val match = manualSearchMatches(listOf(songs(music("song1", type = null))), emptyList(), null).single()

        assertTrue(match.isSong)
    }

    @Test fun `a YouTube result reads as its channel, without Topic, and shows the video's own thumbnail`() {
        val topic = youTube("topic1", channel = "Daft Punk - Topic")
        val upload = youTube("upload1").copy(uploader = "", channel = "Fan Uploads")

        val (song, video) = manualSearchMatches(emptyList(), listOf(topic, upload), null)

        assertEquals("Daft Punk", song.candidate.artist)
        assertTrue(song.isSong)
        assertEquals(ArtUrlUpgrader.youTubeThumbnail(id("topic1")), song.candidate.thumbnailUrl)
        assertEquals(248.0, song.candidate.durationSeconds, 0.0)
        assertEquals("Fan Uploads", video.candidate.artist)
        assertFalse(video.isSong)
    }

    @Test fun `a done search with no rows has found nothing, a running one hasn't yet`() {
        assertTrue(ManualSearch(trackId = 1L, query = "x").foundNothing)
        assertFalse(ManualSearch(trackId = 1L, query = "x", searchingYouTube = true).foundNothing)
    }
}
