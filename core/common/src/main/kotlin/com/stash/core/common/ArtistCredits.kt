package com.stash.core.common

/**
 * Single acts whose names happen to contain the characters the credit parser
 * otherwise treats as separators (",", "&", "+", "/", a leading "x"), but
 * that describe ONE act, not a collaboration. A literal split would fabricate
 * artists that don't exist ("Earth" / "Wind" / "Fire", "AC" / "DC", ...).
 *
 * The list is small and deliberately *generic* — well-known, timeless acts,
 * so it reads as a general music-world fact rather than anything tailored to
 * a particular library. It is not meant to be exhaustive; it only covers the
 * names that would be visibly broken by splitting.
 */
val ARTIST_CREDIT_ALLOWLIST: Set<String> = setOf(
    "AC/DC",
    "Earth, Wind & Fire",
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
 * Splits a combined artist credit string into its individual acts.
 *
 * Understands the separators that actually appear in music metadata:
 * feature clauses ("feat.", "ft.", "featuring", "with"), commas,
 * ampersands, plus signs, slashes, and a standalone "x" ("Travis Scott
 * x Lil Baby"). Returns the distinct, trimmed acts in original order.
 *
 * Entries in [ARTIST_CREDIT_ALLOWLIST] are never split — neither on their
 * own nor as a segment inside a wider collaboration ("AC/DC & Guns N'
 * Roses" stays ["AC/DC", "Guns N' Roses"]).
 *
 * A single-artist string ("Metro Boomin") round-trips to a single-element
 * list, so callers can always iterate the result instead of special-casing
 * separators.
 */
fun String.splitArtistCredits(): List<String> {
    val trimmed = trim()
    if (trimmed.isEmpty()) return emptyList()
    if (trimmed.isAllowlistedCredit()) return listOf(trimmed)

    // "Post Malone (feat. 21 Savage)" / "[ft. X]" → "Post Malone feat. 21 Savage"
    // so the feature-connective splitter sees the clause.
    val text = trimmed
        .replace(
            Regex("[(\\[]\\s*(featuring|feat\\.|feat|ft\\.|ft)(?=\\s|\\]|\\))", RegexOption.IGNORE_CASE),
            "$1 ",
        )
        .replace(Regex("[)\\]\\n]"), "")

    return text
        .split(FEATURE_CONNECTIVES)
        .flatMap { segment -> segment.splitPunctuationCredits() }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
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
fun matchesArtistCredits(artist: String, albumArtist: String, query: String): Boolean =
    artist.equals(query, ignoreCase = true) ||
        albumArtist.equals(query, ignoreCase = true) ||
        expandArtistCredits(artist, albumArtist).any { it.equals(query, ignoreCase = true) }

/**
 * The primary act credited on a combined artist string — the first act in
 * [splitArtistCredits]. Lets display surfaces (Now Playing, album cards, the
 * Artists grid) render "Aarne" for a collaboration credit like "Aarne, Toxi$,
 * Big Baby Tape" instead of the full run-on credit. Falls back to the trimmed
 * input when nothing can be split.
 */
fun String.primaryArtist(): String =
    splitArtistCredits().firstOrNull() ?: trim()

/**
 * `feat.` / `ft.` / `featuring` / `with` clauses. Non-capturing so the
 * connective token never leaks into the split result (String.split keeps
 * capture-group matches otherwise). Longest token first ("featuring" before
 * "feat") and a required trailing space, so the shorter "feat" can never
 * eat the prefix of "featuring" and strand "uring".
 */
private val FEATURE_CONNECTIVES =
    Regex("\\s*(?:featuring|feat\\.|feat|ft\\.|ft|with)\\s+", RegexOption.IGNORE_CASE)

/** Comma, ampersand, plus, slash, and standalone "x" separators. */
private val PUNCTUATION_SEPARATORS =
    Regex("\\s*(?:,|&|\\+|/|\\bx\\b)\\s*", RegexOption.IGNORE_CASE)

private fun String.isAllowlistedCredit(): Boolean =
    ARTIST_CREDIT_ALLOWLIST.any { it.equals(this, ignoreCase = true) }

/**
 * Splits a single segment on punctuation separators, but first shields any
 * [ARTIST_CREDIT_ALLOWLIST] name appearing as a substring (case-insensitively)
 * with a placeholder. That keeps "AC/DC & Guns N' Roses" → ["AC/DC",
 * "Guns N' Roses"] instead of splitting AC/DC on its slash.
 */
private fun String.splitPunctuationCredits(): List<String> {
    if (isAllowlistedCredit()) return listOf(this)

    val placeholders = mutableListOf<String>()
    var working = this
    ARTIST_CREDIT_ALLOWLIST.forEach { name ->
        val token = "\u0001${placeholders.size}\u0001"
        working = working.replace(name, token, ignoreCase = true)
        placeholders.add(name)
    }

    return working
        .split(PUNCTUATION_SEPARATORS)
        .mapNotNull { part ->
            var restored = part
            placeholders.forEachIndexed { index, name ->
                restored = restored.replace("\u0001$index\u0001", name)
            }
            restored.trim().ifEmpty { null }
        }
}
