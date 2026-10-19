package com.stash.core.data.genre

import com.stash.core.common.splitArtistCredits

/**
 * The artist a credit's genres are looked up and cached under.
 *
 * Genres belong to the act a song is credited to first: "Drake, 21 Savage" and "Drake feat. Rihanna"
 * both count toward Drake. So the key is the first act of [splitArtistCredits] (split on commas;
 * allowlisted comma names like "Earth, Wind & Fire" or "Tyler, The Creator" kept whole, also inside
 * a collaboration: "Earth, Wind & Fire, Chicago" -> "Earth, Wind & Fire"), with a trailing
 * featured-artist clause cut off ("A feat. B", "A ft. B", "A featuring B", "A (feat. B)"), which
 * the credit split leaves alone because it only ever splits on commas.
 *
 * This is the Library's primary-artist rule except inside collaborations, where `primaryArtist`
 * still cuts an allowlisted name at its first comma.
 *
 * The featured clause must follow whitespace, so a name that merely starts with "Ft." or contains
 * "ft" inside a word ("Daft Punk") is untouched. "with" and "&" are not cut: too many band names
 * contain them ("Sleeping With Sirens", "Mumford & Sons").
 *
 * The cache (`artist_genres`, primary key COLLATE NOCASE) and [TopGenresCalculator] both go through
 * [of], so a listen's credit and its cached genres always meet on the same key whatever form the
 * caller passes in.
 */
object ArtistGenreKey {

    private val FEATURING = Regex(
        """\s*[(\[]\s*(?:feat\.?|ft\.?|featuring)\s.*$|\s+(?:feat\.?|ft\.?|featuring)\s.*$""",
        RegexOption.IGNORE_CASE,
    )

    /** The cache key for [credit]; blank in, blank out. */
    fun of(credit: String): String {
        val primary = credit.splitArtistCredits().firstOrNull()?.trim().orEmpty()
        if (primary.isEmpty()) return ""
        return primary.replace(FEATURING, "").trim().ifEmpty { primary }
    }
}
