package com.stash.core.common

import java.text.Normalizer

/**
 * The song a YouTube Music library row really is, read from its video title and channel: what a
 * catalog (Qobuz, Tidal) would list as artist and title. YouTube rows carry whatever the upload had,
 * "Artist - Song (Official Video)", "KEXP | Phantogram - When I'm Small (Live on KEXP)", a channel
 * as the artist ("ArtistVEVO", "neilyoungchannel"), so the lossless lookups searched and scored
 * against noise. Of 6,843 labelled library rows, the raw fields named the right artist and title
 * 78.9% of the time; [parse] gets 91%+ (pinned by YouTubeSongTitleTest).
 *
 * Text only, no network: a row whose identity needs outside knowledge (a label channel, a
 * misspelled artist) is left as close to its fields as the rules allow. Not the library's identity
 * ([TrackCanonical] keys stay as they are); this only shapes lookup queries.
 */
object YouTubeSongTitle {

    /**
     * @property artist the performing artist, as the row names it.
     * @property title the song title without the artist, upload noise or version.
     * @property version the version the row names ("Live on KEXP", "2018 Remix"), or "".
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
            "stereo|sped|slowed|reverb|piano|orchestral|a ?cappella|karaoke|extended|radio|club|dub|rework|bootleg|vip|bonus)\\b",
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

    /**
     * @param title the row's title as YouTube had it.
     * @param artist the row's artist (often the uploading channel).
     */
    fun parse(title: String, artist: String): Parsed {
        var t = title.trim()
        var a = cleanArtist(artist)
        var version = ""
        var hadNoise = false

        // 1. Bracket groups: noise goes, a version is kept apart, anything else is part of the title.
        t = BRACKET.replace(t) { m ->
            val inner = m.groupValues[1].trim()
            when {
                NOISE.matches(inner) || NOISE_PHRASE.containsMatchIn(inner) -> { hadNoise = true; "" }
                VERSION.containsMatchIn(inner) && !CREDIT.containsMatchIn(inner) -> { if (version.isEmpty()) version = inner; "" }
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
                if (VERSION.containsMatchIn(rest) && version.isEmpty()) version = rest.trim()
                hadNoise = true
            }
        }

        // 3. Artist "Title", or "Title" - Artist.
        QUOTED.find(t)?.let { q ->
            val (before, quoted, after) = q.destructured
            if (before.isBlank() && LEADING_DASH.containsMatchIn(after) && after.trim().length > 1) {
                a = LEADING_DASH.replace(after, "").trim()
                t = quoted.trim()
            } else if (before.isNotBlank() && (jaccard(FEAT_TAIL.replace(before, ""), a) >= 0.5f || hadNoise ||
                    CHANNELISH.containsMatchIn(artist) || PERFORMING.containsMatchIn(before))
            ) {
                val lead = PERFORMING.replace(FEAT_TAIL.replace(before, ""), "").trimEnd('.', ' ', '-').trim()
                if (lead.isNotEmpty() && jaccard(lead, a) < 0.5f) a = lead
                t = quoted.trim()
            }
        }

        // 4. "Left - Right": YouTube's "Artist - Title", unless the row says otherwise.
        val segs = t.split(DASH)
        if (segs.size > 1) {
            val left = segs[0]
            val artistTokens = tokens(a)
            val leftIsArtist = jaccard(left, a) >= 0.5f || (artistTokens.isNotEmpty() && tokens(left).containsAll(artistTokens))
            val credited = artist.contains(',') // "Orchestra, Conductor, Composer": a catalog-style credit
            val keepsDash = credited || left.contains(':')
            val right = segs[1]
            var rest = segs.drop(1)
            when {
                leftIsArtist -> { if (left.length >= a.length) a = left; t = rest.first(); rest = rest.drop(1) }
                jaccard(right, a) >= 0.5f -> { t = left; rest = rest.drop(1) } // "Title - Artist"
                VERSION.containsMatchIn(right) && !CHANNELISH.containsMatchIn(artist) -> t = left
                keepsDash -> t = left
                else -> { a = left; t = rest.first(); rest = rest.drop(1) }
            }
            for (r in rest) {
                when {
                    FROM_NOTE.containsMatchIn(r) || NOISE.matches(r.trim()) -> Unit
                    VERSION.containsMatchIn(r) -> if (version.isEmpty()) version = r.trim()
                    keepsDash -> t = "$t - $r"
                }
            }
        }

        t = stripComposer(t, a)
        return Parsed(artist = a.trim(), title = t.replace(WHITESPACE, " ").trim(), version = version)
    }

    private val WHITESPACE = Regex("\\s+")

    private fun cleanArtist(a: String): String =
        a.replace(Regex("\\s*-\\s*topic$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("vevo$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+official$", RegexOption.IGNORE_CASE), "")
            .trim()

    /** "Chopin: Nocturne…" when Chopin is already in the artist credit. */
    private fun stripComposer(t: String, a: String): String {
        val m = COMPOSER_PREFIX.find(t) ?: return t
        val words = tokens(m.groupValues[1]).filter { it.length > 2 }
        val artistTokens = tokens(a)
        return if (words.isNotEmpty() && words.all { it in artistTokens }) m.groupValues[2] else t
    }

    /** Comparison words: accents folded, brackets and "feat." tails dropped, punctuation out. */
    internal fun tokens(s: String): Set<String> =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()
            .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
            .replace(Regex("\\b(feat\\.?|ft\\.?|featuring)\\b.*"), " ")
            .replace(Regex("['’`]"), "")
            .replace(Regex("[^\\p{L}\\p{N}\\p{S}\\s]"), " ")
            .split(WHITESPACE).filter { it.isNotEmpty() }.toSet()

    internal fun jaccard(a: String, b: String): Float {
        val x = tokens(a)
        val y = tokens(b)
        if (x.isEmpty() || y.isEmpty()) return 0f
        val shared = x.count { it in y }
        return shared.toFloat() / (x.size + y.size - shared)
    }
}
