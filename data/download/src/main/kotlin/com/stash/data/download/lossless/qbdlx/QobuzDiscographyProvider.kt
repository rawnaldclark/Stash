package com.stash.data.download.lossless.qbdlx

import android.util.Log
import com.stash.core.data.discography.DiscographySupplement
import com.stash.core.data.discography.MergedDiscography
import com.stash.core.data.discography.newReleaseYears
import com.stash.data.download.lossless.qobuz.QobuzCandidateMatcher
import com.stash.data.ytmusic.model.AlbumSource
import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.DatedRelease
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

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
 *
 * Alongside the merge it passes on the artist's recent releases dated to the day
 * ([MergedDiscography.releaseDates]), for the artist page's Latest releases row.
 */
@Singleton
class QobuzDiscographyProvider @Inject constructor(
    private val apiClient: QbdlxApiClient,
) : DiscographySupplement {

    /** Test seam: today, for which releases can still be new ([newReleaseYears]). */
    internal var today: () -> LocalDate = { LocalDate.now() }

    /** Test seam: a monotonic clock in ms, for [NEWEST_BUDGET_MS]. */
    internal var elapsedMs: () -> Long = { System.nanoTime() / 1_000_000 }

    override suspend fun mergeInto(
        artistName: String,
        ytAlbums: List<AlbumSummary>,
        ytSingles: List<AlbumSummary>,
    ): MergedDiscography = runCatching {
        val startedAtMs = elapsedMs()
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
        val listed = apiClient.getArtistAlbums(best.id)
        val ownReleases = listed.filter { isOwn(it, nBest) }
        val ownAlbums = ownReleases.filter { it.tracks_count >= ALBUM_MIN_TRACKS }
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
            albums = DiscographyMerger.gapFill(
                ytAlbums,
                ownAlbums.map { it.toAlbumSummary(best.name) },
                ytSingles = ytSingles, // a CD single YouTube shelves as a single stays out of Albums (#755)
            ),
            singles = ytSingles,
            releaseDates = recentReleaseDates(
                artistId = best.id,
                nBest = nBest,
                ownReleases = ownReleases,
                listFull = listed.size >= QbdlxApiClient.ARTIST_ALBUMS_LIMIT,
                youTube = ytAlbums + ytSingles,
                startedAtMs = startedAtMs,
            ),
        )
    }.getOrElse { unchanged(ytAlbums, ytSingles) }

    private fun unchanged(a: List<AlbumSummary>, s: List<AlbumSummary>) = MergedDiscography(a, s)

    /** The matched artist's OWN release: the primary artist is exactly the match (a feature or a cover isn't). */
    private fun isOwn(item: QbdlxAlbumItem, nBest: String): Boolean =
        QobuzCandidateMatcher.normalize(item.artist?.name.orEmpty()) == nBest

    /**
     * The artist's own releases, any length, that can still be new ([newReleaseYears]), dated to
     * the day: the artist page's Latest releases row tells a release from the last weeks by them,
     * where YouTube's cards give the year alone. From the answer the gap-fill already read, so
     * usually no request of their own.
     *
     * That answer is most popular first and at most [QbdlxApiClient.ARTIST_ALBUMS_LIMIT] long, so
     * when it is full ([listFull]: the artist has more) a new release can be past the cut. Then,
     * and only if YouTube shows a release from those years, one more call asks for the newest
     * [NEWEST_LIMIT], unless the calls so far took [NEWEST_BUDGET_MS] or more: ArtistCache drops
     * the whole merge, gap-fill included, past its bound. Best effort: a failure leaves the dates
     * the first answer gave.
     */
    private suspend fun recentReleaseDates(
        artistId: Long,
        nBest: String,
        ownReleases: List<QbdlxAlbumItem>,
        listFull: Boolean,
        youTube: List<AlbumSummary>,
        startedAtMs: Long,
    ): List<DatedRelease> {
        val years = newReleaseYears(today())
        val youTubeHasOne = youTube.any { yearOf(it.year) in years }
        val askNewest = listFull && youTubeHasOne && elapsedMs() - startedAtMs < NEWEST_BUDGET_MS
        val newest = if (askNewest) {
            try {
                apiClient.getNewestArtistAlbums(artistId, NEWEST_LIMIT).filter { isOwn(it, nBest) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
        val dated = (ownReleases + newest).mapNotNull { item ->
            val date = item.release_date_original?.takeIf { ISO_DATE.matches(it) } ?: return@mapNotNull null
            if (yearOf(date) !in years) return@mapNotNull null
            DatedRelease(title = item.title, date = date, trackCount = item.tracks_count.takeIf { it > 0 })
        }.distinct()
        Log.d(TAG, "release dates: ${dated.size} from $years (listed ${ownReleases.size} own, newest call: $askNewest)")
        return dated
    }

    /** The year in "2026" or "2026-10-30"; null when there is none. */
    private fun yearOf(s: String?): Int? = s?.let { YEAR.find(it) }?.value?.toIntOrNull()

    /** A candidate whose normalized name strictly contains the query PLUS extra
     *  tokens (e.g. "my bloody valentine tribute") is a pseudo-artist — drop it so
     *  it can't create false ambiguity for the real one. Exact match (c == q) stays. */
    private fun isSuperstringPseudo(query: String, cand: String): Boolean {
        val q = query.split(" ").filter { it.isNotEmpty() }.toSet()
        val c = cand.split(" ").filter { it.isNotEmpty() }.toSet()
        return c != q && c.containsAll(q) && (c - q).isNotEmpty()
    }

    internal companion object {
        // ponytail: subset-coverage in artistSimilarity already returns ~1.0 for a
        // superset/canonical name match, so 0.6 comfortably admits real matches while
        // rejecting partial-token noise. Bump only if a real false-graft slips through.
        const val ARTIST_MATCH_THRESHOLD = 0.6f
        const val TAG = "QobuzDiscography"
        // Minimum track count for a Qobuz release to count as an album (not a
        // single/EP). Singles are 1-3 tracks; real albums ~8-24. 5 clears the
        // junk singles while keeping short albums. Tune here if a real album is
        // ever excluded.
        const val ALBUM_MIN_TRACKS = 5
        // The newest-first call ([recentReleaseDates]): enough for a few months of an
        // artist's releases, several editions of a single each included (Taylor Swift
        // listed six of "I Knew It, I Knew You" on 2026-11-03).
        const val NEWEST_LIMIT = 30
        // The search and the first list take ~0.4-0.8 s each, the extra call as long, and
        // ArtistCache bounds the whole merge at 4 s: past 2 s the network is slow, and the
        // dates aren't worth losing the gap-fill for.
        const val NEWEST_BUDGET_MS = 2_000L
        val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")
        val YEAR = Regex("""\d{4}""")
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
