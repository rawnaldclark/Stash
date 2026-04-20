package com.stash.data.download.ytdlp

import com.stash.data.ytmusic.model.MusicVideoType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

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
    val duration: Double = 0.0,
    @SerialName("view_count") val viewCount: Long = 0,
    @SerialName("webpage_url") val webpageUrl: String = "",
    val url: String = "",
    @SerialName("like_count") val likeCount: Long? = null,
    val description: String = "",
    /** Thumbnail URL (populated by InnerTube search, not by yt-dlp --flat-playlist). */
    val thumbnail: String? = null,
    /** Album name (populated by InnerTube search from flexColumns[2]). */
    val album: String? = null,
    /**
     * YouTube Music's authoritative video classification. Populated by
     * [com.stash.data.download.matching.InnerTubeSearchExecutor] from
     * `watchEndpointMusicConfig.musicVideoType`; null for yt-dlp-sourced
     * fallback results (yt-dlp's JSON doesn't expose this field). Drives
     * scorer preferences: ATV > OFFICIAL_SOURCE_MUSIC > OMV; UGC + PODCAST
     * are hard-rejected.
     *
     * `@Transient` because [kotlinx.serialization] handles `YtDlpSearchResult`
     * shapes only when they originate from yt-dlp JSON — the InnerTube
     * path constructs the DTO in Kotlin code and never round-trips it
     * through JSON, so the enum never needs a serializer.
     */
    @Transient
    val musicVideoType: MusicVideoType? = null,
)
