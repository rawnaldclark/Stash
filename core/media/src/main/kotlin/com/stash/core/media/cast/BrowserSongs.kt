package com.stash.core.media.cast

import androidx.media3.common.MediaItem
import com.stash.core.data.weblink.WebSongs
import com.stash.core.data.weblink.handoff.WireSong
import com.stash.core.media.EXTRA_SOUNDCLOUD_ID
import com.stash.core.media.EXTRA_SOUNDCLOUD_LOCAL
import com.stash.core.media.EXTRA_TRACK_SPOTIFY_URI
import com.stash.core.media.queueTrackId
import com.stash.core.media.rebuiltTrackSource
import com.stash.core.media.service.StashPlaybackService
import com.stash.core.model.MusicSource
import com.stash.core.model.Track
import com.stash.core.model.isScannedTrackId

/**
 * What a browser playing this phone's queue is handed for a queue item (Remote v2, spec 2026-11-03 §5): the same Song the
 * handoff sends, by the one rule ([WebSongs]). The playback service looks the item's library row up by its track id; an item
 * with no row (a search result, a radio song) goes by the words and ids it carries.
 */
internal object BrowserSongs {
    /** [item] as a web song (`phoneOnly` when the browser can't play it), or null when it has no title or artist. */
    fun songFor(item: MediaItem, row: Track?): WireSong? = WebSongs.songOf(row ?: trackOf(item))

    /**
     * The queue rows the browser can't play, as the queue names them ([queueTrackId]): every item in [songs] (the service's
     * lookups, by media id) whose song is phone-only, or has none (no title or artist). The queue dims exactly these, so a row
     * looks the way the browser will treat it: the queue's own Track can't tell, as it doesn't carry the download or the ISRC
     * its library row has. An item not looked up yet, or with no id (a Search preview), isn't listed.
     */
    fun phoneOnlyIds(items: List<MediaItem>, songs: Map<String, WireSong?>): Set<Long> = buildSet {
        for (item in items) {
            if (!songs.containsKey(item.mediaId)) continue
            if (songs[item.mediaId]?.phoneOnly == false) continue
            item.queueTrackId().takeIf { it != 0L }?.let(::add)
        }
    }

    /** The library row id [item] stands for, when it can have one (an All Songs scan never has). */
    fun rowId(item: MediaItem): Long? {
        val id = item.mediaMetadata.extras?.getLong(StashPlaybackService.EXTRA_TRACK_ID, 0L)?.takeIf { it != 0L }
            ?: item.mediaId.toLongOrNull()
            ?: return null
        return id.takeIf { it > 0 && !isScannedTrackId(it) }
    }

    /** [item] rebuilt from what it carries, for one with no library row. */
    fun trackOf(item: MediaItem): Track {
        val meta = item.mediaMetadata
        val extras = meta.extras
        val id = extras?.getLong(StashPlaybackService.EXTRA_TRACK_ID, 0L)?.takeIf { it != 0L } ?: item.mediaId.toLongOrNull() ?: 0L
        val youtubeId = extras?.getString(StashPlaybackService.EXTRA_TRACK_YOUTUBE_ID)
        // A file on this phone (not a stream): with no ids to find it by, the browser can't play it.
        val scheme = item.localConfiguration?.uri?.scheme?.lowercase()
        val onPhone = scheme == "file" || scheme == "content" || extras?.getBoolean(EXTRA_SOUNDCLOUD_LOCAL, false) == true
        val source = rebuiltTrackSource(extras, youtubeId)
        return Track(
            id = id,
            title = meta.title?.toString().orEmpty(),
            artist = meta.artist?.toString().orEmpty(),
            album = meta.albumTitle?.toString().orEmpty(),
            durationMs = extras?.getLong(StashPlaybackService.EXTRA_TRACK_DURATION_MS, 0L) ?: 0L,
            youtubeId = youtubeId,
            spotifyUri = extras?.getString(EXTRA_TRACK_SPOTIFY_URI),
            soundCloudId = extras?.getString(EXTRA_SOUNDCLOUD_ID),
            albumArtUrl = meta.artworkUri?.toString(),
            source = if (onPhone && source != MusicSource.SOUNDCLOUD && source != MusicSource.CLOUD) MusicSource.LOCAL else source,
            isDownloaded = onPhone,
        )
    }
}
