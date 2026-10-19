package com.stash.feature.listening.recap

import com.stash.core.data.db.dao.TopTrackRow

/**
 * Song titles as the Recap cards and share images show them: a remaster tag ("(2004 Remaster)",
 * " - Remastered 2011", "[Remastered]") is dropped, since it truncates the title on a card and
 * says nothing about the song. Conservative on purpose: live, remix, version, edit, mono and
 * every other tag stays (they name a different performance), and a title that is only a tag is
 * left as it is. Display only: matching, the library and History never see this.
 */
internal object RecapTitles {

    /** "(2004 Remaster)", "[Remastered 2011]", "(Remastered Version)", in brackets at the end. */
    private val BRACKETED = Regex(
        """\s*[(\[]\s*(?:\d{4}\s+)?(?:digital(?:ly)?\s+)?remaster(?:ed)?(?:\s+\d{4})?(?:\s+version)?\s*[)\]]\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /** " - Remastered 2011", " – 2004 Remaster", " - Remastered" after a dash at the end. */
    private val DASHED = Regex(
        """\s+[-–—]\s+(?:\d{4}\s+)?(?:digital(?:ly)?\s+)?remaster(?:ed)?(?:\s+\d{4})?(?:\s+version)?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    fun display(title: String): String {
        var t = title
        // At most two passes: "Song - Remastered 2011 (2011 Remaster)" style doubles.
        repeat(2) { t = t.replace(BRACKETED, "").replace(DASHED, "") }
        return t.trim().ifEmpty { title }
    }
}

/** The song's title for a Recap card ([RecapTitles.display]). */
internal val TopTrackRow.displayTitle: String get() = RecapTitles.display(track.title)
