package com.stash.core.data.update

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.stash.core.data.update.ReleaseNotes.Kind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * [ReleaseNotes.parse] on the real notes of v0.9.113 to v0.9.122, as GitHub serves them (`test/resources/release-notes`,
 * from `gh api repos/rawnaldclark/Stash/releases`): the update sheet shows the user's part of a release and never the
 * developer's (the PR list, @handles, links, "Full Changelog", picture leftovers, a heading twice).
 */
class ReleaseNotesTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun body(tag: String): String {
        val text = javaClass.getResource("/release-notes/$tag.json")!!.readText()
        return json.parseToJsonElement(text).jsonObject["body"]!!.jsonPrimitive.content
    }

    private fun notes(tag: String) = ReleaseNotes.parse(body(tag))

    /** Every string the sheet can show for [n]. */
    private fun shown(n: ReleaseNotes): List<String> = buildList {
        add(n.headline)
        n.highlights.forEach { addAll(listOfNotNull(it.title, it.body)); addAll(it.details) }
        n.sections.forEach { s ->
            addAll(listOfNotNull(s.heading))
            s.notes.forEach { addAll(listOfNotNull(it.title, it.body)); addAll(it.details) }
        }
    }

    // ── Nothing of GitHub's part, in any real release ──────────────────────

    @Test
    fun `no real release shows a link, a handle, the PR list, the changelog or a picture's leftovers`() {
        for (tag in TAGS) {
            val n = notes(tag)
            assertWithMessage("$tag has notes").that(n.sections).isNotEmpty()
            assertWithMessage("$tag has highlights").that(n.highlights).isNotEmpty()
            for (line in shown(n)) {
                val where = "$tag: \"$line\""
                assertWithMessage(where).that(line).doesNotContain("http")
                assertWithMessage(where).that(line).doesNotContain("github.com")
                assertWithMessage(where).that(line).doesNotContainMatch("""(^|[^\w.])@\w""")
                assertWithMessage(where).that(line).doesNotContain("What's Changed")
                assertWithMessage(where).that(line).doesNotContain("Full Changelog")
                assertWithMessage(where).that(line).doesNotContain("Stash.apk")
                assertWithMessage(where).that(line).doesNotContain("Requires:")
                assertWithMessage(where).that(line).doesNotContain("**")
                assertWithMessage(where).that(line).doesNotContain("](")
                assertWithMessage(where).that(line).doesNotContain("<")
                assertWithMessage(where).that(line).doesNotContainMatch("""\(#\d+\)""")
                assertWithMessage(where).that(line).doesNotContainMatch("""(^|\s)!""")
                assertWithMessage(where).that(line).doesNotContainMatch("""^\s|\s$|\s{2}""")
            }
            val headings = n.sections.mapNotNull { it.heading }
            assertWithMessage("$tag headings").that(headings).containsNoDuplicates()
            assertWithMessage("$tag headings").that(headings.map { it.lowercase() }).containsNoneOf("install", "what's changed", "new contributors")
            assertWithMessage("$tag: a highlight with neither title nor text").that(n.highlights.none { it.title == null && it.body.isEmpty() }).isTrue()
        }
    }

    @Test
    fun `the notes the app stored before 0_9_123 (plain text) still read`() {
        val n = ReleaseNotes.parse("What's new\n• Paste links from Spotify\n\n• Sync tab")
        assertThat(n.highlights.map { it.body }).containsExactly("Paste links from Spotify", "Sync tab").inOrder()
    }

    // ── v0.9.122: the shape every release is written in now ────────────────

    @Test
    fun `v0_9_122 - the first line, three highlights with their first sentence, and the rest counted`() {
        val n = notes("v0.9.122")
        assertThat(n.headline).isEqualTo(
            "Paste links from Spotify, YT, or Soundcloud. Sync & play your SoundCloud library. Sync tab OVERHAUL. " +
                "Reorganize your playlists. Update Stash from the app itself. + many more improvements.",
        )
        assertThat(n.highlights.map { it.title }).containsExactly("The Sync tab, rebuilt", "SoundCloud", "Your record, in the app").inOrder()
        assertThat(n.highlights[0].body).isEqualTo(
            "A fresh install asks once how your music should arrive, with Stream everything picked, and then one sync puts " +
                "your whole Spotify and YouTube Music on Home and in Library.",
        )
        assertThat(n.highlights[1].body).isEqualTo("Connect your SoundCloud account in Settings → Accounts, or just give Stash a public profile.")
        assertThat(n.moreNew).isEqualTo(4)
        assertThat(n.improvements).isEqualTo(4)
        assertThat(n.fixes).isEqualTo(10)
        assertThat(n.hasMore).isTrue()
    }

    @Test
    fun `v0_9_122 - the full notes are the three user sections, whole, and What's new only once`() {
        val n = notes("v0.9.122")
        assertThat(n.sections.map { it.heading }).containsExactly("What's new", "UX polish", "Bug fixes").inOrder()
        assertThat(n.sections.map { it.kind }).containsExactly(Kind.NEW, Kind.POLISH, Kind.FIXES).inOrder()
        assertThat(n.sections.map { it.notes.size }).containsExactly(7, 4, 10).inOrder()
        assertThat(shown(n).count { it.equals("What's new", ignoreCase = true) }).isEqualTo(1)
        // A note keeps its whole text, the bold words inside it plain; the picture under it is gone.
        val sync = n.sections[0].notes[0]
        assertThat(sync.body).endsWith("History, blocked songs, undo and the log live behind one History row.")
        assertThat(sync.body).contains("Sync now and Play something new are the big buttons.")
        // "Thanks to @ParaliyzedEvo." goes; the rest of the line stays.
        assertThat(n.sections[1].notes[3].body).isEqualTo("More of Settings and the Sync tab can now be translated on Crowdin.")
        assertThat(n.sections[2].notes[0].title).isEqualTo("Censored versions")
        assertThat(n.sections[2].notes[8].body).isEqualTo(
            "FLAC played as loud static on some Samsung phones (Galaxy Note 9). Their decoders are now passed over whatever their case.",
        )
    }

    // ── The releases before it ─────────────────────────────────────────────

    @Test
    fun `v0_9_121 - credits to handles go, a sentence about them doesn't`() {
        val n = notes("v0.9.121")
        assertThat(n.headline).isEqualTo("Full art: Now Playing's art fills the screen, and the album's pages turn behind the controls as the song plays.")
        assertThat(n.highlights.map { it.title }).containsExactly("Full art", "Make full art yours", "The title, your way").inOrder()
        assertThat(n.highlights[0].body).isEqualTo("Now Playing's cover now fills the top of the screen, fading into the controls below.")
        assertThat(listOf(n.moreNew, n.improvements, n.fixes)).containsExactly(2, 3, 3).inOrder()
        val language = n.sections[0].notes.single { it.title == "Stash in your language" }
        assertThat(language.body).endsWith("the rest of the app will follow.")
        assertThat(n.sections[2].notes[1].body).endsWith("It now shows a Retry button that loads the page.")
    }

    @Test
    fun `v0_9_120 - Known limitations are shown in full but not counted`() {
        val n = notes("v0.9.120")
        assertThat(n.sections.map { it.heading }).containsExactly("What's new", "UX polish", "Known limitations").inOrder()
        assertThat(n.sections.last().kind).isEqualTo(Kind.INFO)
        assertThat(n.highlights.map { it.title }).containsExactly("Flip the cover", "Look up close", "Cycle album art").inOrder()
        assertThat(listOf(n.moreNew, n.improvements, n.fixes)).containsExactly(2, 3, 0).inOrder()
    }

    @Test
    fun `v0_9_119 - emoji come off the headings`() {
        val n = notes("v0.9.119")
        assertThat(n.sections.map { it.heading }).containsExactly("What's new", "UX polish", "Fixes").inOrder()
        assertThat(listOf(n.moreNew, n.improvements, n.fixes)).containsExactly(6, 10, 2).inOrder()
        assertThat(n.highlights[0].title).isEqualTo("Downloads are much faster")
        // A bold start that runs on into its sentence is emphasis, not a title.
        val lyrics = n.sections[1].notes.single { it.body.startsWith("Fetch lyrics") }
        assertThat(lyrics.title).isNull()
        assertThat(lyrics.body).startsWith("Fetch lyrics in Settings → Library & Storage → Library Health now tries")
    }

    @Test
    fun `v0_9_118 - notes that start with a heading have no headline, and points under a paragraph stay with it`() {
        val n = notes("v0.9.118")
        assertThat(n.headline).isEmpty()
        assertThat(n.sections.first().heading).isEqualTo("Your music, in the cloud")
        val cloud = n.sections.first().notes.first()
        assertThat(cloud.details).hasSize(4)
        assertThat(cloud.details.first()).isEqualTo("Dropbox and OneDrive: pick your music folders and Stash keeps them up to date.")
        assertThat(n.sections.first().notes[1].title).isEqualTo("Save to Google Drive")
        val lyrics = n.sections.single { it.heading == "Lyrics" }.notes
        assertThat(lyrics[0].body).isEqualTo("Long lines now wrap onto the next line instead of shrinking to fit.")
        assertThat(lyrics[1].body).isEqualTo("More songs get word-by-word lyrics, from a new source.")
        // "**Now Playing:** tap the quality line" → "Now Playing" over "Tap the quality line".
        val nowPlaying = n.sections.single { it.kind == Kind.FIXES }.notes.single { it.title == "Now Playing" }
        assertThat(nowPlaying.body).startsWith("Tap the quality line")
    }

    @Test
    fun `v0_9_117 - "#1 song" is words, not a PR number`() {
        val n = notes("v0.9.117")
        assertThat(n.highlights.first().title).isEqualTo("Recap")
        assertThat(n.highlights.first().body).contains("your #1 song")
    }

    @Test
    fun `v0_9_116 - a one-line release`() {
        val n = notes("v0.9.116")
        assertThat(n.headline).isEqualTo("Tidal fix.")
        assertThat(n.highlights).containsExactly(ReleaseNotes.Note(null, "Stash now asks Tidal far less often, which keeps Tidal working more reliably for everyone."))
        assertThat(n.hasMore).isFalse()
    }

    @Test
    fun `v0_9_114 - a fix-only release puts its fix on the card and keeps Good to know for the full notes`() {
        val n = notes("v0.9.114")
        assertThat(n.headline).isEqualTo("A quick fix for v0.9.113.")
        assertThat(n.highlights.map { it.body }).containsExactly("On some Samsung phones, CD-quality FLAC songs could play as loud noise.")
        assertThat(listOf(n.moreNew, n.improvements, n.fixes)).containsExactly(0, 0, 0).inOrder()
        assertThat(n.sections.map { it.kind }).containsExactly(Kind.FIXES, Kind.INFO).inOrder()
        assertThat(n.hasMore).isTrue()
    }

    // ── The hidden block ───────────────────────────────────────────────────

    @Test
    fun `the hidden block gives the app's exact words, and the notes still count the rest`() {
        val block = """
            <!-- stash-app
            Paste links from Spotify, YouTube or SoundCloud, and update Stash from the app.
            - The Sync tab, rebuilt | One sync puts your Spotify and YouTube Music on Home.
            - SoundCloud | Sync your likes and playlists, and paste any SoundCloud link.
            - Your record, in the app | Your listening history, built from this phone's plays.
            -->
        """.trimIndent()
        val n = ReleaseNotes.parse(block + "\n" + body("v0.9.122"))
        assertThat(n.headline).isEqualTo("Paste links from Spotify, YouTube or SoundCloud, and update Stash from the app.")
        assertThat(n.highlights).containsExactly(
            ReleaseNotes.Note("The Sync tab, rebuilt", "One sync puts your Spotify and YouTube Music on Home."),
            ReleaseNotes.Note("SoundCloud", "Sync your likes and playlists, and paste any SoundCloud link."),
            ReleaseNotes.Note("Your record, in the app", "Your listening history, built from this phone's plays."),
        ).inOrder()
        assertThat(listOf(n.moreNew, n.improvements, n.fixes)).containsExactly(4, 4, 10).inOrder()
        assertThat(n.sections.map { it.heading }).containsExactly("What's new", "UX polish", "Bug fixes").inOrder()
        assertThat(shown(n).none { it.contains("stash-app") || it.contains("-->") }).isTrue()
    }

    @Test
    fun `a block with only a headline keeps the parsed highlights, and one anywhere in the body counts`() {
        val n = ReleaseNotes.parse(body("v0.9.121") + "\n<!-- stash-app\nFull art fills Now Playing.\n-->\n")
        assertThat(n.headline).isEqualTo("Full art fills Now Playing.")
        assertThat(n.highlights.map { it.title }).containsExactly("Full art", "Make full art yours", "The title, your way").inOrder()
    }

    @Test
    fun `the block also reads as app colon, takes lines without a title, and keeps at most five`() {
        val n = ReleaseNotes.parse(
            "<!-- app: Better sound.\n- Just a line\n- A | 1\n- B | 2\n- C | 3\n- D | 4\n- E | 5\n-->\nTagline\n\n## What's new\n- **X.** Y.",
        )
        assertThat(n.headline).isEqualTo("Better sound.")
        assertThat(n.highlights).hasSize(ReleaseNotes.MAX_APP_HIGHLIGHTS)
        assertThat(n.highlights.first()).isEqualTo(ReleaseNotes.Note(null, "Just a line"))
    }

    @Test
    fun `an ordinary comment is not the block`() {
        val n = ReleaseNotes.parse("<!-- application notes: none -->\nHello.\n\n## What's new\n- **X.** Y.")
        assertThat(n.headline).isEqualTo("Hello.")
        assertThat(n.highlights).containsExactly(ReleaseNotes.Note("X", "Y."))
    }

    // ── Odd notes ──────────────────────────────────────────────────────────

    @Test
    fun `empty or odd notes never throw`() {
        assertThat(ReleaseNotes.parse("")).isEqualTo(ReleaseNotes.EMPTY)
        for (odd in listOf("!!!", "##", "- ", "**", "<!--", "```\ncode", "[x](", "| a | b |", "---\n---", "\n\n\n")) {
            val n = ReleaseNotes.parse(odd)
            assertWithMessage(odd).that(shown(n).none { it.contains("http") || it.contains("**") }).isTrue()
        }
    }

    @Test
    fun `GitHub's part goes even without its heading, and code blocks and tables never show`() {
        val n = ReleaseNotes.parse(
            """
            Headline.

            ## What's new
            - **One.** First. ![shot [1]](https://x/y.webp) <img src="https://x/z.png">
            * Fix the thing by @someone in https://github.com/rawnaldclark/Stash/pull/9
            ```
            ./gradlew build
            ```
            | a | b |
            |---|---|
            - Two (#705), see https://example.com, mail a@b.co
            **Full Changelog**: https://github.com/rawnaldclark/Stash/compare/v1...v2
            - Never shown.
            """.trimIndent(),
        )
        assertThat(n.sections.single().notes).containsExactly(
            ReleaseNotes.Note("One", "First."),
            ReleaseNotes.Note(null, "Two, see, mail a@b.co"),
        ).inOrder()
    }

    @Test
    fun `closing hashes, rules and an unclosed comment read as GitHub shows them`() {
        val n = ReleaseNotes.parse("Line.\n\n## Bug fixes ##\n- One.\n- - -\n- Two in C#.\n\n<!-- left open\n- Hidden, as on GitHub.")
        assertThat(n.sections.single().heading).isEqualTo("Bug fixes")
        assertThat(n.sections.single().notes.map { it.body }).containsExactly("One.", "Two in C#.").inOrder()
    }

    @Test
    fun `Unicode line breaks read as line breaks`() {
        val n = ReleaseNotes.parse("Headline.  ## Bug fixes - One.\u0085- Two.\u000B- Three.")
        assertThat(n.headline).isEqualTo("Headline.")
        assertThat(n.sections.single().notes.map { it.body }).containsExactly("One.", "Two.", "Three.").inOrder()
    }

    // ── Bounded work (review on #814) ──────────────────────────────────────

    /**
     * A body as long as GitHub allows (125k), made of what made the old patterns slow: long runs of blanks inside a
     * heading, a PR number, "by @someone in" and a credit; a line of pictures; unclosed links, pictures, comments and
     * code; marks repeated thousands of times. The old parser took seconds on 40k of such text (quadratic) and could
     * try 2ⁿ ways through a line of pictures. Each line is a little over the line cap, so the first 32k (the body cap)
     * holds every kind. Parsed by [ReleaseNotesParser] itself, so a stack overflow would fail the test instead of turning
     * into EMPTY quietly.
     */
    @Test
    fun `a hostile 125k body parses in well under a second`() {
        val blanks = " ".repeat(2_100)
        val tabs = "\t".repeat(300)
        val part = buildString {
            append("Headline a").append(blanks).append("b.\n\n")
            append("## a").append(blanks).append("b\n")
            append("- **Lead.** text (#1").append(blanks).append("x\n")
            append("- note by @a").append(tabs).append("in").append(tabs).append("x\n")
            append("- (thanks ").append("@a ".repeat(700)).append("\n")
            append("Wow").append("!".repeat(600)).append(" Next").append(".".repeat(600)).append(" Then.\n")
            // Line breaks `.` won't cross (#814, second review): one in a long note made `(.*)$` rescan it.
            append("- **").append("_".repeat(1_000)).append(' ').append("_".repeat(1_000)).append('\u0085')
                .append("_".repeat(1_000)).append(" \u000B\u000C x\n")
            repeat(65) { append("<picture><img src=a></picture> ") }
            append("\n")
            repeat(65) { append("<picture><img src=a></picture> ") }
            append("x\n")
            repeat(260) { append("![a](b) ") }
            append("![\n")
            append("[a](".repeat(300)).append('\n')
            append("<picture".repeat(150)).append('\n')
            append("`".repeat(1_200)).append('\n')
            append("**a".repeat(400)).append('\n')
            append("-".repeat(1_200)).append('\n')
            append("# ").append("#".repeat(2_100)).append(" x\n\n")
        }
        assertThat(part.length).isLessThan(ReleaseNotesParser.MAX_BODY)
        val body = buildString {
            while (length < 120_000) append(part)
            append("<!-- app ".repeat(600))
        }
        assertThat(body.length).isAtLeast(125_000)
        ReleaseNotesParser.parse("Warm up.\n\n## What's new\n- **One.** Two.")

        val start = System.nanoTime()
        val n = ReleaseNotesParser.parse(body)
        val ms = (System.nanoTime() - start) / 1_000_000

        assertWithMessage("parse took $ms ms").that(ms).isLessThan(1_000L)
        assertThat(n.headline).startsWith("Headline a")
        assertThat(n.sections).isNotEmpty()
    }

    @Test
    fun `first sentences skip feat, e_g and initials`() {
        assertThat(ReleaseNotesParser.firstSentence("Plays Song (feat. Drake) right. Then more.")).isEqualTo("Plays Song (feat. Drake) right.")
        assertThat(ReleaseNotesParser.firstSentence("Sources, e.g. Qobuz, are faster. And more.")).isEqualTo("Sources, e.g. Qobuz, are faster.")
        assertThat(ReleaseNotesParser.firstSentence("Songs by J. Cole play. Yes.")).isEqualTo("Songs by J. Cole play.")
        assertThat(ReleaseNotesParser.firstSentence("Version 0.9.122 is out. Next.")).isEqualTo("Version 0.9.122 is out.")
        assertThat(ReleaseNotesParser.firstSentence("One sentence only")).isEqualTo("One sentence only")
    }

    @Test
    fun `headings sort into new, polish, fixes and good to know`() {
        assertThat(ReleaseNotesParser.kindOf("What's new")).isEqualTo(Kind.NEW)
        assertThat(ReleaseNotesParser.kindOf("Better sound")).isEqualTo(Kind.NEW)
        assertThat(ReleaseNotesParser.kindOf("UX polish")).isEqualTo(Kind.POLISH)
        assertThat(ReleaseNotesParser.kindOf("Improvements")).isEqualTo(Kind.POLISH)
        assertThat(ReleaseNotesParser.kindOf("Bug fixes")).isEqualTo(Kind.FIXES)
        assertThat(ReleaseNotesParser.kindOf("Fixed")).isEqualTo(Kind.FIXES)
        assertThat(ReleaseNotesParser.kindOf("Good to know")).isEqualTo(Kind.INFO)
        assertThat(ReleaseNotesParser.kindOf("Known limitations")).isEqualTo(Kind.INFO)
    }

    private companion object {
        val TAGS = listOf(
            "v0.9.122", "v0.9.121", "v0.9.120", "v0.9.119", "v0.9.118",
            "v0.9.117", "v0.9.116", "v0.9.115", "v0.9.114", "v0.9.113",
        )
    }
}
