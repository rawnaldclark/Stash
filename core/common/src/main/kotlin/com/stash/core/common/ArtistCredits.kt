package com.stash.core.common

import java.text.Normalizer

/**
 * Single acts whose names happen to contain the characters a credit
 * parser would otherwise treat as separators (", ", "&", "+", "/", a
 * leading "x"), but that describe ONE act, not a collaboration.
 *
 * The list is deliberately small and *generic* — well-known, timeless
 * acts, so it reads as a general music-world fact rather than anything
 * tailored to a particular library. It is not meant to be exhaustive; it
 * only covers the names that would be visibly broken by splitting.
 */
val ARTIST_CREDIT_ALLOWLIST: Set<String> = setOf(
    "AC/DC",
    "Earth, Wind & Fire",
    "Tyler, The Creator",
    "Blood, Sweat & Tears",
    "Simon & Garfunkel",
    "Hall & Oates",
    "Sam & Dave",
    "Sonny & Cher",
    "Peaches & Herb",
    "Dan + Shay",
    "Chase & Status",
    "X Ambassadors",
    "X Japan",
)

/**
 * Splits a combined artist credit string into its individual acts, on
 * the `", "` delimiter the app itself uses when joining credits.
 *
 * Entries on [ARTIST_CREDIT_ALLOWLIST] are never split — neither on
 * their own nor as a segment inside a wider collaboration ("Earth, Wind &
 * Fire, Chicago" stays ["Earth, Wind & Fire", "Chicago"]). A
 * single-artist string ("Metro Boomin") round-trips to a single-element
 * list, so callers can always iterate the result instead of special-casing
 * separators.
 *
 * Known limitation (shared with [String.primaryArtist]): a single artist
 * whose own name contains ", " and isn't allowlisted is truncated
 * ("Peter, Bjorn and John" → ["Peter", "Bjorn and John"]). Fixing that needs a structured artist list
 * on TrackEntity; a comma-only split is the smallest conservative step
 * that fixes the reported collaboration cases.
 */
fun String.splitArtistCredits(): List<String> {
    val trimmed = trim()
    if (trimmed.isEmpty()) return emptyList()
    if (trimmed.isAllowlistedCredit()) return listOf(trimmed)

    val placeholders = mutableListOf<String>()
    var working = trimmed
    ARTIST_CREDIT_ALLOWLIST.forEach { name ->
        val token = "\u0001${placeholders.size}\u0001"
        working = working.replace(name, token, ignoreCase = true)
        placeholders.add(name)
    }

    return working
        .split(CREDIT_SEPARATOR)
        .mapNotNull { part ->
            var restored = part
            placeholders.forEachIndexed { index, name ->
                restored = restored.replace("\u0001$index\u0001", name)
            }
            restored.trim().ifEmpty { null }
        }
}

/**
 * Every distinct act credited across a track's [artist] and [albumArtist]
 * fields. Used by library queries that need to answer "does this track
 * belong to artist X?" — a collaboration credit ("Metro Boomin, Travis
 * Scott") counts for both "Metro Boomin" and "Travis Scott".
 */
fun expandArtistCredits(artist: String, albumArtist: String): List<String> =
    buildList {
        addAll(artist.splitArtistCredits())
        addAll(albumArtist.splitArtistCredits())
    }.distinct()

/**
 * True when [query] is one of the acts credited on a track carrying
 * [artist] + [albumArtist]. Exact whole-string matches are included, so a
 * combined-credit row ("Drake, 21 Savage") still matches its own display
 * name as well as each individual act.
 */
fun matchesArtistCredits(artist: String, albumArtist: String, query: String): Boolean {
    val trimmedQuery = query.trim()
    if (trimmedQuery.isEmpty()) return false
    return artist.equals(trimmedQuery, ignoreCase = true) ||
        albumArtist.equals(trimmedQuery, ignoreCase = true) ||
        expandArtistCredits(artist, albumArtist).any { it.equals(trimmedQuery, ignoreCase = true) }
}

/**
 * The primary act credited on a combined artist string, for DISPLAY.
 *
 * Splits on `", "` only — the single delimiter the app itself uses when
 * joining artist credits — so a collaboration like "Aarne, Toxi$, Big Baby
 * Tape" renders as "Aarne" without ever shredding band names that merely
 * contain separators ("Mumford & Sons", "Sleeping With Sirens", "Florence +
 * the Machine" all round-trip untouched because they have no comma).
 *
 * Entries on [ARTIST_CREDIT_ALLOWLIST] are never split, so comma-bearing
 * single acts ("Earth, Wind & Fire", "Blood, Sweat & Tears") survive too.
 *
 * Known limitation (shared with the long-standing LastFmScrobbler helper
 * this replaces): a single artist whose own name contains ", " is truncated
 * when it isn't allowlisted ("Peter, Bjorn and John" → "Peter"). Fixing that needs a structured artist
 * list on TrackEntity; a comma-only split is the smallest conservative
 * step that fixes the reported collaboration cases.
 *
 * Falls back to the trimmed input when nothing can be split, and returns
 * the input unchanged when it is blank.
 */
fun String.primaryArtist(): String {
    val trimmed = trim()
    if (trimmed.isEmpty()) return this
    if (trimmed.isAllowlistedCredit()) return trimmed
    return trimmed.substringBefore(", ").trim().ifEmpty { trimmed }
}

/**
 * The act a song's own artist link should open: [albumArtist] when the song's credit names it
 * (the clean "Drake" of "Drake feat. 21 Savage" on Drake's album), else the song's [artist].
 *
 * An album artist the credit doesn't name belongs to the album, not the song: a compilation's
 * "Various Artists" (Last.fm files big hits under one, "Blinding Lights" under "Now 35"), or
 * another act's collection (The Supremes on Diana Ross's "Ultimate Collection"). Names compare
 * word by word with case, accents and punctuation folded, so "T.Rex" names "T. Rex".
 */
fun songArtist(artist: String, albumArtist: String): String {
    if (albumArtist.isBlank()) return artist
    if (artist.isBlank()) return albumArtist
    val album = creditWords(albumArtist)
    val named = (artist.splitArtistCredits() + artist).any { act ->
        val words = creditWords(act)
        words.isNotEmpty() && album.isNotEmpty() && (album.contains(words) || words.contains(album))
    }
    return if (named) albumArtist else artist
}

/** [name]'s words with case and accents folded, padded with spaces: " t rex " for "T. Rex". */
private fun creditWords(name: String): String {
    val bare = Normalizer.normalize(name, Normalizer.Form.NFD).replace(COMBINING_MARKS, "")
    val words = foldCase(bare).split(NON_WORD).filter { it.isNotEmpty() }
    return if (words.isEmpty()) "" else words.joinToString(" ", prefix = " ", postfix = " ")
}

private val COMBINING_MARKS = Regex("\\p{M}+")
private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

private fun String.isAllowlistedCredit(): Boolean =
    ARTIST_CREDIT_ALLOWLIST.any { it.equals(this, ignoreCase = true) }

/** The `", "` join delimiter, tolerating missing/extra spaces. */
private val CREDIT_SEPARATOR = Regex("\\s*,\\s*")
/**
 * A key under which two strings are equal exactly when `a.equals(b, ignoreCase = true)` is true:
 * that compares char by char, equal when `lowercase(uppercase(c))` agrees, so folding every char
 * the same way gives one key per equivalence class (unlike `lowercase()`, which differs from it on
 * letters such as the Turkish dotted I, and unlike SQLite's LOWER(), which folds ASCII only).
 * Use it for grouping and list keys that must agree with `equals(ignoreCase = true)`.
 */
fun foldCase(s: String): String {
    val out = CharArray(s.length)
    for (i in s.indices) out[i] = Character.toLowerCase(Character.toUpperCase(s[i]))
    return String(out)
}
