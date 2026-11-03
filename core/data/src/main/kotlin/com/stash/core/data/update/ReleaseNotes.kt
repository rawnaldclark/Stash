package com.stash.core.data.update

/**
 * What the update sheet shows from a release's notes: the user's part of them, never the developer's.
 *
 * The notes are written in one shape (see the release-notes rules): a first line in plain words, then `## What's new`
 * with `- **Bold lead.** text` bullets, then `## UX polish` and `## Bug fixes`, sometimes `## Known limitations`. The
 * release workflow then adds `---`, `## Install`, GitHub's generated `## What's Changed` (the PR list, with @handles and
 * links) and `**Full Changelog**`. [parse] keeps the first part and drops the rest, and inside it drops pictures, links'
 * addresses, PR numbers and "Thanks to @someone" credits.
 *
 * An optional hidden block in the notes gives the app's exact words (GitHub doesn't show HTML comments):
 * ```
 * <!-- stash-app
 * Paste links from Spotify, YouTube or SoundCloud, and update Stash from the app.
 * - The Sync tab, rebuilt | One sync puts your Spotify and YouTube Music on Home.
 * - SoundCloud | Sync your likes and playlists, and paste any SoundCloud link.
 * -->
 * ```
 * Lines without a dash are the headline; each dash line is a highlight, its title before the `|` and its line after it
 * (no `|`: a line with no title). Either part may be left out: the notes fill in what the block doesn't say.
 */
data class ReleaseNotes(
    /** The notes' first line in plain words ("" when they start with a heading). */
    val headline: String,
    /** The sheet's highlights card: up to [HIGHLIGHTS] notes, each with its first sentence (or the hidden block's words). */
    val highlights: List<Note>,
    /** Every user-facing section in order, each note whole: the full notes. */
    val sections: List<Section>,
    /** New things not in [highlights]. */
    val moreNew: Int,
    /** Improvements (the polish section) not in [highlights]. */
    val improvements: Int,
    /** Fixes not in [highlights]. */
    val fixes: Int,
    /** Whether the full notes say more than the sheet does: more notes, more of a highlight, or a "good to know". */
    val hasMore: Boolean,
) {
    /** One change: its bold lead ([title], null without one), its text, and the points listed under it. */
    data class Note(val title: String?, val body: String, val details: List<String> = emptyList())

    /** A section of the notes under its own heading (null: the notes before the first heading). */
    data class Section(val heading: String?, val kind: Kind, val notes: List<Note>)

    enum class Kind {
        /** What's new, or any section about one area ("Better sound", "Lyrics"). */
        NEW,

        /** UX polish, improvements. */
        POLISH,

        /** Bug fixes. */
        FIXES,

        /** Good to know, known limitations: shown in full, never counted. */
        INFO,
    }

    companion object {
        /** How many notes the sheet's card shows (owner, 11-02). */
        const val HIGHLIGHTS = 3

        /** The most the hidden block may put on the card. */
        const val MAX_APP_HIGHLIGHTS = 5

        val EMPTY = ReleaseNotes("", emptyList(), emptyList(), 0, 0, 0, false)

        /**
         * The user's part of [markdown], a GitHub release body. Never throws; unreadable notes give [EMPTY]. Bounded work
         * whatever the body (see [ReleaseNotesParser]'s limits), but still a parse: call it off the main thread.
         */
        fun parse(markdown: String): ReleaseNotes = runCatching { ReleaseNotesParser.parse(markdown) }.getOrDefault(EMPTY)
    }
}

/**
 * [ReleaseNotes.parse]'s work: a line-by-line read of the Markdown GitHub's releases use.
 *
 * Its work is bounded whatever the body says (review on #814: a long run of spaces made three patterns quadratic, seconds
 * of work at 40k characters). The body, each line, each note and the number of lines read are capped far above any real
 * release (v0.9.122's user part is ~6k characters in ~60 lines); [clean] folds blank runs before anything else; and no
 * pattern can try more than one way through the same text: runs are possessive (`*+`, `++`) or atomic, scans stop at the
 * next opener (`[^()]`, `[^<>]`), and the closing `#`s of a heading, `<!-- -->` comments and rules are read in code.
 */
internal object ReleaseNotesParser {

    /** The most of a body read: GitHub allows 125k; a real release's user part is ~6k. */
    const val MAX_BODY = 32_000

    /** The most of one line read. */
    const val MAX_LINE = 2_000

    /**
     * The most lines read, every one counted (blank, fenced, table and dropped lines too), whether or not GitHub's part
     * comes first. Of the hidden block's lines, the same.
     */
    const val MAX_LINES = 400

    /** The most one note, one point under it, or the headline grows to from its wrapped lines. */
    const val MAX_NOTE = 4_000

    fun parse(markdown: String): ReleaseNotes {
        // Every line break `.` won't cross becomes a plain one first (U+2028, U+2029, U+0085, and \v and \f on Android's
        // ICU): left inside a line, `(.*)$` couldn't reach the end and rescanned from each position (#814, second review).
        val text = LINE_BREAKS.replace(markdown.take(MAX_BODY).replace("\r\n", "\n"), "\n")
        val (visible, comments) = splitComments(text)
        val block = comments.firstNotNullOfOrNull { c -> APP_BLOCK_START.find(c)?.let { appBlock(c.substring(it.range.last + 1)) } }
        val lines = userLines(visible)

        val headlineParts = mutableListOf<String>()
        var headlineLength = 0
        var headlineOpen = true
        val sections = mutableListOf<SectionBuilder>()
        var note: NoteBuilder? = null
        var inDetail = false
        var blank = true

        fun flush() {
            val built = note?.build()
            note = null
            inDetail = false
            if (built == null) return
            val section = sections.lastOrNull() ?: SectionBuilder(null).also { sections += it }
            section.notes += built
        }

        for (line in lines) {
            when (line) {
                is Line.Blank -> {
                    if (headlineParts.isNotEmpty()) headlineOpen = false
                    blank = true
                }
                is Line.Heading -> {
                    headlineOpen = false
                    flush()
                    sections += SectionBuilder(cleanHeading(line.text))
                    blank = true
                }
                is Line.Bullet -> {
                    headlineOpen = false
                    val owner = note
                    val detail = owner != null && ((owner.bullet && line.indent >= 2) || (!owner.bullet && (!blank || line.indent >= 2)))
                    if (detail) {
                        owner!!.details += line.text
                        inDetail = true
                    } else {
                        flush()
                        note = NoteBuilder(line.text, bullet = true)
                    }
                    blank = false
                }
                is Line.Text -> {
                    if (headlineOpen && (headlineParts.isEmpty() || !blank) && !(headlineParts.isEmpty() && line.text.trimStart().startsWith("**"))) {
                        if (headlineLength < MAX_NOTE) {
                            headlineParts += line.text
                            headlineLength += line.text.length + 1
                        }
                        blank = false
                        continue
                    }
                    headlineOpen = false
                    val owner = note
                    if (owner != null && (!blank || (line.indent >= 2 && owner.bullet))) {
                        // A line of the same item (wrapped, or an indented paragraph under a bullet).
                        if (inDetail) {
                            owner.details[owner.details.lastIndex] = joinCapped(owner.details.last(), line.text)
                        } else {
                            owner.body = joinCapped(owner.body, line.text)
                        }
                    } else {
                        flush()
                        note = NoteBuilder(line.text, bullet = false)
                    }
                    blank = false
                }
            }
        }
        flush()

        val built = sections.filter { it.notes.isNotEmpty() }.map { ReleaseNotes.Section(it.heading, it.kind, it.notes.toList()) }
        val new = built.filter { it.kind == ReleaseNotes.Kind.NEW }.flatMap { it.notes }
        val polish = built.filter { it.kind == ReleaseNotes.Kind.POLISH }.flatMap { it.notes }
        val fixes = built.filter { it.kind == ReleaseNotes.Kind.FIXES }.flatMap { it.notes }
        val headline = block?.headline?.takeIf { it.isNotEmpty() } ?: clean(headlineParts.joinToString(" "))

        if (block != null && block.highlights.isNotEmpty()) {
            val highlights = block.highlights.take(ReleaseNotes.MAX_APP_HIGHLIGHTS)
            return ReleaseNotes(
                headline = headline,
                highlights = highlights,
                sections = built,
                moreNew = (new.size - highlights.size).coerceAtLeast(0),
                improvements = polish.size,
                fixes = fixes.size,
                hasMore = built.isNotEmpty(),
            )
        }

        // New things first; a release with fewer than three (a fix-only one) fills the card from polish, then fixes.
        val takeNew = minOf(ReleaseNotes.HIGHLIGHTS, new.size)
        val takePolish = minOf(ReleaseNotes.HIGHLIGHTS - takeNew, polish.size)
        val takeFixes = minOf(ReleaseNotes.HIGHLIGHTS - takeNew - takePolish, fixes.size)
        val picked = new.take(takeNew) + polish.take(takePolish) + fixes.take(takeFixes)
        val highlights = picked.map { ReleaseNotes.Note(it.title, firstSentence(it.body)) }
        val moreNew = new.size - takeNew
        val improvements = polish.size - takePolish
        val fixesLeft = fixes.size - takeFixes
        val hasMore = moreNew + improvements + fixesLeft > 0 ||
            built.any { it.kind == ReleaseNotes.Kind.INFO } ||
            picked.zip(highlights).any { (whole, shown) -> whole.body != shown.body || whole.details.isNotEmpty() }
        return ReleaseNotes(headline, highlights, built, moreNew, improvements, fixesLeft, hasMore)
    }

    // ── Lines ────────────────────────────────────────────────────────────────

    sealed interface Line {
        data object Blank : Line
        data class Heading(val text: String) : Line
        data class Bullet(val indent: Int, val text: String) : Line
        data class Text(val indent: Int, val text: String) : Line
    }

    /** The notes' lines up to the developer's tail (Install, What's Changed, Full Changelog), sorted into kinds. */
    fun userLines(text: String): List<Line> {
        val out = mutableListOf<Line>()
        var fence = false
        for (raw in text.splitToSequence('\n').take(MAX_LINES)) {
            val line = raw.replace("\t", "    ").take(MAX_LINE).trimEnd()
            if (FENCE.matches(line)) {
                fence = !fence
                continue
            }
            if (fence) continue
            if (isDevTail(line)) break
            val unquoted = QUOTE.replace(line, "")
            when {
                unquoted.isBlank() || isRule(unquoted) || PICTURES_ONLY.matches(unquoted) -> out += Line.Blank
                TABLE.matches(unquoted) || LINK_DEFINITION.matches(unquoted) || GENERATED_PR_LINE.containsMatchIn(unquoted) -> Unit
                else -> {
                    val heading = headingText(unquoted)
                        ?: BOLD_HEADING.matchEntire(unquoted)?.groupValues?.get(1)?.takeUnless { it.trimEnd().endsWith('.') }
                    val bullet = BULLET.matchEntire(unquoted)
                    out += when {
                        heading != null -> Line.Heading(heading)
                        bullet != null -> Line.Bullet(bullet.groupValues[1].length, bullet.groupValues[2])
                        else -> Line.Text(unquoted.length - unquoted.trimStart().length, unquoted.trim())
                    }
                }
            }
        }
        return out
    }

    /** A `# Heading`'s words, without the closing `#`s some writers add ("## Fixes ##"); null for any other line. */
    private fun headingText(line: String): String? {
        val words = HEADING.matchEntire(line)?.groupValues?.get(1)?.trimEnd() ?: return null
        val open = words.trimEnd('#')
        return if (open.length < words.length && (open.isEmpty() || open.last().isWhitespace())) open.trimEnd() else words
    }

    /** `---`, `***` or `___` (spaces between allowed): a rule, which the notes read as a gap. */
    private fun isRule(line: String): Boolean {
        val mark = line.trimStart()
        if (line.length - mark.length > 3 || mark.isEmpty() || mark[0] !in "-*_") return false
        var count = 0
        for (c in mark) {
            when {
                c == mark[0] -> count++
                !c.isWhitespace() -> return false
            }
        }
        return count >= 3
    }

    /** Where GitHub's part starts: `## Install`, `## What's Changed`, `## New Contributors`, `**Full Changelog**`. */
    private fun isDevTail(line: String): Boolean {
        headingText(line)?.let { return normalise(it) in DEV_HEADINGS }
        return FULL_CHANGELOG.containsMatchIn(line)
    }

    private fun normalise(heading: String): String = trimEdgeSymbols(clean(heading).replace('’', '\'').lowercase())

    /** No emoji or marks in front ("🐛 Fixes" → "Fixes"), no colon or space after. */
    private fun trimEdgeSymbols(s: String): String = LEADING_SYMBOLS.replace(s, "").trimEnd { it.isWhitespace() || it == ':' }

    /**
     * [text] without its `<!-- … -->` comments, and the comments' insides in order. One left open runs to the end, as
     * GitHub hides the rest of the body then too. In code, not a lazy `.*?`: that scans to the end once per unclosed `<!--`.
     */
    private fun splitComments(text: String): Pair<String, List<String>> {
        val visible = StringBuilder(text.length)
        val comments = mutableListOf<String>()
        var at = 0
        while (true) {
            val open = text.indexOf("<!--", at)
            if (open < 0) {
                visible.append(text, at, text.length)
                break
            }
            visible.append(text, at, open)
            val close = text.indexOf("-->", open + 4)
            if (close < 0) {
                comments += text.substring(open + 4)
                break
            }
            comments += text.substring(open + 4, close)
            at = close + 3
        }
        return visible.toString() to comments
    }

    /** [base] and a wrapped line of it, one space between; [base] as it is once it reaches [MAX_NOTE]. */
    private fun joinCapped(base: String, more: String): String = if (base.length >= MAX_NOTE) base else "$base $more"

    // ── Notes ────────────────────────────────────────────────────────────────

    private class SectionBuilder(val heading: String?) {
        val kind: ReleaseNotes.Kind = heading?.let(::kindOf) ?: ReleaseNotes.Kind.NEW
        val notes = mutableListOf<ReleaseNotes.Note>()
    }

    private class NoteBuilder(var body: String, val bullet: Boolean) {
        val details = mutableListOf<String>()

        fun build(): ReleaseNotes.Note? {
            val (title, text) = splitLead(body)
            val points = details.map(::clean).filter { it.isNotEmpty() }
            if (title.isNullOrEmpty() && text.isEmpty()) return null
            return ReleaseNotes.Note(title?.takeIf { it.isNotEmpty() }, text, points)
        }
    }

    fun kindOf(heading: String): ReleaseNotes.Kind {
        val h = heading.lowercase()
        return when {
            FIX_WORDS.containsMatchIn(h) -> ReleaseNotes.Kind.FIXES
            POLISH_WORDS.containsMatchIn(h) -> ReleaseNotes.Kind.POLISH
            INFO_WORDS.containsMatchIn(h) -> ReleaseNotes.Kind.INFO
            else -> ReleaseNotes.Kind.NEW
        }
    }

    /** A heading in plain words: no emoji in front ("🐛 Fixes" → "Fixes"), no colon after. */
    fun cleanHeading(raw: String): String? = trimEdgeSymbols(clean(raw)).trim().takeIf { it.isNotEmpty() }

    /**
     * "**The Sync tab, rebuilt.** A fresh install…" → ("The Sync tab, rebuilt", "A fresh install…"). A bold start that runs
     * on into its sentence ("**Your queue comes back** after…") is emphasis, not a title: no title, the whole sentence.
     */
    fun splitLead(raw: String): Pair<String?, String> {
        val m = LEAD.matchEntire(raw.trim()) ?: return null to clean(raw)
        val lead = clean(m.groupValues[1].ifEmpty { m.groupValues[2] })
        val rest = clean(m.groupValues[3])
        val runsOn = lead.isNotEmpty() && lead.last() !in ".:!?" && rest.isNotEmpty() && !rest.first().isUpperCase() && !rest.first().isDigit()
        if (runsOn || lead.isEmpty()) return null to clean(raw)
        val title = lead.trimEnd('.', ':', ' ')
        return title to rest.replaceFirstChar { it.uppercaseChar() }
    }

    /** The first sentence of [text] (all of it when it is one). Doesn't stop at "feat.", "e.g." or an initial. */
    fun firstSentence(text: String): String {
        for (m in SENTENCE_END.findAll(text)) {
            var from = m.range.first
            while (from > 0 && !text[from - 1].isWhitespace()) from--
            val word = text.substring(from, m.range.first).trimStart('(', '"', '“')
            if (word.lowercase() in ABBREVIATIONS || (word.length == 1 && word[0].isUpperCase())) continue
            return text.substring(0, m.range.last + 1).trim()
        }
        return text.trim()
    }

    /**
     * One line of Markdown as plain words: pictures gone, links' text kept and their addresses dropped, no bare URLs, PR
     * numbers or "by @someone in …", emphasis marks off, and "Thanks to @someone." credits taken out (an @ elsewhere
     * loses its @). Blank runs folded, first of all: no pattern below meets a run of spaces.
     */
    fun clean(s: String): String {
        var t = SPACES.replace(s, " ")
        t = IMAGE.replace(t, "")
        t = HTML_IMAGE.replace(t, "")
        t = LINK.replace(t) { it.groupValues[1] }
        t = REF_LINK.replace(t) { it.groupValues[1] }
        t = AUTOLINK.replace(t, "")
        t = HTML_TAG.replace(t, "")
        t = GENERATED_BY.replace(t, "")
        t = URL.replace(t, "")
        t = PR_REF.replace(t, "")
        t = BOLD.replace(t) { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        t = ITALIC_STAR.replace(t) { it.groupValues[1] }
        t = ITALIC_UNDERSCORE.replace(t) { it.groupValues[1] }
        t = STRIKE.replace(t) { it.groupValues[1] }
        t = CODE.replace(t) { it.groupValues[1] }
        t = STRAY_MARKS.replace(t, "")
        t = ENTITIES.entries.fold(t) { acc, (k, v) -> acc.replace(k, v) }
        t = THANKS_ASIDE.replace(t, "")
        t = SENTENCE_BREAK.split(t).filterNot { HANDLE.containsMatchIn(it) && THANKS.containsMatchIn(it) }.joinToString(" ")
        t = HANDLE.replace(t) { it.groupValues[1] }
        t = LONE_BANG.replace(t, "")
        t = SPACES.replace(t, " ")
        t = SPACE_BEFORE_PUNCTUATION.replace(t, "$1")
        t = EMPTY_BRACKETS.replace(t, "")
        return SPACES.replace(t, " ").trim()
    }

    // ── The hidden block ────────────────────────────────────────────────────

    class AppBlock(val headline: String, val highlights: List<ReleaseNotes.Note>)

    fun appBlock(body: String): AppBlock {
        val headline = mutableListOf<String>()
        val highlights = mutableListOf<ReleaseNotes.Note>()
        for (raw in body.split('\n').take(MAX_LINES)) {
            val line = raw.take(MAX_LINE).trim()
            if (line.isEmpty()) continue
            val bullet = BLOCK_BULLET.matchEntire(line)
            if (bullet == null) {
                headline += line
                continue
            }
            val item = bullet.groupValues[1]
            val bar = item.indexOf('|')
            val title = if (bar >= 0) clean(item.substring(0, bar)).trimEnd('.', ':', ' ') else ""
            val text = clean(if (bar >= 0) item.substring(bar + 1) else item)
            if (title.isNotEmpty() || text.isNotEmpty()) highlights += ReleaseNotes.Note(title.ifEmpty { null }, text)
        }
        return AppBlock(clean(headline.joinToString(" ")), highlights)
    }

    // ── Patterns ────────────────────────────────────────────────────────────

    // Each pattern is linear in its text (#814 review): a run is taken whole (`*+`, `++`: no giving back to try again),
    // a scan stops at the next opener of its kind, and a match can start only at the start of a run of blanks
    // (`(?<!\s)`). Checked against long runs of every mark these patterns read.

    /** A lone \r, \v, \f, U+0085, U+2028 or U+2029: each read as a line break. */
    private val LINE_BREAKS = Regex("""[\r\u000B\u000C\u0085\u2028\u2029]""")

    /** The start of the hidden block, inside a comment: "stash-app" or "app", then a colon or a blank. */
    private val APP_BLOCK_START = Regex("""^\s*+(?:stash-app|app)(?:\s*+:|\s)""", RegexOption.IGNORE_CASE)
    private val BLOCK_BULLET = Regex("""^(?:[-*+•])\s+(.*)$""")

    private val FENCE = Regex("""^\s*(?:```|~~~).*$""")
    private val QUOTE = Regex("""^\s{0,3}>\s?""")
    /** `# Heading`: the closing `#`s are taken off in code ([headingText]); a lazy `.*?` before them was quadratic. */
    private val HEADING = Regex("""^\s{0,3}#{1,6}\s+(.*)$""")
    /** A line that is only a short bold phrase ("**What's new**") is a heading too, unless it ends a sentence. */
    private val BOLD_HEADING = Regex("""^\s{0,3}\*\*([^*]{1,40})\*\*:?\s*$""")
    private val BULLET = Regex("""^(\s*)(?:[-*+•]|\d{1,3}[.)])\s+(.*)$""")
    private val TABLE = Regex("""^\s*\|.*\|\s*$""")
    private val LINK_DEFINITION = Regex("""^\s{0,3}\[[^\]]+]:\s*\S+.*$""")
    /** A line of nothing but pictures. Each picture is atomic and can't run into the next: one way through, never 2ⁿ. */
    private val PICTURES_ONLY = Regex(
        """^\s*+(?:(?>!\[(?:[^\[\]]++|\[[^\[\]]*+])*+](?:\((?:[^()]++|\([^()]*+\))*+\)|\[[^\[\]]*+])|<img\b[^<>]*+>|<picture\b(?:(?!</?picture\b)[\s\S])*+</picture>)\s*+)++$""",
        RegexOption.IGNORE_CASE,
    )
    /** A line of GitHub's generated PR list that lost its heading: "* Title by @someone in https://github.com/…/pull/1". */
    private val GENERATED_PR_LINE = Regex("""\bby @[\w-]+ in https?://github\.com/""")
    private val FULL_CHANGELOG = Regex("""^\s*+\**+\s*+full changelog\b""", RegexOption.IGNORE_CASE)
    private val DEV_HEADINGS = setOf(
        "install", "installation", "how to install", "what's changed", "whats changed", "new contributors", "full changelog",
        "checksums", "commits",
    )
    private val LEADING_SYMBOLS = Regex("""^[^\p{L}\p{N}]++""")

    private val FIX_WORDS = Regex("""\b(?:bugs?|fix(?:es|ed)?)\b""")
    private val POLISH_WORDS = Regex("""polish|improve|tweak|refine|smaller changes|under the hood""")
    private val INFO_WORDS = Regex("""good to know|known (?:issues?|limitations?|problems?)|heads.?up|before you update|\bnotes?\b""")

    private val LEAD = Regex("""^(?:\*\*(.+?)\*\*|__(.+?)__)(.*)$""")
    /** A sentence's end: starts only at the first mark of a run ("Wow!!! Next"), never once per mark. */
    private val SENTENCE_END = Regex("""(?<![.!?])[.!?]++["”’')\]]*+(?=\s+["“‘(\[]?[\p{Lu}\d])""")
    private val ABBREVIATIONS = setOf("e.g", "i.e", "vs", "approx", "mr", "mrs", "ms", "dr", "st", "no", "feat", "ft", "vol")

    /** `![alt](address)`: brackets and parentheses one deep inside, each scan ending at the next opener. */
    private val IMAGE = Regex("""!\[(?:[^\[\]]++|\[[^\[\]]*+])*+](?:\((?:[^()]++|\([^()]*+\))*+\)|\[[^\[\]]*+])""")
    private val HTML_IMAGE = Regex("""<picture\b(?:(?!</?picture\b)[\s\S])*+</picture>|<img\b[^<>]*+>""", RegexOption.IGNORE_CASE)
    private val LINK = Regex("""\[((?:[^\[\]]++|\[[^\[\]]*+])++)]\((?:[^()]++|\([^()]*+\))*+\)""")
    private val REF_LINK = Regex("""\[([^\[\]]++)]\[[^\[\]]*+]""")
    private val AUTOLINK = Regex("""<(?:https?|mailto):[^<>]++>""", RegexOption.IGNORE_CASE)
    private val HTML_TAG = Regex("""</?[A-Za-z][^<>]*+>""")
    private val GENERATED_BY = Regex("""(?<!\s)\s++by\s++@[\w-]++\s++in\s++\S++""")
    /** A bare address, not the punctuation after it. */
    private val URL = Regex("""\bhttps?://[^\s<>]*[^\s<>.,;:!?)'"\]]""", RegexOption.IGNORE_CASE)
    private val PR_REF = Regex("""(?<!\s)\s*+\(\s*+(?:PR\s*+)?#\d++(?:\s*+,\s*+#\d++)*+\s*+\)""", RegexOption.IGNORE_CASE)
    private val BOLD = Regex("""\*\*(.+?)\*\*|__(.+?)__""")
    private val ITALIC_STAR = Regex("""(?<![\w*])\*(?!\s)([^*\n]+?)(?<!\s)\*(?![\w*])""")
    private val ITALIC_UNDERSCORE = Regex("""(?<![\w_])_(?!\s)([^_\n]+?)(?<!\s)_(?![\w_])""")
    private val STRIKE = Regex("""~~(.+?)~~""")
    private val CODE = Regex("""(?<!`)`++([^`]++)`++""")
    /** Emphasis marks with nothing to mark, left from a broken line. */
    private val STRAY_MARKS = Regex("""\*\*+|__+|`+""")
    private val ENTITIES = mapOf("&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&nbsp;" to " ")
    /** An @handle (not an e-mail address, not inside a path). */
    private val HANDLE = Regex("""(?<![\w.@/])@([A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)\b""")
    private val THANKS = Regex("""^\W*(?:big\s+)?(?:thanks|thank you|kudos|shout-?outs?|credits?|props|hat tip|h/t|reported by|fixed by|contributed by|via)\b""", RegexOption.IGNORE_CASE)
    private val THANKS_ASIDE = Regex("""(?<!\s)\s*+\(\s*+(?:thanks|thank you|h/t|via|by)\b[^()@]*+@[^()]*+\)""", RegexOption.IGNORE_CASE)
    private val SENTENCE_BREAK = Regex("""(?<=[.!?])\s+""")
    private val LONE_BANG = Regex("""(?<=^|\s)!+(?=\s|$)""")
    private val SPACES = Regex("""\s+""")
    private val SPACE_BEFORE_PUNCTUATION = Regex("""(?<!\s)\s++([.,;:!?)])""")
    private val EMPTY_BRACKETS = Regex("""\(\s*\)""")
}
