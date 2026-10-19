package com.stash.feature.library

import com.stash.core.common.expandArtistCredits
import com.stash.core.common.matchesArtistCredits
import com.stash.core.common.primaryArtist
import com.stash.core.data.db.dao.ArtistSummary

/**
 * Builds the Library Artists tab's cards from the DAO's (artist, album artist) credit pairs.
 *
 * **Which cards.** One per primary act of a stored artist credit ("Aarne, Toxi$, Big Baby Tape"
 * lands on "Aarne"), as before. A card's art prefers the act's own credit, then any credit that
 * leads with the act.
 *
 * **What a card counts.** Exactly the songs its artist page lists. The page
 * ([com.stash.core.data.repository.MusicRepository.getTracksByArtist]) takes every song with
 * [matchesArtistCredits] for the card's name: features ("Future, Drake" is on Drake's page) and
 * songs whose ALBUM artist credits the act. The card used to count only songs whose own credit
 * leads with the act, so the two disagreed even before Stream only widened both.
 *
 * Counting each card by scanning every pair would be cards × pairs credit parses, seconds on a
 * big Stream only library. Instead each pair is filed once under every act its credits name,
 * folded the way `equals(ignoreCase = true)` compares (see [foldCase]), and a card reads its
 * own bucket: O(pairs × credits per pair).
 *
 * **What Delete All by Artist removes.** [ArtistInfo.downloadedCount]: downloaded songs whose
 * credit leads with the act (see [artistCardTracks]), and only those the page's credit search
 * returns, since that is where the delete reads them from. Deleting the whole page would take
 * features from other acts' cards and compilations.
 *
 * A card whose page would list nothing (a blank credit; a primary act its own credit split
 * doesn't name, like "Earth" from "Earth, Wind & Fire, Chicago") is left out: it led to an
 * empty page.
 *
 * Pure. The caller runs it off Main, and only when the DAO rows change.
 */
internal fun buildArtistCards(pairs: List<ArtistSummary>): List<ArtistInfo> {
    // Songs per act, filed under every act a pair's credits name. A set per pair, so a pair
    // whose artist and album artist name the same act is counted once, as the page lists it once.
    val pageTotals = HashMap<String, PageTotals>()
    for (pair in pairs) {
        for (key in creditKeys(pair)) {
            pageTotals.getOrPut(key) { PageTotals() }.add(pair)
        }
    }

    // Each stored artist credit with its song count and art, in the order the DAO's old
    // per-credit query gave (most songs first, then by name): cards and art picks follow it.
    val credits = pairs
        .groupBy { it.artist }
        .map { (artist, group) ->
            CreditSummary(
                artist = artist,
                trackCount = group.sumOf { it.trackCount },
                artUrl = group.mapNotNull { it.artUrl }.maxOrNull(),
            )
        }
        .sortedWith(compareByDescending<CreditSummary> { it.trackCount }.thenBy { it.artist })

    // What the card's delete can reach: downloaded, credit leads with the act, AND on its page.
    val deletable = HashMap<String, Int>()
    for (pair in pairs) {
        if (pair.downloadedCount == 0) continue
        val primary = pair.artist.primaryArtist()
        if (matchesArtistCredits(pair.artist, pair.albumArtist, primary)) {
            deletable.merge(primary, pair.downloadedCount, Int::plus)
        }
    }

    return credits
        .groupBy { it.artist.primaryArtist() }
        .mapNotNull { (primary, group) ->
            val onPage = pageTotals[foldCase(primary.trim())] ?: return@mapNotNull null
            ArtistInfo(
                name = primary,
                trackCount = onPage.trackCount,
                totalDurationMs = onPage.totalDurationMs,
                artUrl = group.firstOrNull { it.artist == primary }?.artUrl
                    ?: group.firstNotNullOfOrNull { it.artUrl },
                downloadedCount = deletable[primary] ?: 0,
            )
        }
}

/**
 * Every key a page query for an act can match on this pair: the whole artist and album-artist
 * strings and each act they credit, mirroring [matchesArtistCredits]. Blank keys are dropped: a
 * blank query matches nothing.
 */
private fun creditKeys(pair: ArtistSummary): Set<String> {
    val keys = HashSet<String>()
    fun file(credit: String) {
        if (credit.isNotBlank()) keys += foldCase(credit)
    }
    file(pair.artist)
    file(pair.albumArtist)
    expandArtistCredits(pair.artist, pair.albumArtist).forEach(::file)
    return keys
}

/**
 * A key under which two strings are equal exactly when `a.equals(b, ignoreCase = true)` is true:
 * that compares char by char, equal when `lowercase(uppercase(c))` agrees, so folding every char
 * the same way gives one key per equivalence class (unlike `lowercase()`, which differs from it on
 * letters such as the Turkish dotted I).
 */
internal fun foldCase(s: String): String = com.stash.core.common.foldCase(s)

/** Song count and duration of an artist page, accumulated pair by pair. */
private class PageTotals {
    var trackCount = 0
        private set
    var totalDurationMs = 0L
        private set

    fun add(pair: ArtistSummary) {
        trackCount += pair.trackCount
        totalDurationMs += pair.totalDurationMs
    }
}

/** One stored artist credit: its songs across album artists, and its art. */
private data class CreditSummary(
    val artist: String,
    val trackCount: Int,
    val artUrl: String?,
)
