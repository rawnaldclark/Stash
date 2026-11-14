package com.stash.data.download.lossless.qbdlx

import com.stash.data.download.lossless.qobuz.QobuzCandidateMatcher
import com.stash.data.ytmusic.model.AlbumSummary

/**
 * Gap-fill merge: YouTube stays authoritative. Returns YT's albums plus any
 * Qobuz albums whose (normalized) title YouTube doesn't already have, ordered
 * newest-first. On a title collision the YouTube entry is kept and the Qobuz
 * duplicate dropped — Qobuz only ever ADDS albums YouTube is missing (e.g. My
 * Bloody Valentine's *Loveless*), never replaces one YouTube already lists.
 *
 * A title YouTube lists on its Singles shelf is no gap either (#755): a Qobuz CD
 * single with bonus tracks passes the album-length check, but YouTube already
 * filed it as a single, so it must not turn up in the Albums row.
 */
object DiscographyMerger {

    fun gapFill(
        yt: List<AlbumSummary>,
        qobuz: List<AlbumSummary>,
        ytSingles: List<AlbumSummary> = emptyList(),
    ): List<AlbumSummary> {
        if (qobuz.isEmpty()) return yt
        val ytTitles = (yt + ytSingles).mapTo(HashSet()) { key(it.title) }
        val gaps = qobuz.filter { key(it.title) !in ytTitles }
        if (gaps.isEmpty()) return yt
        return (yt + gaps).sortedWith(compareByDescending { yearInt(it.year) }) // nulls (Int.MIN) last
    }

    private fun key(title: String) = QobuzCandidateMatcher.normalize(title)

    /** Parse a 4-digit year out of "1991" or "1991-11-04"; Int.MIN_VALUE when unknown. */
    private fun yearInt(s: String?): Int {
        if (s.isNullOrBlank()) return Int.MIN_VALUE
        return Regex("\\d{4}").find(s)?.value?.toIntOrNull() ?: Int.MIN_VALUE
    }
}
