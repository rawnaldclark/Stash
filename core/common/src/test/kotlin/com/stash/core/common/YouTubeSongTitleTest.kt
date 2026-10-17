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

    @Test fun `artist and a quoted title`() {
        val p = parse("Cage The Elephant performing \"Cold Cold Cold\" Live on KCRW", "KCRW")
        assertEquals("Cage The Elephant", p.artist)
        assertEquals("Cold Cold Cold", p.title)
    }

    /**
     * The labelled library rows (src/test/resources/youtube/titles-v1.tsv: 6,843 YouTube Music rows
     * whose song the text alone identifies, labelled by Claude). Raw fields: 78.9% right. Ratchet:
     * raise [MIN_BOTH] when the parser improves; a change may not lower it.
     */
    @Test fun `parses the labelled library rows`() {
        val rows = checkNotNull(javaClass.classLoader?.getResource("youtube/titles-v1.tsv")).readText()
            .lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }.toList()
        var both = 0
        var artistOk = 0
        var titleOk = 0
        for (r in rows) {
            val (title, artist, wantArtist, wantTitle) = r
            val p = parse(title, artist)
            val want = YouTubeSongTitle.tokens(wantArtist)
            val a = YouTubeSongTitle.tokens(p.artist).let { it == want || (want.isNotEmpty() && it.containsAll(want)) }
            val t = YouTubeSongTitle.tokens(p.title) == YouTubeSongTitle.tokens(wantTitle)
            if (a) artistOk++
            if (t) titleOk++
            if (a && t) both++
        }
        val pct = { n: Int -> 100.0 * n / rows.size }
        println("youtube titles v1: ${rows.size} rows | artist %.1f%% | title %.1f%% | both %.1f%%".format(pct(artistOk), pct(titleOk), pct(both)))
        assertTrue("both right: %.1f%% < $MIN_BOTH%%".format(pct(both)), pct(both) >= MIN_BOTH)
    }

    private companion object {
        const val MIN_BOTH = 91.0
    }
}
