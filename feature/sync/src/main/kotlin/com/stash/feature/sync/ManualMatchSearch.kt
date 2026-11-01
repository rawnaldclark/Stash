package com.stash.feature.sync

import com.stash.core.common.ArtUrlUpgrader
import com.stash.core.model.YouTubeVideoId
import com.stash.data.download.ytdlp.YtDlpSearchResult
import com.stash.data.ytmusic.model.SearchResultSection
import com.stash.data.ytmusic.model.TopResultItem
import com.stash.data.ytmusic.model.TrackSummary

/**
 * One result of "find it yourself" on Failed Matches (#19): a video the
 * listener can preview and pick for a song that didn't match, or matched the
 * wrong recording.
 *
 * @property candidate What a pick hands to the approve and swap paths: the same
 *   [ResyncCandidate] an automatic match is, so nothing after the pick is new.
 * @property isSong    A song from a release (YouTube Music's own audio) rather
 *   than a video (a music video, a live cut, an upload). The row says which,
 *   and songs are listed first.
 */
data class ManualMatch(
    val candidate: ResyncCandidate,
    val isSong: Boolean,
)

/**
 * "Find it yourself", open under one song's row on Failed Matches.
 *
 * @property trackId          The song it is open for.
 * @property query            The words searched for: "artist title" at first.
 * @property results          What was found, in the order shown ([manualSearchMatches]).
 * @property searchingMusic   YouTube Music hasn't answered yet.
 * @property searchingYouTube All of YouTube hasn't answered yet. Its rows follow
 *   YouTube Music's, which show as soon as they're in.
 * @property failed           Neither search got anything back, and YouTube Music
 *   didn't answer at all: likely no connection, so say so rather than "nothing found".
 */
data class ManualSearch(
    val trackId: Long,
    val query: String,
    val results: List<ManualMatch> = emptyList(),
    val searchingMusic: Boolean = false,
    val searchingYouTube: Boolean = false,
    val failed: Boolean = false,
) {
    /** Both searches are done and there is nothing to show. */
    val foundNothing: Boolean
        get() = !searchingMusic && !searchingYouTube && results.isEmpty()
}

/** What the search box starts with: the song's artist and title, as you'd type them into Search. */
internal fun manualSearchQuery(artist: String, title: String): String =
    listOf(artist, title).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

/**
 * The rows "find it yourself" shows, in order: YouTube Music's songs, then its
 * videos (both from the Search tab's own search, so at most five rows: its top
 * result and up to four songs), then what a search of all of YouTube adds, its
 * songs first. Each video is listed once, where it first turned up. Left out:
 * [excludeVideoId] (a flagged song's current video, the one the listener said
 * is wrong), rows without a YouTube video id or a title, podcast episodes, and
 * all-of-YouTube rows without a length ([YtDlpSearchResult.toManualMatch]).
 *
 * All of YouTube only ever adds rows below: its answer comes seconds after
 * YouTube Music's, and the rows already on screen must not move under the
 * listener's finger.
 */
internal fun manualSearchMatches(
    youTubeMusic: List<SearchResultSection>,
    allOfYouTube: List<YtDlpSearchResult>,
    excludeVideoId: String?,
): List<ManualMatch> {
    val listed = HashSet<String>()
    fun ManualMatch.isNew(): Boolean =
        candidate.videoId != excludeVideoId && listed.add(candidate.videoId)

    val music = youTubeMusic.tracks().mapNotNull { it.toManualMatch()?.takeIf { match -> match.isNew() } }
    val youTube = allOfYouTube.mapNotNull { it.toManualMatch()?.takeIf { match -> match.isNew() } }
    return music.songsFirst() + youTube.songsFirst()
}

/** YouTube Music's name for a song from a release ([TrackSummary.musicVideoType]). */
private const val SONG_TYPE = "ATV"

private const val PODCAST_TYPE = "PODCAST_EPISODE"

/** YouTube's name for the channels it fills with a release's songs: "Artist - Topic". */
private const val TOPIC_SUFFIX = " - Topic"

/** The tracks of a Search answer, as the Search tab shows them: the top result if it is one, then Songs. */
private fun List<SearchResultSection>.tracks(): List<TrackSummary> = flatMap { section ->
    when (section) {
        is SearchResultSection.Top -> listOfNotNull((section.item as? TopResultItem.TrackTop)?.track)
        is SearchResultSection.Songs -> section.tracks
        is SearchResultSection.Artists, is SearchResultSection.Albums -> emptyList()
    }
}

private fun List<ManualMatch>.songsFirst(): List<ManualMatch> {
    val (songs, videos) = partition { it.isSong }
    return songs + videos
}

/**
 * A YouTube Music row as a candidate. A row without a type counts as a song:
 * it came from the Songs list (the top result card is typed).
 */
internal fun TrackSummary.toManualMatch(): ManualMatch? {
    if (!YouTubeVideoId.isValid(videoId) || title.isBlank() || musicVideoType == PODCAST_TYPE) return null
    return ManualMatch(
        candidate = ResyncCandidate(
            videoId = videoId,
            title = title,
            artist = artist,
            thumbnailUrl = thumbnailUrl ?: ArtUrlUpgrader.youTubeThumbnail(videoId),
            durationSeconds = durationSeconds,
        ),
        isSong = musicVideoType == null || musicVideoType == SONG_TYPE,
    )
}

/**
 * A yt-dlp search row as a candidate. yt-dlp says nothing about the kind of
 * video, but a Topic channel's uploads are a release's songs. Its flat search
 * rows carry no thumbnail, so the video's own is used. A row without a length
 * is a live stream (or a premiere): its download would never finish and would
 * hold one of the few approve slots, so it is left out. (A YouTube Music row
 * without a length is kept: its flat layout carries none.)
 */
internal fun YtDlpSearchResult.toManualMatch(): ManualMatch? {
    if (!YouTubeVideoId.isValid(id) || title.isBlank() || duration <= 0.0) return null
    return ManualMatch(
        candidate = ResyncCandidate(
            videoId = id,
            title = title,
            artist = uploader.ifBlank { channel }.removeSuffix(TOPIC_SUFFIX),
            thumbnailUrl = thumbnail ?: ArtUrlUpgrader.youTubeThumbnail(id),
            durationSeconds = duration,
        ),
        isSong = channel.endsWith(TOPIC_SUFFIX) || uploader.endsWith(TOPIC_SUFFIX),
    )
}
