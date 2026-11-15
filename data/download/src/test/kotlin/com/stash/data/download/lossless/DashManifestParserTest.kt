package com.stash.data.download.lossless

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class DashManifestParserTest {

    private val mpdUrl = "https://relay.example/dash/td/1.mpd?exp=1&sig=x"
    private val n = "\$Number\$"

    private fun mpd(template: String, timeline: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static">
          <Period><AdaptationSet mimeType="audio/mp4"><Representation id="FLAC" codecs="flac">
            $template
              <SegmentTimeline>$timeline</SegmentTimeline>
            </SegmentTemplate>
          </Representation></AdaptationSet></Period>
        </MPD>
    """.trimIndent()

    @Test fun `timeline with repeats gives every segment from startNumber`() {
        val plan = DashManifestParser.parse(
            mpd(
                """<SegmentTemplate timescale="44100" initialization="https://cdn/t/0.mp4?a=1&amp;b=2" media="https://cdn/t/$n.mp4?a=1&amp;b=2" startNumber="1">""",
                """<S d="176128" r="51"/><S d="118808"/>""",
            ),
            mpdUrl,
        )
        assertThat(plan.mediaUrls).hasSize(53)
        assertThat(plan.initUrl).isEqualTo("https://cdn/t/0.mp4?a=1&b=2")
        assertThat(plan.mediaUrls.first()).isEqualTo("https://cdn/t/1.mp4?a=1&b=2")
        assertThat(plan.mediaUrls.last()).isEqualTo("https://cdn/t/53.mp4?a=1&b=2")
    }

    /** Tidal's 30 s PREVIEW of "Linger" (td:575545), as the relay served it on 2026-11-13: it says how long it is. */
    @Test fun `a manifest says how long it plays - Tidal's preview of Linger is 29,907 ms`() {
        val plan = DashManifestParser.parse(
            mpd(
                """<SegmentTemplate timescale="44100" initialization="https://cdn/t/0.mp4" media="https://cdn/t/$n.mp4" startNumber="1">""",
                """<S d="176128" r="6"/><S d="86016"/>""",
            ),
            mpdUrl,
        )
        assertThat(plan.mediaUrls).hasSize(8)
        assertThat(plan.durationMs).isEqualTo(29_907L)
    }

    @Test fun `a whole song's manifest says the whole song`() {
        val plan = DashManifestParser.parse(
            mpd(
                """<SegmentTemplate timescale="44100" initialization="https://cdn/t/0.mp4" media="https://cdn/t/$n.mp4" startNumber="1">""",
                """<S d="176128" r="51"/><S d="118808"/>""",
            ),
            mpdUrl,
        )
        // 52 x 176128 + 118808 samples at 44.1 kHz.
        assertThat(plan.durationMs).isEqualTo(210_373L)
    }

    @Test fun `a segment with no length leaves the manifest's length unknown, and the plan as it was`() {
        val plan = DashManifestParser.parse(
            mpd(
                """<SegmentTemplate timescale="44100" initialization="https://cdn/i.mp4" media="https://cdn/$n.mp4">""",
                """<S d="176128"/><S r="1"/>""",
            ),
            mpdUrl,
        )
        assertThat(plan.mediaUrls).hasSize(3)
        assertThat(plan.durationMs).isNull()
    }

    @Test fun `multiple S with t attrs and no startNumber default to 1`() {
        val plan = DashManifestParser.parse(
            mpd(
                """<SegmentTemplate initialization="https://cdn/i.mp4" media="https://cdn/$n.mp4">""",
                """<S t="0" d="10"/><S t="10" d="10" r="2"/><S d="5"/>""",
            ),
            mpdUrl,
        )
        assertThat(plan.mediaUrls).containsExactly(
            "https://cdn/1.mp4", "https://cdn/2.mp4", "https://cdn/3.mp4",
            "https://cdn/4.mp4", "https://cdn/5.mp4",
        ).inOrder()
    }

    @Test fun `startNumber offset, padded Number and relative URLs`() {
        val plan = DashManifestParser.parse(
            mpd(
                """<SegmentTemplate initialization="init.mp4" media="seg-${'$'}Number%03d${'$'}.mp4" startNumber="7">""",
                """<S d="1" r="1"/>""",
            ),
            mpdUrl,
        )
        assertThat(plan.initUrl).isEqualTo("https://relay.example/dash/td/init.mp4")
        assertThat(plan.mediaUrls).containsExactly(
            "https://relay.example/dash/td/seg-007.mp4",
            "https://relay.example/dash/td/seg-008.mp4",
        ).inOrder()
    }

    @Test fun `missing initialization is an error`() {
        assertThrows(DashManifestException::class.java) {
            DashManifestParser.parse(
                mpd("""<SegmentTemplate media="https://cdn/$n.mp4">""", """<S d="1"/>"""),
                mpdUrl,
            )
        }
    }

    @Test fun `open-ended repeat and empty timeline are errors`() {
        assertThrows(DashManifestException::class.java) {
            DashManifestParser.parse(
                mpd("""<SegmentTemplate initialization="https://cdn/i" media="https://cdn/$n">""", """<S d="1" r="-1"/>"""),
                mpdUrl,
            )
        }
        assertThrows(DashManifestException::class.java) {
            DashManifestParser.parse(
                mpd("""<SegmentTemplate initialization="https://cdn/i" media="https://cdn/$n">""", ""),
                mpdUrl,
            )
        }
    }

    @Test fun `no SegmentTemplate is an error`() {
        assertThrows(DashManifestException::class.java) {
            DashManifestParser.parse("<MPD/>", mpdUrl)
        }
    }

    @Test fun `entity decoding leaves a literal amp-escape intact`() {
        assertThat(DashManifestParser.decodeEntities("a&amp;b&amp;lt;c")).isEqualTo("a&b&lt;c")
    }

    // ---- review-882 round 3, R1: one pass over the text, whatever an add-on sends --------------
    // Each manifest below is the size an add-on's may be (1 MiB). Read in one pass it takes
    // milliseconds; with the regexes it had, minutes (the first two) to hours (the attributes).
    // The timeout is far from both, so a slow runner cannot fail it.

    private val addonCap = DashFlacDownloader.MAX_ADDON_MANIFEST_BYTES.toInt()
    private fun fill(unit: String, prefix: String = "", suffix: String = "") =
        prefix + unit.repeat((addonCap - prefix.length - suffix.length) / unit.length) + suffix
    private val okTemplate = """<SegmentTemplate initialization="https://cdn/i" media="https://cdn/$n">"""

    @Test(timeout = 20_000) fun `many SegmentTimeline openings with no closing tag are read in one pass`() {
        val e = assertThrows(DashManifestException::class.java) {
            DashManifestParser.parse(fill("<SegmentTimeline>", prefix = okTemplate), mpdUrl)
        }
        assertThat(e).hasMessageThat().isEqualTo("no SegmentTimeline")
    }

    @Test(timeout = 20_000) fun `many SegmentTemplate openings with no closing bracket are read in one pass`() {
        val e = assertThrows(DashManifestException::class.java) {
            DashManifestParser.parse(fill("<SegmentTemplate "), mpdUrl)
        }
        assertThat(e).hasMessageThat().isEqualTo("no SegmentTemplate")
    }

    @Test(timeout = 20_000) fun `a template whose attributes are one long name is read in one pass`() {
        val e = assertThrows(DashManifestException::class.java) {
            DashManifestParser.parse(fill("a", prefix = "<SegmentTemplate ", suffix = ">"), mpdUrl)
        }
        assertThat(e).hasMessageThat().isEqualTo("no initialization URL")
    }

    @Test(timeout = 20_000) fun `a timeline of S openings with no closing bracket is read in one pass`() {
        val e = assertThrows(DashManifestException::class.java) {
            DashManifestParser.parse(
                fill("<S ", prefix = "$okTemplate<SegmentTimeline>", suffix = "</SegmentTimeline>"), mpdUrl,
            )
        }
        assertThat(e).hasMessageThat().isEqualTo("empty SegmentTimeline")
    }

    @Test(timeout = 20_000) fun `many BaseURL openings before one bracket are read in one pass`() {
        // Relative URLs, so the base is read. There is no `<` after the one `>`: no BaseURL, as before.
        val head = """<SegmentTemplate initialization="i.mp4" media="$n.mp4"><SegmentTimeline><S d="1"/></SegmentTimeline>"""
        val plan = DashManifestParser.parse(fill("<BaseURL ", prefix = head, suffix = ">x"), mpdUrl)
        assertThat(plan.initUrl).isEqualTo("https://relay.example/dash/td/i.mp4")
        assertThat(plan.mediaUrls).containsExactly("https://relay.example/dash/td/1.mp4")
    }

    @Test fun `an add-on's segment URLs are bounded before they are built`() {
        // 100 kB of template over 10,000 segments would be 2 GB of strings.
        val longMedia = "https://cdn/" + "x".repeat(100_000) + n
        val manifest = mpd("""<SegmentTemplate initialization="https://cdn/i" media="$longMedia">""", """<S d="1" r="9999"/>""")
        val e = assertThrows(DashManifestException::class.java) {
            DashManifestParser.parse(manifest, mpdUrl, maxPlanChars = 8L * 1024 * 1024)
        }
        assertThat(e).hasMessageThat().isEqualTo("segment URLs too long")
        // Under the bound it is read as before; with none (Stash's own Tidal) nothing is bounded.
        val small = mpd(okTemplate, """<S d="1" r="2"/>""")
        assertThat(DashManifestParser.parse(small, mpdUrl, maxPlanChars = 8L * 1024 * 1024).mediaUrls).hasSize(3)
        assertThat(DashManifestParser.parse(small, mpdUrl).mediaUrls).hasSize(3)
    }

    /** Shapes the regexes read, each read the same way now: [OldParser] is the parser as it was. */
    @Test fun `every shape is read exactly as the regexes read it`() {
        val t = "initialization=\"https://cdn/i.mp4?a=1&amp;b=2\" media=\"https://cdn/$n.mp4\""
        val shapes = listOf(
            mpd("<SegmentTemplate $t startNumber=\"3\">", "<S d=\"1\" r=\"2\"/><S d=\"2\" />"),
            mpd("<SegmentTemplate $t>", "<S d=\"1\"></S><S d=\"1\" r='1'></S>"),
            "<mpd:MPD><mpd:SegmentTemplate $t><mpd:SegmentTimeline><mpd:S d=\"1\" r=\"4\"/></mpd:SegmentTimeline></mpd:SegmentTemplate></mpd:MPD>",
            "<MPD><segmenttemplate $t/><SEGMENTTIMELINE a=\"1\"><s d=\"1\"/><S d=\"1\"//></segmenttimeline></MPD>",
            "<SegmentTemplate initialization='i.mp4' media='s/$n.m4s'/><BaseURL x=\"1\"> https://other/base/ </BaseURL><SegmentTimeline><S/></SegmentTimeline>",
            "<SegmentTemplate initialization='i.mp4' media='s/$n.m4s'/><SegmentTimeline><S/></SegmentTimeline><BaseURL>../up/</BaseURL>",
            "<SegmentTemplate :initialization=\"https://cdn/i\" x:media=\"https://cdn/$n\"><SegmentTimeline><S d=\"1\"/><SegmentTimeline><S d=\"1\"/></SegmentTimeline></SegmentTimeline>",
            "<SegmentTemplate $t><SegmentTimeline/><S d=\"1\"/></SegmentTimeline>",
            "<SegmentTemplateX $t><SegmentTemplate $t><SegmentTimelineX><SegmentTimeline><Sx d=\"9\"/><S d=\"1\"/></SegmentTimeline>",
            "<SegmentTemplate media=\"https://cdn/$n\"><SegmentTimeline><S d=\"1\"/></SegmentTimeline>",
            "<SegmentTemplate $t><SegmentTimeline><S d=\"1\" r=\"-1\"/></SegmentTimeline>",
            "<SegmentTemplate $t><SegmentTimeline><S d=\"1\"/>",
            "<SegmentTemplate $t><SegmentTimeline><S d=\"1\"",
            "<SegmentTemplate $t",
            "",
        )
        for (shape in shapes) {
            val before = runCatching { OldParser.parse(shape, mpdUrl) }
            val now = runCatching { DashManifestParser.parse(shape, mpdUrl) }
            // What is fetched is the same; the length (durationMs) is new, and not something the regexes read.
            assertThat(now.getOrNull()?.copy(durationMs = null)).isEqualTo(before.getOrNull())
            assertThat(now.exceptionOrNull()?.message).isEqualTo(before.exceptionOrNull()?.message)
        }
    }

    /** The parser before R1, the regexes as they were, kept here only as the oracle above. */
    private object OldParser {
        private val TEMPLATE = Regex("<(?:\\w+:)?SegmentTemplate\\b([^>]*)>", RegexOption.IGNORE_CASE)
        private val TIMELINE = Regex(
            "<(?:\\w+:)?SegmentTimeline\\b[^>]*>(.*?)</(?:\\w+:)?SegmentTimeline>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val S_ELEMENT = Regex("<(?:\\w+:)?S\\b([^>]*?)/?>", RegexOption.IGNORE_CASE)
        private val ATTR = Regex("([\\w:]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')")
        private val BASE_URL = Regex("<(?:\\w+:)?BaseURL\\b[^>]*>([^<]*)<", RegexOption.IGNORE_CASE)
        private val NUMBER_TOKEN = Regex("\\\$Number(%0(\\d+)d)?\\$")

        fun parse(mpd: String, mpdUrl: String): DashPlan {
            val attrs = attributes(TEMPLATE.find(mpd)?.groupValues?.get(1) ?: throw DashManifestException("no SegmentTemplate"))
            val init = attrs["initialization"]?.takeIf { it.isNotBlank() } ?: throw DashManifestException("no initialization URL")
            val media = attrs["media"]?.takeIf { it.isNotBlank() } ?: throw DashManifestException("no media template")
            if (!NUMBER_TOKEN.containsMatchIn(media)) throw DashManifestException("media template lacks \$Number\$")
            val startNumber = attrs["startNumber"]?.let { it.toLongOrNull() ?: throw DashManifestException("bad startNumber '$it'") } ?: 1L
            val timeline = TIMELINE.find(mpd)?.groupValues?.get(1) ?: throw DashManifestException("no SegmentTimeline")
            var count = 0L
            for (s in S_ELEMENT.findAll(timeline)) {
                val r = attributes(s.groupValues[1])["r"]?.let { it.toLongOrNull() ?: throw DashManifestException("bad r '$it'") } ?: 0L
                if (r < 0) throw DashManifestException("open-ended repeat (r=$r) unsupported")
                count += 1 + r
            }
            if (count == 0L) throw DashManifestException("empty SegmentTimeline")
            val base by lazy {
                val baseText = BASE_URL.find(mpd)?.groupValues?.get(1)?.trim()?.let(DashManifestParser::decodeEntities)
                if (baseText.isNullOrEmpty()) java.net.URI(mpdUrl) else java.net.URI(mpdUrl).resolve(baseText)
            }
            fun resolve(ref: String) = ref.trim().let { r -> if (r.startsWith("https://", true) || r.startsWith("http://", true)) r else base.resolve(r).toString() }
            fun expand(number: Long) = NUMBER_TOKEN.replace(media) { m ->
                val width = m.groups[2]?.value?.toIntOrNull()
                if (width != null) number.toString().padStart(width, '0') else number.toString()
            }.replace("$$", "$")
            return DashPlan(resolve(init), (0 until count).map { resolve(expand(startNumber + it)) })
        }

        private fun attributes(raw: String): Map<String, String> = ATTR.findAll(raw).associate { m ->
            m.groupValues[1].substringAfter(':') to DashManifestParser.decodeEntities(m.groups[3]?.value ?: m.groups[4]?.value ?: "")
        }
    }
}
