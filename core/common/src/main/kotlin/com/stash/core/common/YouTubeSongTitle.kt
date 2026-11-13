package com.stash.core.common

import java.text.Normalizer

/**
 * The song a YouTube Music library row really is, read from its video title and channel: what a
 * catalog (Qobuz, Tidal) would list as artist and title. YouTube rows carry whatever the upload had,
 * "Artist - Song (Official Video)", "KEXP | Phantogram - When I'm Small (Live on KEXP)", a channel
 * as the artist ("ArtistVEVO", "neilyoungchannel"), so the lossless lookups searched and scored
 * against noise. Of 6,843 labelled library rows, the raw fields named the right artist and title
 * 78.9% of the time; [parse] gets 91.7%, and keeps 88% of the labelled performance versions
 * (pinned by YouTubeSongTitleTest).
 *
 * Text only, no network: a row whose identity needs outside knowledge (a label channel, a
 * misspelled artist) is left as close to its fields as the rules allow. Not the library's identity
 * ([TrackCanonical] keys stay as they are); this only shapes lookup queries.
 */
object YouTubeSongTitle {

    /**
     * @property artist the performing artist, as the row names it.
     * @property title the song title without the artist, upload noise or version.
     * @property version every version the row names, comma-joined ("Live on KEXP", "2015 Remaster,
     *   Live"), or "".
     */
    data class Parsed(val artist: String, val title: String, val version: String) {
        /** The title as a lookup query carries it: the version back in brackets, where the matcher reads it. */
        val queryTitle: String get() = if (version.isBlank()) title else "$title ($version)"
    }

    /** Upload noise inside brackets or after a bar: never a version. */
    private val NOISE = Regex(
        "^(official\\s*)?(hd\\s*|4k\\s*|hq\\s*)?(music\\s*)?(video|audio|visuali[sz]er|lyrics?(\\s*video)?|clip|mv|m/v|" +
            "videoclip|video\\s*oficial|official|hd|hq|4k|remastered\\s*(in\\s*)?(hd|4k))(\\s*(hd|4k|hq))?$|" +
            "^(lyrics?|letra|with lyrics|audio only|full song|explicit|clean|uncensored|out now|new song.*|premiere|\\d{4}|visualiser)$",
        RegexOption.IGNORE_CASE,
    )
    private val NOISE_PHRASE = Regex("^(official|lyric|music)\\b.*\\b(video|audio)\\b", RegexOption.IGNORE_CASE)
    private val TRAILING_NOISE = Regex(
        "\\s+(official\\s+(music\\s+)?(video|audio)|lyric video|music video|visuali[sz]er|lyrics|hd|hq|4k|closed captioned)\\s*$",
        RegexOption.IGNORE_CASE,
    )

    /** Words that make a bracket, bar or dash part a version of the song rather than more title. */
    private val VERSION = Regex(
        "\\b(live|remix|mix|acoustic|unplugged|session|sessions|demo|instrumental|cover|edit|version|remaster(ed)?|mono|" +
            "stereo|sped|slowed|reverb|piano|orchestral|a ?cappella|karaoke|extended|radio|club|dub|rework|bootleg|vip|bonus|" +
            "re-?record(ed|ing)?|outtakes?|performance|concert|colors show)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val CREDIT = Regex("^(feat|ft|with|prod|arr)\\b", RegexOption.IGNORE_CASE)

    /** An uploader rather than an artist: VEVO/Topic channels, live-session channels, "…Official", "…Covers", handles with numbers. */
    private val CHANNELISH = Regex(
        "(vevo$|\\s-\\s?topic$|channel|official$|covers?$|\\btv$|records?$|recordings$|\\bmusic$|\\d{3,}|^kexp$|^audiotree|" +
            "^colors$|^npr|^genius$|^cercle$)",
        RegexOption.IGNORE_CASE,
    )

    private val BRACKET = Regex("\\s*[(\\[]([^)\\]]*)[)\\]]")
    private val TRACK_NUMBER = Regex("^\\d{1,2}\\s*[-.]\\s+")
    private val BAR = Regex("\\s+(?:[|｜]|//)\\s+")
    private val QUOTED = Regex("^(.*?)[\"“”]([^\"“”]+)[\"“”](.*)$")
    private val LEADING_DASH = Regex("^\\s*[-–—]\\s*")
    private val DASH = Regex("\\s+[-–—]\\s*|\\s*[-–—]\\s+")
    private val FEAT_TAIL = Regex("\\s*\\b(feat\\.?|ft\\.?|featuring)\\s.*$", RegexOption.IGNORE_CASE)
    private val PERFORMING = Regex("\\s+performing\\s*$", RegexOption.IGNORE_CASE)
    private val COMPOSER_PREFIX = Regex("^([^:]{2,40}):\\s+(.+)$")
    private val FROM_NOTE = Regex("^from\\b", RegexOption.IGNORE_CASE)

    /** The last " - part" of a string, its text in group 1. */
    private val DASH_TAIL = Regex("\\s+[-\u2013\u2014]\\s*([^-\u2013\u2014]*)$")

    /**
     * A last dash before nothing but one bracketed part, the bracket in group 1: " - (The Nightmare
     * Before Christmas)". Group 1 is absent when step 1 already took the bracket out as noise or a
     * version ("Song - (Official Video)" leaves "Song -").
     */
    private val NOTE_TAIL = Regex("\\s+[-\u2013\u2014]\\s*([(\\[][^)\\]]*[)\\]])?\\s*$")

    /**
     * @param title the row's title as YouTube had it.
     * @param artist the row's artist (often the uploading channel).
     */
    fun parse(title: String, artist: String): Parsed {
        var t = title.trim()
        var a = cleanArtist(artist)
        // Every version the row names is kept: "Purple Rain (2015 Remaster) (Live)" is the live take.
        val versions = mutableListOf<String>()
        fun addVersion(v: String) {
            val clean = LEADING_DASH.replace(v, "").trim()
            if (clean.isNotEmpty() && versions.none { it.equals(clean, ignoreCase = true) }) versions += clean
        }
        var hadNoise = false

        // 1. Bracket groups: noise goes, a version is kept apart, anything else is part of the title.
        // A version word beats a noise phrase: "(Official Live Video)" is still the live take.
        t = BRACKET.replace(t) { m ->
            val inner = m.groupValues[1].trim()
            when {
                NOISE.matches(inner) -> { hadNoise = true; "" }
                VERSION.containsMatchIn(inner) && !CREDIT.containsMatchIn(inner) -> { addVersion(inner); "" }
                NOISE_PHRASE.containsMatchIn(inner) -> { hadNoise = true; "" }
                else -> m.value
            }
        }
        if (TRAILING_NOISE.containsMatchIn(t)) { hadNoise = true; t = TRAILING_NOISE.replace(t, "") }
        t = TRACK_NUMBER.replace(t, "")

        // 2. "Song | rest" / "Song // rest": the rest is a version or noise ("aloneintokyo // hollows" puts the artist first).
        val bar = t.split(BAR)
        if (bar.size > 1) {
            if (bar.size == 2 && jaccard(bar[0], a) >= 0.8f) {
                t = bar[1]
            } else {
                t = bar[0]
                val rest = bar.drop(1).joinToString(" ")
                if (VERSION.containsMatchIn(rest)) addVersion(rest)
                hadNoise = true
            }
        }

        // 3. Artist "Title", or "Title" - Artist.
        // Version words after the quote stay: 'Cage The Elephant performing "Cold Cold Cold" Live on KCRW'.
        QUOTED.find(t)?.let { q ->
            val (before, quoted, after) = q.destructured
            val beforeDash = DASH_TAIL.find(before)
            if (before.isBlank() && LEADING_DASH.containsMatchIn(after) && after.trim().length > 1) {
                a = LEADING_DASH.replace(after, "").trim()
                t = quoted.trim()
            } else if (beforeDash != null && FROM_NOTE.containsMatchIn(beforeDash.groupValues[1])) {
                // 'Lose Yourself - From "8 Mile" Soundtrack': a soundtrack note, not an artist and a title.
                t = before.substring(0, beforeDash.range.first)
            } else if (before.isNotBlank() && (jaccard(FEAT_TAIL.replace(before, ""), a) >= 0.5f || hadNoise ||
                    CHANNELISH.containsMatchIn(artist) || PERFORMING.containsMatchIn(before))
            ) {
                val lead = PERFORMING.replace(FEAT_TAIL.replace(before, ""), "").trimEnd('.', ' ', '-').trim()
                if (lead.isNotEmpty() && jaccard(lead, a) < 0.5f) a = lead
                t = quoted.trim()
                if (VERSION.containsMatchIn(after)) addVersion(after)
            }
        }

        // 4. "Left - Right": YouTube's "Artist - Title", unless the row says otherwise.
        // A dash before nothing but a bracket is not that split. YouTube Music lists Danny Elfman's
        // "Overture - (The Nightmare Before Christmas)", and Tidal the same: it is his "Overture" with
        // a note on it, not a song "(The Nightmare Before Christmas)" by "Overture", which no catalog
        // title matched. The bracket is set aside here and goes back on the title below, as the
        // catalogs write it; a dash step 1 left bare ("Song - (Official Video)") just goes.
        val noteTail = NOTE_TAIL.find(t)
        if (noteTail != null) t = t.substring(0, noteTail.range.first)
        val segs = t.split(DASH)
        if (segs.size > 1) {
            val left = segs[0]
            val artistTokens = tokens(a)
            val leftIsArtist = jaccard(left, a) >= 0.5f || (artistTokens.isNotEmpty() && tokens(left).containsAll(artistTokens))
            val credited = artist.contains(',') // "Orchestra, Conductor, Composer": a catalog-style credit
            val keepsDash = credited || left.contains(':')
            val right = segs[1]
            val channel = CHANNELISH.containsMatchIn(artist)
            var rest = segs.drop(1)
            when {
                leftIsArtist -> { if (left.length >= a.length) a = left; t = rest.first(); rest = rest.drop(1) }
                jaccard(right, a) >= 0.5f -> { t = left; rest = rest.drop(1) } // "Title - Artist"
                // A real artist's row keeps its artist when the right side is a version or a note,
                // not a title: "Until Morning - A COLORS SHOW" (James Vickery), "Song - From X".
                (VERSION.containsMatchIn(right) || FROM_NOTE.containsMatchIn(right)) && !channel -> t = left
                // ...or when the left side is a version: "BBC Sessions - Love Or Confusion" (Jimi Hendrix).
                !keepsDash && VERSION.containsMatchIn(left) && tokens(left).size >= 2 && !VERSION.containsMatchIn(right) && !channel -> {
                    addVersion(left); t = right; rest = rest.drop(1)
                }
                keepsDash -> t = left
                else -> { a = left; t = rest.first(); rest = rest.drop(1) }
            }
            for (r in rest) {
                when {
                    FROM_NOTE.containsMatchIn(r) || NOISE.matches(r.trim()) -> Unit
                    VERSION.containsMatchIn(r) -> addVersion(r)
                    keepsDash -> t = "$t - $r"
                }
            }
        }
        if (noteTail?.groups?.get(1) != null) t += noteTail.value.trimEnd()

        t = stripComposer(t, a)
        return Parsed(artist = a.trim(), title = t.replace(WHITESPACE, " ").trim(), version = versions.joinToString(", "))
    }

    private val WHITESPACE = Regex("\\s+")

    private val TOPIC_SUFFIX = Regex("\\s*-\\s*topic$", RegexOption.IGNORE_CASE)
    private val VEVO_SUFFIX = Regex("vevo$", RegexOption.IGNORE_CASE)
    private val OFFICIAL_SUFFIX = Regex("\\s+official$", RegexOption.IGNORE_CASE)

    private fun cleanArtist(a: String): String =
        a.replace(TOPIC_SUFFIX, "").replace(VEVO_SUFFIX, "").replace(OFFICIAL_SUFFIX, "").trim()

    /** "Chopin: Nocturne…" when Chopin is already in the artist credit. */
    private fun stripComposer(t: String, a: String): String {
        val m = COMPOSER_PREFIX.find(t) ?: return t
        val words = tokens(m.groupValues[1]).filter { it.length > 2 }
        val artistTokens = tokens(a)
        return if (words.isNotEmpty() && words.all { it in artistTokens }) m.groupValues[2] else t
    }

    /** Comparison words: accents folded, brackets and "feat." tails dropped, punctuation out. */
    internal fun tokens(s: String): Set<String> =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(TOKEN_MARKS, "").lowercase()
            .replace(TOKEN_BRACKETS, " ")
            .replace(TOKEN_FEAT, " ")
            .replace(TOKEN_APOSTROPHES, "")
            .replace(TOKEN_PUNCT, " ")
            .split(WHITESPACE).filter { it.isNotEmpty() }.toSet()

    // tokens() runs several times per row; its patterns are compiled once.
    private val TOKEN_MARKS = Regex("\\p{Mn}+")
    private val TOKEN_BRACKETS = Regex("\\([^)]*\\)|\\[[^]]*]")
    private val TOKEN_FEAT = Regex("\\b(feat\\.?|ft\\.?|featuring)\\b.*")
    private val TOKEN_APOSTROPHES = Regex("['’`]")
    private val TOKEN_PUNCT = Regex("[^\\p{L}\\p{N}\\p{S}\\s]")

    internal fun jaccard(a: String, b: String): Float {
        val x = tokens(a)
        val y = tokens(b)
        if (x.isEmpty() || y.isEmpty()) return 0f
        val shared = x.count { it in y }
        return shared.toFloat() / (x.size + y.size - shared)
    }
}
