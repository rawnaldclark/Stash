package com.stash.data.download.lossless

import java.net.URI

/**
 * The fetch plan for a single-representation DASH manifest: one init segment,
 * then [mediaUrls] in playback order. Concatenating them gives a playable fMP4.
 */
data class DashPlan(
    val initUrl: String,
    val mediaUrls: List<String>,
    /**
     * How long the segments play, in ms, from the timeline (every `d` times its repeats, over the
     * template's `timescale`); null when a segment states no `d`. What the plan fetches, so a preview
     * manifest says it is one: Tidal's 30 s PREVIEW of "Linger" was 7 x 176128 + 86016 samples at
     * 44100 = 29,907 ms.
     */
    val durationMs: Long? = null,
)

/** A manifest we cannot turn into a [DashPlan]; the download fails rather than guessing. */
class DashManifestException(message: String) : Exception(message)

/**
 * Minimal MPD reader for Tidal's audio manifests (one `SegmentTemplate` with a
 * `SegmentTimeline`, no DRM). Pure JVM — one forward text scan, not an XML stack — because
 * the shape is fixed and this keeps it unit-testable without Android. An add-on's manifest
 * comes through here too, so no step may cost more than the length of the text.
 *
 * Deliberately narrow: anything it does not understand (`r="-1"`, template
 * tokens other than `$Number$`, no timeline) is an error, so a format change
 * upstream surfaces as a failed download instead of a silently truncated file.
 */
object DashManifestParser {

    // Every element is found by its opening name alone, and where it ends by `indexOf`: one forward
    // pass over the text. One regex per element, with `[^>]*` or `.*?` running to the end of the
    // text, scanned the rest of the text again from every opening that had no end: the square of
    // the size, and an add-on's 1 MiB manifest held a download for minutes of CPU that no
    // cancellation reaches (review-882 round 3, R1). Each element ends where its regex ended.
    private val TEMPLATE_OPEN = Regex("<(?:\\w+:)?SegmentTemplate\\b", RegexOption.IGNORE_CASE)
    private val TIMELINE_OPEN = Regex("<(?:\\w+:)?SegmentTimeline\\b", RegexOption.IGNORE_CASE)
    private val TIMELINE_CLOSE = Regex("</(?:\\w+:)?SegmentTimeline>", RegexOption.IGNORE_CASE)
    private val S_OPEN = Regex("<(?:\\w+:)?S\\b", RegexOption.IGNORE_CASE)
    private val BASE_URL_OPEN = Regex("<(?:\\w+:)?BaseURL\\b", RegexOption.IGNORE_CASE)

    // A name is tried only where a run of name characters starts. Tried from inside the run too,
    // a long run with no `=` after it cost far more than the square (64 KiB: two minutes on a
    // desktop). It finds what it found before: a match can only ever start at the run's start.
    private val ATTR = Regex("(?<![\\w:])([\\w:]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')")
    private val NUMBER_TOKEN = Regex("\\\$Number(%0(\\d+)d)?\\$")

    /** Guards against a hostile/garbled manifest asking for millions of fetches. */
    const val MAX_SEGMENTS = 10_000

    /**
     * The largest `d` counted toward [DashPlan.durationMs]: an hour at 768 kHz. Larger is garbage, and
     * bounding it keeps the sum (at most [MAX_SEGMENTS] of them, each repeated at most that often) far
     * from overflowing.
     */
    private const val MAX_SEGMENT_UNITS = 3_600L * 768_000L

    /**
     * @param mpd      Manifest XML text.
     * @param mpdUrl   Where it came from; relative URLs resolve against it (and
     *                 any `BaseURL`). Tidal's are absolute, so this rarely matters.
     * @param maxPlanChars  The most the segment URLs may add up to, in characters; null = no bound
     *   (Stash's own Tidal). Set for an add-on's manifest: its media template is copied once per
     *   segment, and a 1 MiB template over [MAX_SEGMENTS] segments would be gigabytes of strings.
     */
    fun parse(mpd: String, mpdUrl: String, maxPlanChars: Long? = null): DashPlan {
        val templateAttrs = element(mpd, TEMPLATE_OPEN)?.attrs
            ?: throw DashManifestException("no SegmentTemplate")
        val attrs = attributes(templateAttrs)
        val init = attrs["initialization"]?.takeIf { it.isNotBlank() }
            ?: throw DashManifestException("no initialization URL")
        val media = attrs["media"]?.takeIf { it.isNotBlank() }
            ?: throw DashManifestException("no media template")
        if (!NUMBER_TOKEN.containsMatchIn(media)) throw DashManifestException("media template lacks \$Number\$")
        val startNumber = attrs["startNumber"]?.let {
            it.toLongOrNull() ?: throw DashManifestException("bad startNumber '$it'")
        } ?: 1L
        // Units per second of every `d` (DASH: 1 when absent). A bad one leaves the length unknown, not the plan.
        val timescale = attrs["timescale"]?.toLongOrNull() ?: 1L

        // The text after the first SegmentTimeline's `>`, up to the first closing tag after it.
        val timelineOpen = element(mpd, TIMELINE_OPEN) ?: throw DashManifestException("no SegmentTimeline")
        val timelineClose = TIMELINE_CLOSE.find(mpd, timelineOpen.end + 1)
            ?: throw DashManifestException("no SegmentTimeline")
        val timeline = mpd.substring(timelineOpen.end + 1, timelineClose.range.first)
        var count = 0L
        // The timeline's length in timescale units; null once a segment states no usable `d`.
        var units: Long? = 0L
        var at = 0
        while (true) {
            val s = element(timeline, S_OPEN, at) ?: break
            at = s.end + 1
            // `<S d="1"/>`: the `/` before the `>` is not an attribute's.
            val sAttrs = attributes(s.attrs.removeSuffix("/"))
            val r = sAttrs["r"]?.let {
                it.toLongOrNull() ?: throw DashManifestException("bad r '$it'")
            } ?: 0L
            // r=-1 means "repeat until the period ends" — needs durations we don't model.
            if (r < 0) throw DashManifestException("open-ended repeat (r=$r) unsupported")
            count += 1 + r
            if (count > MAX_SEGMENTS) throw DashManifestException("too many segments")
            val d = sAttrs["d"]?.toLongOrNull()?.takeIf { it in 1..MAX_SEGMENT_UNITS }
            units = if (d == null) null else units?.plus(d * (1 + r))
        }
        if (count == 0L) throw DashManifestException("empty SegmentTimeline")
        if (maxPlanChars != null && media.length.toLong() * count > maxPlanChars) {
            throw DashManifestException("segment URLs too long")
        }

        val base = baseFor(mpd, mpdUrl)
        val mediaUrls = (0 until count).map { i -> resolve(base, expand(media, startNumber + i)) }
        val durationMs = units?.takeIf { timescale > 0 }?.let { it * 1_000L / timescale }
        return DashPlan(initUrl = resolve(base, init), mediaUrls = mediaUrls, durationMs = durationMs)
    }

    /** An element's attribute text (between its name and its `>`) and the index of that `>`. */
    private class Element(val attrs: String, val end: Int)

    /**
     * The first element that [open] finds at or after [from], or null. When the first opening has
     * no `>` after it, no later one has either: there is nothing more to find, as with the regex.
     */
    private fun element(text: String, open: Regex, from: Int = 0): Element? {
        val name = open.find(text, from) ?: return null
        val end = text.indexOf('>', name.range.last + 1)
        if (end < 0) return null
        return Element(text.substring(name.range.last + 1, end), end)
    }

    private fun attributes(raw: String): Map<String, String> =
        ATTR.findAll(raw).associate { m ->
            val value = m.groups[3]?.value ?: m.groups[4]?.value ?: ""
            m.groupValues[1].substringAfter(':') to decodeEntities(value)
        }

    private fun expand(template: String, number: Long): String {
        val out = NUMBER_TOKEN.replace(template) { m ->
            val width = m.groups[2]?.value?.toIntOrNull()
            if (width != null) number.toString().padStart(width, '0') else number.toString()
        }
        // Any other identifier ($RepresentationID$, $Time$...) means a manifest shape we don't handle.
        if (Regex("\\$\\w+\\$").containsMatchIn(out)) throw DashManifestException("unsupported template token")
        return out.replace("$$", "$")
    }

    /** Lazy: Tidal's URLs are absolute, so a base that java.net.URI rejects must not fail them. */
    private fun baseFor(mpd: String, mpdUrl: String): Lazy<URI> = lazy {
        try {
            val docUri = URI(mpdUrl)
            // The text after the first BaseURL's `>`, up to the next `<`; none when there is no `<` after it.
            val baseText = element(mpd, BASE_URL_OPEN)
                ?.let { open -> mpd.indexOf('<', open.end + 1).takeIf { it >= 0 }?.let { mpd.substring(open.end + 1, it) } }
                ?.trim()?.let(::decodeEntities)
            if (baseText.isNullOrEmpty()) docUri else docUri.resolve(baseText)
        } catch (e: IllegalArgumentException) {
            throw DashManifestException("unresolvable base URL")
        } catch (e: java.net.URISyntaxException) {
            throw DashManifestException("unresolvable base URL")
        }
    }

    // Absolute URLs pass through verbatim: CDN signatures can hold characters
    // java.net.URI is strict about, and re-encoding would break the signature.
    private fun resolve(base: Lazy<URI>, ref: String): String {
        val r = ref.trim()
        if (r.startsWith("https://", ignoreCase = true) || r.startsWith("http://", ignoreCase = true)) return r
        return try {
            base.value.resolve(r).toString()
        } catch (e: IllegalArgumentException) {
            throw DashManifestException("unresolvable segment URL")
        }
    }

    /** XML attribute text is entity-encoded: Tidal's signed URLs arrive with `&amp;`. */
    internal fun decodeEntities(s: String): String =
        s.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#38;", "&")
            .replace("&amp;", "&") // last, so "&amp;lt;" stays "&lt;"
}
