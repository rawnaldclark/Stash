package com.stash.data.download.lossless.qbdlx

import com.stash.core.data.discography.DiscographySupplement
import com.stash.core.data.discography.MergedDiscography
import com.stash.data.download.lossless.qobuz.QobuzCandidateMatcher
import com.stash.data.ytmusic.model.AlbumSource
import com.stash.data.ytmusic.model.AlbumSummary
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [DiscographySupplement] backed by the direct-Qobuz (qbdlx) catalog. Given a YT
 * artist name + its YT albums/singles, it matches the artist on Qobuz, fetches
 * its albums, and unions them into the YT lists via [DiscographyMerger].
 *
 * Correctness-critical: the dangerous failure is a FALSE artist match grafting a
 * stranger's discography. The gate therefore FAILS SAFE — on any doubt (blank/VA
 * name, no candidate over threshold, ambiguous homonym, or any thrown exception)
 * it returns the YT lists UNCHANGED. The outer
 * `runCatching { … }.getOrElse { unchanged }` guarantees nothing throws out.
 */
@Singleton
class QobuzDiscographyProvider @Inject constructor(
    private val apiClient: QbdlxApiClient,
) : DiscographySupplement {

    override suspend fun mergeInto(
        artistName: String,
        ytAlbums: List<AlbumSummary>,
        ytSingles: List<AlbumSummary>,
    ): MergedDiscography = runCatching {
        val nName = QobuzCandidateMatcher.normalize(artistName)
        if (nName.isBlank() || nName in VARIOUS_ARTISTS) return unchanged(ytAlbums, ytSingles)

        val candidates = apiClient.searchArtists(artistName)
            .filter { QobuzCandidateMatcher.normalize(it.name) !in VARIOUS_ARTISTS }
            .filterNot { isSuperstringPseudo(nName, QobuzCandidateMatcher.normalize(it.name)) }
            .distinctBy { it.id }
        val scored = candidates
            .map { it to QobuzCandidateMatcher.artistSimilarity(nName, QobuzCandidateMatcher.normalize(it.name)) }
            .filter { it.second >= ARTIST_MATCH_THRESHOLD }
            .sortedByDescending { it.second }
        // Collapse the artist's OWN duplicate entities (same name, different id):
        // dedup by normalized NAME so "distinct candidate" == "distinct artist".
        val distinctArtists = scored.distinctBy { QobuzCandidateMatcher.normalize(it.first.name) }
        val best = distinctArtists.firstOrNull()?.first ?: return unchanged(ytAlbums, ytSingles)

        val nBest = QobuzCandidateMatcher.normalize(best.name)
        // Qobuz's artist-discography endpoint is a firehose: real studio albums
        // PLUS singles, compilations, and every release the artist is merely
        // FEATURED on (tribute/cover singles credited to other artists, guest
        // spots credited to "X, Lil Wayne", etc.). YouTube's album grid is
        // clean/curated, so Qobuz is here ONLY to fill genuine ALBUM gaps.
        // Keep a release only if it is:
        //   1. the matched artist's OWN release — the PRIMARY artist's name is
        //      EXACTLY the match (strict, not subset). Drops features/collabs
        //      ("Jay Sean, Lil Wayne" ≠ "Lil Wayne") and covers by others.
        //   2. album-length — enough tracks to be an album, not a single/EP.
        //      release_type is null from this endpoint, so track count is the
        //      only reliable signal (real albums ~8-24 tracks; singles 1-3).
        val ownAlbums = apiClient.getArtistAlbums(best.id).filter {
            QobuzCandidateMatcher.normalize(it.artist?.name.orEmpty()) == nBest &&
                it.tracks_count >= ALBUM_MIN_TRACKS
        }
        if (ytAlbums.isNotEmpty()) {
            // A confident single match still needs corroboration: at least one Qobuz
            // release title must overlap a YT one, else we may have matched a homonym.
            val ytKeys = ytAlbums.map { QobuzCandidateMatcher.normalize(it.title) }.toSet()
            val overlap = ownAlbums.any { QobuzCandidateMatcher.normalize(it.title) in ytKeys }
            if (!overlap) return unchanged(ytAlbums, ytSingles)
        } else {
            // No YT titles to corroborate against → an ambiguous name is unresolvable.
            if (distinctArtists.size >= 2) return unchanged(ytAlbums, ytSingles)
        }

        // Gap-fill the ALBUMS lane only; singles/EPs stay 100% YouTube (we don't
        // supplement them — that's where most of the Qobuz noise lives).
        MergedDiscography(
            albums = DiscographyMerger.gapFill(ytAlbums, ownAlbums.map { it.toAlbumSummary(best.name) }),
            singles = ytSingles,
        )
    }.getOrElse { unchanged(ytAlbums, ytSingles) }

    private fun unchanged(a: List<AlbumSummary>, s: List<AlbumSummary>) = MergedDiscography(a, s)

    /** A candidate whose normalized name strictly contains the query PLUS extra
     *  tokens (e.g. "my bloody valentine tribute") is a pseudo-artist — drop it so
     *  it can't create false ambiguity for the real one. Exact match (c == q) stays. */
    private fun isSuperstringPseudo(query: String, cand: String): Boolean {
        val q = query.split(" ").filter { it.isNotEmpty() }.toSet()
        val c = cand.split(" ").filter { it.isNotEmpty() }.toSet()
        return c != q && c.containsAll(q) && (c - q).isNotEmpty()
    }

    private companion object {
        // ponytail: subset-coverage in artistSimilarity already returns ~1.0 for a
        // superset/canonical name match, so 0.6 comfortably admits real matches while
        // rejecting partial-token noise. Bump only if a real false-graft slips through.
        const val ARTIST_MATCH_THRESHOLD = 0.6f
        // Minimum track count for a Qobuz release to count as an album (not a
        // single/EP). Singles are 1-3 tracks; real albums ~8-24. 5 clears the
        // junk singles while keeping short albums. Tune here if a real album is
        // ever excluded.
        const val ALBUM_MIN_TRACKS = 5
        val VARIOUS_ARTISTS = setOf(
            "various artists", "various", "verschiedene interpreten", "multi artistes", "va",
        )
    }
}

private fun QbdlxAlbumItem.toAlbumSummary(artistName: String) = AlbumSummary(
    id = id,
    title = title,
    artist = artist?.name?.ifBlank { artistName } ?: artistName,
    thumbnailUrl = image?.large ?: image?.small ?: image?.thumbnail,
    year = release_date_original, // "YYYY-MM-DD"; DiscographyMerger parses the year
    source = AlbumSource.QOBUZ,
    trackCount = tracks_count.takeIf { it > 0 }, // #481: tells the album screen it's an album
)
