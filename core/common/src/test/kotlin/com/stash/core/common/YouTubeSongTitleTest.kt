package com.stash.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeSongTitleTest {

    private fun parse(title: String, artist: String) = YouTubeSongTitle.parse(title, artist)

    @Test fun `artist - title with video noise`() {
        val p = parse("Rage Against The Machine - People of the Sun (Official HD Video)", "RageAgainstTheMachineVEVO")
        assertEquals("Rage Against The Machine", p.artist)
        assertEquals("People of the Sun", p.title)
        assertEquals("", p.version)
    }

    @Test fun `a live-session channel names the artist in the title and keeps the version`() {
        val p = parse("Phantogram - When I'm Small (Live on KEXP)", "KEXP")
        assertEquals("Phantogram", p.artist)
        assertEquals("When I'm Small", p.title)
        assertEquals("Live on KEXP", p.version)
        assertEquals("When I'm Small (Live on KEXP)", p.queryTitle)
    }

    @Test fun `a clean row is left alone`() {
        val p = parse("Supermassive Black Hole", "Muse")
        assertEquals(YouTubeSongTitle.Parsed("Muse", "Supermassive Black Hole", ""), p)
    }

    @Test fun `title - artist, the reverse order, when the right side is the channel`() {
        val p = parse("Only A Case - Nipsey Hussle", "Nipsey Hussle")
        assertEquals("Nipsey Hussle", p.artist)
        assertEquals("Only A Case", p.title)
    }

    @Test fun `a dash version on a real artist row stays a version`() {
        val p = parse("An Ending (Ascent) - Remastered 2005", "Brian Eno")
        assertEquals("Brian Eno", p.artist)
        assertEquals("An Ending (Ascent)", p.title)
        assertEquals("Remastered 2005", p.version)
    }

    @Test fun `a catalog-style credit keeps a movement dash and drops the composer prefix`() {
        val p = parse("Dvořák: Serenade for Strings in E, Op. 22 - 1. Moderato", "London Philharmonic Orchestra, Christopher Hogwood, Antonín Dvořák")
        assertEquals("Serenade for Strings in E, Op. 22 - 1. Moderato", p.title)
        assertEquals("London Philharmonic Orchestra, Christopher Hogwood, Antonín Dvořák", p.artist)
    }

    @Test fun `artist and a quoted title keep the version after the quote`() {
        val p = parse("Cage The Elephant performing \"Cold Cold Cold\" Live on KCRW", "KCRW")
        assertEquals("Cage The Elephant", p.artist)
        assertEquals("Cold Cold Cold", p.title)
        assertEquals("Live on KCRW", p.version)
    }

    @Test fun `every version bracket is kept, not just the first`() {
        val p = parse("Purple Rain (2015 Remaster) (Live)", "Prince")
        assertEquals("Purple Rain", p.title)
        assertEquals("2015 Remaster, Live", p.version)
        assertEquals("Purple Rain (2015 Remaster, Live)", p.queryTitle)
    }

    @Test fun `a version word beats a noise phrase`() {
        val p = parse("Song (Official Live Video)", "Band")
        assertEquals("Song", p.title)
        assertEquals("Official Live Video", p.version)
    }

    @Test fun `a film performance is a version, not the studio cut`() {
        val p = parse("Joy Division - Transmission (Performance From \"Control\")", "Vaughan Walton")
        assertEquals("Joy Division", p.artist)
        assertEquals("Transmission", p.title)
        assertEquals("Performance From \"Control\"", p.version)
        assertEquals(setOf("live"), VersionWords.forCatalogMatch(p.version))
    }

    @Test fun `a real artist keeps the row when the right side is a COLORS show`() {
        val p = parse("Until Morning - A COLORS SHOW", "James Vickery")
        assertEquals("James Vickery", p.artist)
        assertEquals("Until Morning", p.title)
        assertEquals("A COLORS SHOW", p.version)
    }

    @Test fun `a real artist keeps the row when the left side is a version`() {
        val p = parse("BBC Sessions - Love Or Confusion", "Jimi Hendrix")
        assertEquals("Jimi Hendrix", p.artist)
        assertEquals("Love Or Confusion", p.title)
        assertEquals("BBC Sessions", p.version)
    }

    @Test fun `a soundtrack note after the dash is not a title`() {
        val p = parse("Lose Yourself - From \"8 Mile\" Soundtrack", "Eminem")
        assertEquals("Eminem", p.artist)
        assertEquals("Lose Yourself", p.title)
        assertEquals("", p.version)
    }

    @Test fun `a dash before only a bracketed note is not artist - title`() {
        // YouTube Music's "Nightmare Before Christmas Special Edition" (2006), as Tidal lists it too.
        // Read as artist "Overture", song "(The Nightmare Before Christmas)": no catalog title matched.
        val overture = parse("Overture - (The Nightmare Before Christmas)", "Danny Elfman")
        assertEquals(YouTubeSongTitle.Parsed("Danny Elfman", "Overture - (The Nightmare Before Christmas)", ""), overture)
        val opening = parse("Opening - (The Nightmare Before Christmas)", "Patrick Stewart")
        assertEquals(YouTubeSongTitle.Parsed("Patrick Stewart", "Opening - (The Nightmare Before Christmas)", ""), opening)
        // Square brackets and an en dash read the same.
        assertEquals(YouTubeSongTitle.Parsed("Band", "Song – [Disc 2]", ""), parse("Song – [Disc 2]", "Band"))
    }

    @Test fun `a channel row with a bracketed note still takes the artist from the left side`() {
        val p = parse("Danny Elfman - Overture - (The Nightmare Before Christmas)", "Disney Music")
        assertEquals("Danny Elfman", p.artist)
        assertEquals("Overture - (The Nightmare Before Christmas)", p.title)
    }

    @Test fun `a dash left bare once the noise bracket goes is not artist - title`() {
        // A labelled row the parser got wrong: artist "Church & State", no title.
        val p = parse("Church & State (Sad Gay Version) - (Official Lyric Video)", "Brandi Carlile")
        assertEquals(YouTubeSongTitle.Parsed("Brandi Carlile", "Church & State", "Sad Gay Version"), p)
        assertEquals(YouTubeSongTitle.Parsed("Band", "Song", ""), parse("Song - (Official Video)", "Band"))
        assertEquals(YouTubeSongTitle.Parsed("Band", "Song", "Live"), parse("Song - (Live)", "Band"))
    }

    @Test fun `a channel row still takes the artist from the left side`() {
        val p = parse("Phantogram - When I'm Small", "KEXP")
        assertEquals("Phantogram", p.artist)
        assertEquals("When I'm Small", p.title)
    }

    /**
     * The labelled library rows (src/test/resources/youtube/titles-v1.tsv: 6,843 YouTube Music rows
     * whose song the text alone identifies, labelled by Claude). Raw fields: 78.9% right. Ratchets:
     * raise [MIN_BOTH], [MIN_VERSION_WORDS] and [MIN_VERSIONS_FOUND] when the parser improves; a
     * change may not lower them. The version column is checked too: a version the parser drops is a
     * live take or remix the matcher would trade for the studio cut. 2026-10-17, keeping every
     * version (not just the first bracket) and the words after a quoted title: both 91.5 -> 91.7%,
     * version words 98.0 -> 98.2%, performance versions found 82.3 -> 88.0%. 2026-11-13, a dash before
     * only a bracket is no "Artist - Title" split: title 92.8 -> 92.9%, the rest unchanged.
     */
    @Test fun `parses the labelled library rows`() {
        val rows = checkNotNull(javaClass.classLoader?.getResource("youtube/titles-v1.tsv")).readText()
            .lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }.toList()
        var both = 0
        var artistOk = 0
        var titleOk = 0
        var versionOk = 0
        var versionWordsOk = 0
        var labelledVersions = 0
        var labelledVersionsFound = 0
        for (r in rows) {
            val (title, artist, wantArtist, wantTitle) = r
            val wantVersion = r.getOrElse(4) { "" }
            val p = parse(title, artist)
            val want = YouTubeSongTitle.tokens(wantArtist)
            val a = YouTubeSongTitle.tokens(p.artist).let { it == want || (want.isNotEmpty() && it.containsAll(want)) }
            val t = YouTubeSongTitle.tokens(p.title) == YouTubeSongTitle.tokens(wantTitle)
            if (a) artistOk++
            if (t) titleOk++
            if (a && t) both++
            // The version column, as the matcher reads it: the same performance words (live,
            // remix, acoustic...) on both sides. A remaster or "Single Version" names none, so
            // "" and "Remastered 2009" agree, as they do for the matcher.
            val words = VersionWords.forCatalogMatch(wantVersion)
            if (VersionWords.forCatalogMatch(p.version) == words) versionWordsOk++
            if (YouTubeSongTitle.tokens(p.version) == YouTubeSongTitle.tokens(wantVersion)) versionOk++
            if (words.isNotEmpty()) {
                labelledVersions++
                if (VersionWords.forCatalogMatch(p.version).containsAll(words)) labelledVersionsFound++
            }
        }
        val pct = { n: Int -> 100.0 * n / rows.size }
        val foundPct = 100.0 * labelledVersionsFound / labelledVersions
        println(
            "youtube titles v1: ${rows.size} rows | artist %.1f%% | title %.1f%% | both %.1f%% | version text %.1f%% | version words %.1f%% | performance versions found %d/%d (%.1f%%)"
                .format(pct(artistOk), pct(titleOk), pct(both), pct(versionOk), pct(versionWordsOk), labelledVersionsFound, labelledVersions, foundPct),
        )
        assertTrue("both right: %.1f%% < $MIN_BOTH%%".format(pct(both)), pct(both) >= MIN_BOTH)
        assertTrue("version words right: %.1f%% < $MIN_VERSION_WORDS%%".format(pct(versionWordsOk)), pct(versionWordsOk) >= MIN_VERSION_WORDS)
        assertTrue("performance versions found: %.1f%% < $MIN_VERSIONS_FOUND%%".format(foundPct), foundPct >= MIN_VERSIONS_FOUND)
    }

    private companion object {
        const val MIN_BOTH = 91.6

        /** Rows whose parsed version names the label's performance words, no more and no fewer. */
        const val MIN_VERSION_WORDS = 98.0

        /** Of the rows labelled with a performance (live, remix, cover...), how many the parser keeps. */
        const val MIN_VERSIONS_FOUND = 87.5
    }
}
