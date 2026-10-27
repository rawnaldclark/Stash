package com.stash.feature.record.imports

import com.stash.core.data.db.dao.RecordHiddenDao
import com.stash.core.data.db.entity.RecordHiddenEntity
import com.stash.core.data.record.ChosenPlaylist
import com.stash.core.data.record.ExportPlaylist
import com.stash.core.data.record.ExportSong
import com.stash.core.data.record.SpotifyLibraryImport
import com.stash.feature.record.lastfm.Scrobbles
import javax.inject.Inject

/**
 * Where an import's playlists, liked songs and hidden songs go (service.ts `confirm`'s library half, importer.ts `commit`'s
 * hidden set): the library ([SpotifyLibraryImport]) and `record_hidden`. Nothing here when the tests bring plays alone.
 */
interface RecordLibrary {
    /** The indexes of [playlists] the library holds already (owner decision D3): they start unticked in the preview. */
    suspend fun alreadyHere(playlists: List<ExportPlaylist>): Set<Int>

    /** Stash's Liked Songs is kept on this phone: liked songs brought in would download too. */
    suspend fun likesDownload(): Boolean

    /** Songs hidden on Spotify, added to those kept (a newer import adds, and takes none away). */
    suspend fun addHidden(songs: List<ExportSong>)

    /** Every song hidden on Spotify gone (Remove import, or the scrobbles were all there was). */
    suspend fun clearHidden()

    /**
     * [playlists] and [likes] into the library, the liked songs timed from [now] down; [progress] is told the songs matched so
     * far of all and may throw to stop. Safe to run again: nothing is added twice.
     */
    suspend fun write(playlists: List<ChosenPlaylist>, likes: List<ExportSong>, now: Long, progress: (done: Int, total: Int) -> Unit)

    /** No library: the preview marks nothing, and nothing is written. */
    object None : RecordLibrary {
        override suspend fun alreadyHere(playlists: List<ExportPlaylist>): Set<Int> = emptySet()

        override suspend fun likesDownload(): Boolean = false

        override suspend fun addHidden(songs: List<ExportSong>) = Unit

        override suspend fun clearHidden() = Unit

        override suspend fun write(playlists: List<ChosenPlaylist>, likes: List<ExportSong>, now: Long, progress: (done: Int, total: Int) -> Unit) = Unit
    }

    companion object {
        /**
         * importer.ts `commit`'s hidden set: each song under its song key (the same song across its remasters: `songKey`), the
         * first spelling of each kept.
         */
        fun hiddenRows(songs: List<ExportSong>): List<RecordHiddenEntity> =
            songs.map { RecordHiddenEntity(Scrobbles.songKey(it.title, it.artist), it.title, it.artist) }.distinctBy { it.key }
    }
}

/** The app's library: [SpotifyLibraryImport] for the playlists and likes, `record_hidden` for the hidden songs. */
class DaoRecordLibrary @Inject constructor(
    private val spotify: SpotifyLibraryImport,
    private val hidden: RecordHiddenDao,
) : RecordLibrary {
    override suspend fun alreadyHere(playlists: List<ExportPlaylist>): Set<Int> = spotify.alreadyHere(playlists)

    override suspend fun likesDownload(): Boolean = spotify.likesDownload()

    override suspend fun addHidden(songs: List<ExportSong>) {
        if (songs.isNotEmpty()) hidden.add(RecordLibrary.hiddenRows(songs))
    }

    override suspend fun clearHidden() = hidden.clear()

    override suspend fun write(playlists: List<ChosenPlaylist>, likes: List<ExportSong>, now: Long, progress: (done: Int, total: Int) -> Unit) {
        spotify.write(playlists, likes, now, progress)
    }
}
