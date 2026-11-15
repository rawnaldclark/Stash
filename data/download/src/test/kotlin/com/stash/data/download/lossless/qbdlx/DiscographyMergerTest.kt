package com.stash.data.download.lossless.qbdlx

import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.AlbumSource
import org.junit.Assert.assertEquals
import org.junit.Test

/** Gap-fill: YouTube authoritative; Qobuz only adds album titles YT lacks. */
class DiscographyMergerTest {
    private fun yt(t: String, y: String?) = AlbumSummary("yt_$t", t, "MBV", null, y, AlbumSource.YOUTUBE)
    private fun qb(t: String, y: String?) = AlbumSummary("qb_$t", t, "MBV", null, y, AlbumSource.QOBUZ)

    @Test fun `qobuz-only album fills the gap`() {
        val out = DiscographyMerger.gapFill(listOf(yt("m b v", "2013")), listOf(qb("Loveless", "1991")))
        assertEquals(setOf("m b v", "Loveless"), out.map { it.title }.toSet())
        assertEquals(AlbumSource.QOBUZ, out.first { it.title == "Loveless" }.source)
    }

    @Test fun `title collision keeps YouTube and drops the Qobuz duplicate`() {
        val out = DiscographyMerger.gapFill(listOf(yt("Loveless", "1991")), listOf(qb("Loveless", "2021")))
        assertEquals(1, out.size)
        assertEquals(AlbumSource.YOUTUBE, out[0].source) // YT authoritative
        assertEquals("1991", out[0].year)               // YT's original year, not Qobuz's remaster date
    }

    @Test fun `empty supplement returns YT unchanged`() {
        val yt = listOf(yt("m b v", "2013"), yt("Isn't Anything", "1988"))
        assertEquals(yt, DiscographyMerger.gapFill(yt, emptyList()))
    }

    @Test fun `ordering is newest-first by year, nulls last`() {
        val out = DiscographyMerger.gapFill(
            listOf(yt("m b v", "2013"), yt("Unknown", null)),
            listOf(qb("Loveless", "1991-11-04")),
        )
        assertEquals(listOf("m b v", "Loveless", "Unknown"), out.map { it.title })
    }

    @Test fun `a Qobuz title on YouTube's Singles shelf is not added to Albums`() {
        val out = DiscographyMerger.gapFill(
            yt = listOf(yt("m b v", "2013")),
            qobuz = listOf(qb("Loveless", "1991"), qb("Creep", "1992")),
            ytSingles = listOf(yt("Creep", "1992")),
        )
        assertEquals(setOf("m b v", "Loveless"), out.map { it.title }.toSet())
    }

    @Test fun `YouTube's own albums are never removed for being on the Singles shelf too`() {
        val out = DiscographyMerger.gapFill(
            yt = listOf(yt("Creep", "1992")),
            qobuz = listOf(qb("Loveless", "1991")),
            ytSingles = listOf(yt("Creep", "1992")),
        )
        assertEquals(listOf("Creep", "Loveless"), out.map { it.title })
        assertEquals(AlbumSource.YOUTUBE, out.first { it.title == "Creep" }.source)
    }
}
