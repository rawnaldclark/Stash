package com.stash.core.media.service

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_DURATION_MS
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_ID
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_IS_STREAMABLE
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_YOUTUBE_ID
import com.stash.core.media.streaming.stashResolveUri
import com.stash.core.model.PlaylistType

/**
 * Track eligibility + MediaItem construction for the Android Auto browse
 * tree, shared by every [StashPlaybackService.StashSessionCallback] surface
 * (children listing, shuffle play, browse-tap queueing, search, getItem).
 *
 * Top-level and internal so the exact predicates Android Auto sees are unit
 * testable on the JVM — the car surface can't be manually tested here (no
 * AA hardware; the DHU is dated), so these tests ARE the regression net.
 */

/**
 * Whether a track may appear/play in the car.
 *
 * `is_streamable = 0` alone does NOT mean unplayable: the column defaults to
 * 0 meaning "not checked yet" (a background worker drains the checks), so a
 * bare-flag gate silently drops every synced, not-yet-downloaded row — the
 * "playlists open empty in Android Auto" bug, and the same lesson as the
 * v0.9.44 prefetch gate. A row is excluded only when a check CONFIRMED it
 * unstreamable (`is_streamable = 0` with a non-null checked-at) and it has
 * no download — the mirror of `Track.isUnavailableForDisplay`.
 */
/**
 * A likes list: the in-app Liked Songs (STASH_LIKED) or a synced one
 * (LIKED_SONGS — Spotify, YouTube Music, Last.fm). Both are named "Liked
 * Songs", and the first head-unit test of #251 listed them as two rows.
 * The car shows ONE, built from [mergeLikedForAuto].
 */
internal fun PlaylistEntity.isLikedPlaylist(): Boolean =
    type == PlaylistType.STASH_LIKED || type == PlaylistType.LIKED_SONGS

/**
 * Every like source merged into one list: each track once, newest like
 * first — the Library's Liked tab, in the car. A row with no like stamp
 * (older data) falls back to its library add date, the same rule as
 * LibraryViewModel.likedAtOrAdded.
 */
internal fun mergeLikedForAuto(sources: List<List<TrackEntity>>): List<TrackEntity> =
    sources.flatten().distinctBy { it.id }.sortedByDescending { it.likedAtForAuto() }

private fun TrackEntity.likedAtForAuto(): Long =
    listOfNotNull(stashLikedAt, spotifySavedAt, ytMusicSavedAt, lastFmLovedAt).maxOrNull()
        ?: dateAdded.toEpochMilli()

internal fun TrackEntity.isPlayableInAuto(): Boolean =
    isDownloaded || isStreamable || isStreamableCheckedAt == null

/**
 * Playback URI for a car item: the local file when it's on disk, otherwise a
 * `stash-resolve://` placeholder that [com.stash.core.media.streaming.LazyResolvingDataSource]
 * resolves just-in-time at open(). Replaces the old `filePath ?: ""` pattern,
 * whose empty URI made every stream-only track error at play time (the same
 * empty-URI pathology as the v0.9.44 Liked-Songs skip-storm).
 */
internal fun TrackEntity.autoPlaybackUri(): Uri {
    val path = filePath
    if (isDownloaded && !path.isNullOrBlank()) {
        return if (path.startsWith("/")) "file://$path".toUri() else path.toUri()
    }
    return stashResolveUri(
        trackId = id,
        youtubeId = youtubeId,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        isrc = isrc,
    )
}

/**
 * Builds the playable MediaItem Android Auto receives for [this] track.
 * Carries the same identity extras as the in-app queue items so downstream
 * consumers (offline silent-skip, scrobbler, notification like, resume)
 * work identically for car-initiated playback.
 *
 * @param mediaId defaults to the track id; browse children pass the
 *   parent-carrying AUTOQ id so a tap can queue the whole playlist.
 */
internal fun TrackEntity.toAutoMediaItem(mediaId: String = id.toString()): MediaItem =
    MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(autoPlaybackUri())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(albumArtUrl?.toUri() ?: albumArtPath?.toUri())
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setExtras(android.os.Bundle().apply {
                    putLong(EXTRA_TRACK_ID, id)
                    youtubeId?.let { putString(EXTRA_TRACK_YOUTUBE_ID, it) }
                    if (durationMs > 0) putLong(EXTRA_TRACK_DURATION_MS, durationMs)
                    // "Will stream" — drives the offline silent-skip for
                    // car-queued items exactly like in-app queue items.
                    putBoolean(EXTRA_TRACK_IS_STREAMABLE, !isDownloaded || filePath.isNullOrBlank())
                })
                .build(),
        )
        .build()
