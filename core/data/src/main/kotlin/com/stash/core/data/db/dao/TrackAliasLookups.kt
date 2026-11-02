package com.stash.core.data.db.dao

import com.stash.core.data.db.entity.TrackAliasEntity
import com.stash.core.data.db.entity.TrackEntity

/*
 * Lookups by an id that also find a song whose row was merged into another (schema 76,
 * `track_aliases`). Every path that finds a song by an id before it would make a new row uses these,
 * so a merged-away video, Spotify track or row id lands on the row that stands for the song now and
 * the duplicate is never made again. The id's own column always wins: an alias stands in only for
 * an id no row holds.
 */

/** The row holding YouTube video [youtubeId], or the row a merge pointed it at. */
suspend fun TrackDao.findByYoutubeIdOrAlias(youtubeId: String): TrackEntity? =
    findByYoutubeId(youtubeId) ?: findByAlias(TrackAliasEntity.KIND_YOUTUBE_ID, youtubeId)

/** The row holding Spotify URI [spotifyUri], or the row a merge pointed it at. */
suspend fun TrackDao.findBySpotifyUriOrAlias(spotifyUri: String): TrackEntity? =
    findBySpotifyUri(spotifyUri) ?: findByAlias(TrackAliasEntity.KIND_SPOTIFY_URI, spotifyUri)

/** The row a merge took row [trackId] into, or null when [trackId] was never merged away. */
suspend fun TrackDao.findMergedInto(trackId: Long): TrackEntity? =
    findByAlias(TrackAliasEntity.KIND_TRACK_ID, trackId.toString())

/** Row [trackId], or the row it was merged into when it is gone. */
suspend fun TrackDao.getByIdOrMerged(trackId: Long): TrackEntity? =
    getById(trackId) ?: findMergedInto(trackId)
