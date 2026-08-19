package com.stash.core.common

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
 * whose own name contains ", " is truncated ("Tyler, The Creator" →
 * ["Tyler", "The Creator"]). Fixing that needs a structured artist list
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
 * ("Tyler, The Creator" → "Tyler"). Fixing that needs a structured artist
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

private fun String.isAllowlistedCredit(): Boolean =
    ARTIST_CREDIT_ALLOWLIST.any { it.equals(this, ignoreCase = true) }

/** The `", "` join delimiter, tolerating missing/extra spaces. */
private val CREDIT_SEPARATOR = Regex("\\s*,\\s*")