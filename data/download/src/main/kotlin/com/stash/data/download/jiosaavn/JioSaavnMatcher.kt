package com.stash.data.download.jiosaavn

import com.stash.data.download.lossless.TrackQuery
import java.text.Normalizer
import kotlin.math.abs

data class JioSaavnMatch(
    val song: JioSaavnSong,
    val media: JioSaavnMediaLink,
    val confidence: Float,
)

/** Conservative matcher: an uncertain result must fall through to YouTube. */
object JioSaavnMatcher {
    fun best(query: TrackQuery, songs: List<JioSaavnSong>): JioSaavnMatch? {
        val ranked = songs.mapNotNull { score(query, it) }.sortedByDescending { it.rankScore }
        val top = ranked.firstOrNull() ?: return null
        val runnerUp = ranked.getOrNull(1)
        if (
            runnerUp != null &&
            top.rankScore - runnerUp.rankScore < MIN_MARGIN &&
            !albumDisambiguates(query, top.match.song, runnerUp.match.song)
        ) return null
        return top.match
    }

    private fun score(query: TrackQuery, song: JioSaavnSong): RankedMatch? {
        val media = song.downloadUrl.firstOrNull {
            it.quality.equals("320kbps", ignoreCase = true) && it.url.startsWith("https://")
        } ?: return null
        val candidateArtist = song.artists.primary.joinToString(" ") { it.name }
        if (candidateArtist.isBlank()) return null
        if (versionSignature(query.title) != versionSignature(song.name)) return null
        if ((artistImpersonationMarkers(candidateArtist) - artistImpersonationMarkers(query.artist)).isNotEmpty()) {
            return null
        }
        if (query.explicit != null && query.explicit != song.explicitContent) return null

        val title = similarity(normalize(query.title), normalize(song.name))
        val artist = artistSimilarity(normalize(query.artist), normalize(candidateArtist))
        if (title < MIN_TITLE || artist < MIN_ARTIST) return null
        val duration = durationSimilarity(query.durationMs, song.duration) ?: return null
        val albumBonus = (albumSimilarity(query, song) ?: 0f) * ALBUM_WEIGHT
        // Keep the uncapped score for ranking so a strong album match can
        // disambiguate otherwise-identical recordings. Cap only the public
        // confidence value exposed to downstream callers.
        val rankScore = title * TITLE_WEIGHT + artist * ARTIST_WEIGHT +
            duration * DURATION_WEIGHT + albumBonus
        val threshold = if (query.durationMs != null && song.duration != null) {
            MIN_WITH_DURATION
        } else {
            MIN_WITHOUT_DURATION
        }
        if (rankScore < threshold) return null
        return RankedMatch(
            match = JioSaavnMatch(song, media, rankScore.coerceAtMost(0.99f)),
            rankScore = rankScore,
        )
    }

    private fun durationSimilarity(targetMs: Long?, candidateSec: Int?): Float? {
        if (targetMs == null || targetMs <= 0 || candidateSec == null || candidateSec <= 0) return 1f
        val targetSec = targetMs / 1000.0
        val difference = abs(targetSec - candidateSec)
        val tolerance = maxOf(8.0, targetSec * 0.03)
        if (difference > tolerance) return null
        return (1.0 - difference / tolerance).toFloat().coerceIn(0f, 1f)
    }

    private fun albumDisambiguates(
        query: TrackQuery,
        top: JioSaavnSong,
        runnerUp: JioSaavnSong,
    ): Boolean {
        val topAlbum = albumSimilarity(query, top) ?: return false
        val runnerUpAlbum = albumSimilarity(query, runnerUp) ?: 0f
        return topAlbum >= MIN_EXACT_ALBUM && topAlbum - runnerUpAlbum >= MIN_ALBUM_ADVANTAGE
    }

    private fun albumSimilarity(query: TrackQuery, song: JioSaavnSong): Float? {
        val requestedAlbum = query.album?.takeIf { it.isNotBlank() } ?: return null
        val candidateAlbum = song.album?.name?.takeIf { it.isNotBlank() } ?: return null
        return similarity(normalize(requestedAlbum), normalize(candidateAlbum))
    }

    private fun versionSignature(value: String): Set<String> {
        val normalized = normalize(value)
        return VERSION_MARKERS.filterTo(linkedSetOf()) { marker ->
            Regex("(?:^|\\s)${Regex.escape(marker)}(?:$|\\s)").containsMatchIn(normalized)
        }
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase()
        .replace(Regex("(?i)\\b(feat\\.?|ft\\.?|featuring)\\b.*"), " ")
        .replace(Regex("[^\\p{L}\\p{N}\\p{S}\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun similarity(a: String, b: String): Float {
        val left = a.split(' ').filter { it.isNotBlank() }.toSet()
        val right = b.split(' ').filter { it.isNotBlank() }.toSet()
        if (left.isEmpty() || right.isEmpty()) return 0f
        return left.intersect(right).size.toFloat() / left.union(right).size.toFloat()
    }

    private fun artistSimilarity(a: String, b: String): Float {
        val left = a.split(' ').filter { it.isNotBlank() }.toSet()
        val right = b.split(' ').filter { it.isNotBlank() }.toSet()
        if (left.isEmpty() || right.isEmpty()) return 0f
        val overlap = left.intersect(right)
        val jaccard = overlap.size.toFloat() / left.union(right).size.toFloat()
        // A source may omit featured artists from its primary credit, but a
        // candidate must not add arbitrary identity words (for example,
        // "Journey Tribute Band" for Journey). Only allow candidateâŠ†target.
        val subset = overlap.size == right.size && overlap.any {
            it.length > 3 || it.any { ch -> !ch.isLetterOrDigit() }
        }
        return if (subset) 1f else jaccard
    }

    private fun artistImpersonationMarkers(value: String): Set<String> {
        val normalized = normalize(value)
        return ARTIST_IMPERSONATION_MARKERS.filterTo(linkedSetOf()) { marker ->
            Regex("(?:^|\\s)${Regex.escape(marker)}(?:$|\\s)").containsMatchIn(normalized)
        }
    }

    private const val MIN_TITLE = 0.82f
    private const val MIN_ARTIST = 0.78f
    private const val TITLE_WEIGHT = 0.50f
    private const val ARTIST_WEIGHT = 0.35f
    private const val DURATION_WEIGHT = 0.15f
    private const val ALBUM_WEIGHT = 0.03f
    private const val MIN_WITH_DURATION = 0.88f
    private const val MIN_WITHOUT_DURATION = 0.93f
    private const val MIN_MARGIN = 0.06f
    private const val MIN_EXACT_ALBUM = 0.90f
    private const val MIN_ALBUM_ADVANTAGE = 0.35f
    private data class RankedMatch(val match: JioSaavnMatch, val rankScore: Float)
    private val VERSION_MARKERS = listOf(
        "karaoke", "instrumental", "cover", "tribute", "live", "concert",
        "remix", "acoustic", "sped up", "slowed", "nightcore", "edit", "extended",
    )
    private val ARTIST_IMPERSONATION_MARKERS = listOf("karaoke", "cover", "tribute", "impersonator")
}
