package com.stash.core.data.lastfm

/**
 * What the art backfill sends to Last.fm `track.getInfo`, and what it doesn't
 * send at all. Pure, so the rules are tested.
 *
 * The proxy log showed most lookups were for rows Last.fm can never answer:
 * local recordings ("Unknown Artist – AUD-20240101-WA0001", WhatsApp voice
 * notes) and YouTube titles carrying "(Official Video)", which Last.fm doesn't
 * list under that name. The first kind is skipped outright; the second is
 * looked up under the plain title, which is how Last.fm lists the song.
 */
object LastFmLookupRules {

    /** Artist values a tagger or the media store writes when there is none. Lowercase. */
    private val PLACEHOLDER_ARTISTS = setOf(
        "unknown artist",
        "<unknown>",
        "unknown",
    )

    /**
     * Camera/messenger/recorder file names: WhatsApp `AUD-20240101-WA0001`,
     * `PTT-…` (push-to-talk voice notes), `VID-…`, and recorder names like
     * `REC_20240101_101500` / `Recording_12`. Anchored at the start, followed
     * by digits, so a song called "Recording Angel" or "Audition" is not one.
     */
    private val FILE_NAME_TITLE = Regex(
        """^(?:aud|ptt|vid|img|rec|recording|voice)[-_ ]?\d""",
        RegexOption.IGNORE_CASE,
    )

    /** A WhatsApp media suffix anywhere: `-WA0001`. */
    private val WHATSAPP_SUFFIX = Regex("""-WA\d{3,}""", RegexOption.IGNORE_CASE)

    /** A title that is a file name: ends in an audio/video extension. */
    private val FILE_EXTENSION = Regex(
        """\.(?:mp3|m4a|aac|ogg|oga|opus|wav|flac|amr|3gp|3ga|mp4|m4b|wma|webm|mka|aiff?)$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * A timestamp, not a title: only digits and separators, with at least eight
     * digits ("20240101_101500"). Songs called "1979" or "2112" are four.
     */
    private val NUMERIC_TITLE = Regex("""^(?=(?:\D*\d){8})[\d\s._-]+$""")

    /**
     * Presentation tags YouTube uploads carry that Last.fm's track names don't:
     * "(Official Audio)", "[Official Music Video]", "(Lyric Video)",
     * "(Official Visualizer)", "(Official HD Video)", "(Audio)", "(Lyrics)".
     * Only these; "(Remix)", "(Live)", "(Acoustic)" and the like name another
     * version, so they stay.
     */
    private val PRESENTATION_TAG = Regex(
        """\s*[(\[]\s*(?:official\s+)?(?:hd\s+|4k\s+)?(?:music\s+video|lyrics?\s+video|lyric\s+visuali[sz]er|""" +
            """visuali[sz]er|video|audio|lyrics?)(?:\s+(?:hd|4k))?\s*[)\]]""",
        RegexOption.IGNORE_CASE,
    )

    /** "Artist - Topic", the name of YouTube's auto-generated artist channels. */
    private val TOPIC_SUFFIX = Regex("""\s+-\s+topic$""", RegexOption.IGNORE_CASE)

    /**
     * True when a lookup for this row can't succeed, so it isn't sent: a
     * placeholder artist, or a title that is a file name or a voice note.
     */
    fun isUnlookable(artist: String, title: String): Boolean {
        val a = artist.trim()
        val t = title.trim()
        if (a.isEmpty() || t.isEmpty()) return true
        if (a.lowercase() in PLACEHOLDER_ARTISTS) return true
        return FILE_NAME_TITLE.containsMatchIn(t) ||
            WHATSAPP_SUFFIX.containsMatchIn(t) ||
            FILE_EXTENSION.containsMatchIn(t) ||
            NUMERIC_TITLE.matches(t)
    }

    /** [title] without YouTube presentation tags; the original when that would leave nothing. */
    fun cleanTitle(title: String): String {
        val cleaned = PRESENTATION_TAG.replace(title, "").replace(Regex("""\s{2,}"""), " ").trim()
        return cleaned.ifEmpty { title.trim() }
    }

    /** [artist] without YouTube's " - Topic" channel suffix. */
    fun cleanArtist(artist: String): String {
        val cleaned = TOPIC_SUFFIX.replace(artist.trim(), "")
        return cleaned.ifEmpty { artist.trim() }
    }
}
