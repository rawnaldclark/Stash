package com.stash.core.data.db.dao

import com.stash.core.model.MusicSource

/**
 * A saved album, over a `playlists` row aliased `p`: the SQL twin of
 * [com.stash.core.data.album.SavedAlbums.isSavedAlbum]; change the two together (pinned by
 * SavedAlbumQueriesTest). A `CUSTOM` row keyed `album:<service>:<id>` (Save on an album page, a
 * pasted album link), but not `album:qobuz_playlist:<id>` (a saved Qobuz playlist), or a YouTube
 * row keyed by an album browse id `MPREb_…` (YouTube Music's saved albums, synced).
 *
 * Library › Albums lists these ([PlaylistDao.observeSavedAlbums]); the Playlists tab and the
 * "Add to playlist" pickers leave them out ([PlaylistDao.getUserCreatedPlaylists],
 * [PlaylistDao.getPickablePlaylists]).
 */
internal const val SAVED_ALBUM = """
    (p.type = 'CUSTOM' AND (
        (p.source_id LIKE 'album:%' AND p.source_id NOT LIKE 'album:%playlist:%')
        OR (p.source = 'YOUTUBE' AND p.source_id LIKE 'MPREb%')
    ))
"""

/** Unit separator: joins a saved album's song artists and titles in one column ([SavedAlbumRow]). */
const val SAVED_ALBUM_LIST_SEPARATOR = '\u001F'

/**
 * One saved album with its songs counted, as [PlaylistDao.observeSavedAlbums] reads it. Its songs
 * are the live memberships the album page lists in Stream only (blocked songs left out).
 *
 * @property trackCount       Its songs.
 * @property downloadedCount  Of those, the ones on this phone (`is_downloaded`, what Download mode lists).
 * @property downloadingCount Of the rest, those waiting or running in the download queue.
 * @property artists          Its songs' artists, in no set order, joined by [SAVED_ALBUM_LIST_SEPARATOR].
 * @property titles           Its songs' titles, the same way.
 */
data class SavedAlbumRow(
    val id: Long,
    val name: String,
    val source: MusicSource,
    val sourceId: String,
    val artUrl: String?,
    val dateAdded: Long,
    val trackCount: Int,
    val downloadedCount: Int,
    val downloadingCount: Int,
    val artists: String?,
    val titles: String?,
)
