package com.stash.core.data.genre

import android.content.Context
import com.stash.core.data.db.dao.ArtistGenreDao
import com.stash.core.data.db.entity.ArtistGenreEntity
import com.stash.core.data.sync.workers.ArtistGenreWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Genres of listened artists, and top genres for any range, for "Your listening" and Recap.
 *
 * The screens own the range and its top artists (ListeningHistoryDao, minutes per artist); this
 * repository only adds genres to them: [observeTopGenres] turns a flow of artist weights into top
 * genres, re-computing when either the artists or the cache change (a run of
 * [ArtistGenreWorker] filling in more artists updates an open screen by itself).
 *
 * Genres come from Last.fm artist tags; screens showing them carry a "Genres from Last.fm" credit.
 */
@Singleton
class ArtistGenreRepository @Inject constructor(
    private val artistGenreDao: ArtistGenreDao,
    @ApplicationContext private val context: Context,
) {

    /** Cached genres by artist key ([ArtistGenreKey]); artists without genres are absent. */
    val genresByArtist: Flow<Map<String, List<WeightedGenre>>> =
        artistGenreDao.observeAll()
            .map(::toGenreMap)
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    /**
     * Top genres of the artists [artistWeights] emits (minutes per artist for a range, typically),
     * at most [limit]. Check [TopGenres.isShowable] before showing them.
     */
    fun observeTopGenres(
        artistWeights: Flow<List<ArtistWeight>>,
        limit: Int = TopGenresCalculator.DEFAULT_LIMIT,
    ): Flow<TopGenres> =
        combine(artistWeights, genresByArtist) { artists, genres ->
            TopGenresCalculator.compute(artists, genres, limit)
        }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    /** Asks for genres of newly listened artists now (when "Your listening" opens). Cheap to repeat. */
    fun requestRefresh() = ArtistGenreWorker.requestNow(context)

    internal companion object {
        /** Tagged rows only, decoded; unreadable or empty rows are left out. */
        fun toGenreMap(rows: List<ArtistGenreEntity>): Map<String, List<WeightedGenre>> =
            buildMap {
                for (row in rows) {
                    if (row.status != ArtistGenreEntity.STATUS_TAGGED) continue
                    val genres = WeightedGenreCodec.decode(row.genres)
                    if (genres.isNotEmpty()) put(row.artistName, genres)
                }
            }
    }
}
