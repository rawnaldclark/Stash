package com.stash.core.common

/**
 * The library's one "same song" rule: what `tracks.canonical_title` and `tracks.canonical_artist`
 * hold for every row, whoever writes it (sync, discovery, the YouTube canonicalizer, search
 * downloads, stream stubs, likes, hand-offs), and what every lookup by words computes. One rule so
 * a song filed by two paths is found as one row instead of being filed twice. (One exception: a
 * song the user saves by hand under a blocked loose key keeps [strictCanonical] words, see
 * `canonicalWordsForSave` in core:data.)
 *
 * Loose on purpose, so the same song from two sources meets:
 *  - [title]: every bracketed group ("(feat. X)", "[Live]", "(Remastered 2011)") and a trailing
 *    "feat. / ft. / featuring ..." are dropped, then the rest is lowercased and trimmed.
 *    Punctuation stays: "Don't Stop (feat. X)" is "don't stop".
 *  - [artist]: split on `,` `;` `&` `/` and the word "and", each part lowercased and trimmed, the
 *    parts sorted and joined with ", ": "B & A" is "a, b".
 *
 * Because the title rule drops brackets, two versions share one key: "Song (Live)" and "Song" are
 * both "song". A row found by its words alone may only stand for an incoming song when
 * [sameVersion] agrees too (the whole reuse rule is `SameSongRule` in core:data): a deny-list, so
 * only a performance word ([VersionWords]) one title names and the other doesn't keeps them apart.
 *
 * This is `TrackMatcher`'s rule, moved here unchanged so code outside the sync can call it without
 * injecting the matcher. Do not change its output: the blocklist keys (`artist|title`) and every
 * stored row are keyed by it, so a change re-keys the whole library. Pinned by TrackCanonicalTest.
 */
object TrackCanonical {

    /** A bracketed group: (remix), (feat. X), [live]. [version] reads exactly the groups [title] drops. */
    private val BRACKET_GROUP = Regex("""\s*[\(\[][^)\]]*[\)\]]""")

    /**
     * "feat", "ft", "featuring" and everything after. Note there is no word boundary in front, so
     * "Left Hand" loses "ft Hand" ("le"). Kept as is: the stored keys and the blocklist depend on it.
     * [version] doesn't repeat the quirk, so the reuse rule still tells such titles apart.
     */
    private val FEAT_TAIL = Regex("""\s*(feat\.?|ft\.?|featuring)\s+.*""", RegexOption.IGNORE_CASE)

    /** Common artist separators. */
    private val ARTIST_SEPARATOR = Regex("""\s*[,;&/]\s*|\s+(?:and|&)\s+""", RegexOption.IGNORE_CASE)

    /**
     * A credit outside brackets: a whole word after a space (so not "Left Hand", not a title that
     * starts with "Ft."), up to a dash that starts a version suffix, or to the end:
     * "Song feat. Jay-Z - Live" loses "feat. Jay-Z" and keeps "- Live".
     */
    private val CREDIT_TAIL = Regex(
        """(?<=\s)(?:feat\.?|ft\.?|featuring)\s+.*?(?=\s+[-\x{2013}\x{2014}]\s+|$)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The credit that opens a bracketed group, up to a dash or the group's end: "feat. X", "ft X",
     * "featuring X", "with X", "w/ X", "prod. X", "produced by X". It names other artists, never a
     * version, so a performance word in a name ("feat. The Cover Girls") counts for nothing, while
     * one after the dash ("with X - Live") still does.
     */
    private val CREDIT_GROUP = Regex(
        """^\s*(?:feat\.?|ft\.?|featuring|with|w/|prod\.?|produced\s+by)(?=[\s.:/]|$).*?(?=\s+[-\x{2013}\x{2014}]\s+|$)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The canonical (loose) form of a track title.
     *
     * @param title Raw track title.
     * @return Lowercased title without bracketed groups or a trailing credit.
     */
    fun title(title: String): String =
        title
            .replace(BRACKET_GROUP, "")
            .replace(FEAT_TAIL, "")
            .lowercase()
            .trim()

    /**
     * Whether a pair of canonical words is blank on either side, so it can't identify a song: a
     * title that is only brackets ("(Intro)", "[Untitled]") or only a credit ("Ft. Lauderdale"), or no
     * artist. Every never-keyed local import is ("", ""), so a lookup by a blank key would load them
     * all, and match every other song with a blank key. Every lookup by words skips such a pair.
     */
    fun isBlankKey(canonicalTitle: String, canonicalArtist: String): Boolean =
        canonicalTitle.isBlank() || canonicalArtist.isBlank()

    /**
     * The canonical (loose) form of an artist credit.
     *
     * @param artist Raw artist string (may name several artists).
     * @return Lowercased artist names, sorted, joined with ", ".
     */
    fun artist(artist: String): String =
        artist
            .split(ARTIST_SEPARATOR)
            .map { it.lowercase().trim() }
            .filter { it.isNotEmpty() }
            .sorted()
            .joinToString(", ")

    /**
     * What a title says about which recording it is. Two titles are the same version when both
     * parts are equal ([sameVersion]).
     *
     * @property base the title without its bracketed groups, a credit ("ft. X") and a dash suffix
     *   (" - Live"), in [strictCanonical] form. Two titles with one canonical key share it, except
     *   where the loose rule's mid-word "ft" cut different words ("Left Hand Free" and "Left Outside
     *   Alone" both key as "le"): the base keeps those two songs apart.
     * @property words the [VersionWords] named in the parts the loose rule drops: bracketed groups
     *   other than a credit, and a dash suffix. Never the base, so "Live Forever" names none.
     */
    data class Version(val base: String, val words: Set<String>)

    /**
     * The [Version] a title names.
     *
     * @param title Raw track title.
     * @param words which words name a version: the library rule's [VersionWords.of] (the default), or the stricter
     *   [VersionWords.forCatalogMatch] for [sameTake].
     */
    fun version(title: String, words: (String) -> Set<String> = VersionWords::of): Version {
        val versionText = StringBuilder()
        val unbracketed = BRACKET_GROUP.replace(title) { group ->
            val bracketed = group.value.trim()
            versionText.append(' ').append(CREDIT_GROUP.replace(bracketed.substring(1, bracketed.length - 1), ""))
            " "
        }
        val uncredited = CREDIT_TAIL.replace(unbracketed, " ")
        val dash = VersionWords.DASH_SUFFIX.find(uncredited)
        if (dash != null) versionText.append(' ').append(dash.value)
        val base = if (dash != null) uncredited.substring(0, dash.range.first) else uncredited
        return Version(base = strictCanonical(base), words = words(versionText.toString()))
    }

    /**
     * Whether two titles name the same version of a song. A deny-list: they are two versions only
     * when one names a performance word ([VersionWords]: live, remix, acoustic, sped up, Taylor's
     * Version...) that the other doesn't. Everything else is the same version: credits ("(feat. X)",
     * "(with X)", "(prod. X)"), presentation tags ("[Official Music Video]", "(Audio)", "(HD)"), a
     * remaster with or without a year, Deluxe, Mono / Stereo, Single / Album / Radio Version,
     * Explicit / Clean. So "STAY (with Justin Bieber)" and "STAY", "Song - Remastered 2011" and
     * "Song", "Loser (Fcukers Remix - Official Audio)" and "Loser - Fcukers Remix" are one version
     * each; "Song (Live)" and "Song" are two.
     */
    fun sameVersion(a: String, b: String): Boolean = version(a) == version(b)

    /**
     * [sameVersion] by the lossless matcher's stricter words ([VersionWords.forCatalogMatch]): a performance, a concert,
     * "A COLORS SHOW", a re-recording, an outtake, a rework or a version the title names in its own words ("(Australian
     * Version)") is another take too. For "is this recording already on the phone", where taking another take for the
     * song would keep the user from ever getting the one they asked for. The library's identity ([sameVersion]) stays as
     * it is.
     */
    fun sameTake(a: String, b: String): Boolean =
        version(a, VersionWords::forCatalogMatch) == version(b, VersionWords::forCatalogMatch)
}
