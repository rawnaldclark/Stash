package com.stash.data.download.ytdlp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTO for a single result returned by yt-dlp's `--dump-json` output.
 *
 * Only the fields needed for match scoring are captured here;
 * unknown keys are silently ignored during deserialization.
 *
 * @property id          YouTube video ID (e.g. "dQw4w9WgXcQ").
 * @property title       Video title as it appears on YouTube.
 * @property uploader    Channel or uploader display name.
 * @property uploaderId  Channel handle or internal uploader ID.
 * @property channel     Channel name (may differ slightly from [uploader]).
 * @property duration    Video duration in seconds.
 * @property viewCount   Total view count at time of search.
 * @property webpageUrl  Full URL to the YouTube watch page.
 * @property url         Direct stream URL (may be empty for flat-playlist results).
 * @property likeCount   Like count if available, null otherwise.
 * @property description Video description text.
 */
@Serializable
data class YtDlpSearchResult(
    val id: String = "",
    val title: String = "",
    val uploader: String = "",
    @SerialName("uploader_id") val uploaderId: String = "",
    val channel: String = "",
    val duration: Long = 0,
    @SerialName("view_count") val viewCount: Long = 0,
    @SerialName("webpage_url") val webpageUrl: String = "",
    val url: String = "",
    @SerialName("like_count") val likeCount: Long? = null,
    val description: String = "",
)
