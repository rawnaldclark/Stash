package com.stash.core.data.genre

import com.stash.core.common.primaryArtist

/**
 * The artist a credit's genres are looked up and cached under.
 *
 * Genres belong to the act a song is credited to first: "Drake, 21 Savage" and "Drake feat. Rihanna"
 * both count toward Drake. So the key is the credit's primary artist — the same
 * [primaryArtist] collapse the Library and `artist_images` use (split on ", " only, allowlisted
 * comma names like "Earth, Wind & Fire" kept whole) — with a trailing featured-artist clause cut
 * off ("A feat. B", "A ft. B", "A featuring B", "A (feat. B)"), which primaryArtist leaves alone
 * because it only ever splits on commas.
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
        val primary = credit.primaryArtist().trim()
        if (primary.isEmpty()) return ""
        return primary.replace(FEATURING, "").trim().ifEmpty { primary }
    }
}
