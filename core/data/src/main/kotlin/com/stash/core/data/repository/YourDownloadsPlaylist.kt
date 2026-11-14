package com.stash.core.data.repository

import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType

/**
 * "Your Downloads": the playlist that holds the songs the user chose to download (a tap on
 * Download anywhere, a Search download), so Refresh cleanup, which deletes a downloaded song in no
 * playlist, never takes one of them (#761). One row, found by [SOURCE_ID] and made on first use.
 *
 * Shared by [MusicRepositoryImpl] and the one-time [com.stash.core.data.db.OwnDownloadsBackfill],
 * so both link into the same row.
 */
internal object YourDownloadsPlaylist {

    /** The reserved source id that finds the row. */
    const val SOURCE_ID = "stash_downloads_mix"

    /** The row as it is made when missing. */
    fun entity(): PlaylistEntity = PlaylistEntity(
        name = "Your Downloads",
        source = MusicSource.BOTH,
        sourceId = SOURCE_ID,
        type = PlaylistType.DOWNLOADS_MIX,
        syncEnabled = false,
    )
}
