package com.stash.data.ytmusic

import com.stash.data.ytmusic.model.SearchResultSection
import com.stash.data.ytmusic.model.TrackSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [parseTrackSummaryFromListItem] against rows trimmed from live InnerTube
 * search responses (2026-09-27, query "mii channel"). YouTube Music puts the
 * album, length and view count in the same subtitle as the artist, so joining
 * every run made the artist "Grimm's VGM, 845 views" and every length 0:00.
 */
class SongRowSubtitleParserTest {

    private fun fixture(name: String): String =
        this::class.java.classLoader!!.getResourceAsStream("fixtures/$name")!!
            .bufferedReader().use { it.readText() }

    /** Unfiltered (flat, #268) rows: Song • artist | Video • channel • views. */
    private val flatSongs: List<TrackSummary> by lazy {
        parseFlatSearchSections(Json.parseToJsonElement(fixture("search_flat_live_rows.json")).jsonArray)
            .filterIsInstance<SearchResultSection.Songs>().single().tracks
    }

    /** Songs-filter shelf rows: artist(s) • album • length. */
    private val shelfSongs: List<TrackSummary> by lazy {
        parseSongsShelf(Json.parseToJsonElement(fixture("search_songs_shelf_live.json")).jsonObject)
    }

    private fun List<TrackSummary>.byId(videoId: String) = single { it.videoId == videoId }

    @Test fun `flat song row - artist only, no length`() {
        val t = flatSongs.byId("Ba1mzFtR_Zk")
        assertEquals("Insaneintherainmusic", t.artist)
        assertEquals(0.0, t.durationSeconds, 0.0)
        assertNull("flexColumns[2] is '5.7M plays', not an album", t.album)
    }

    @Test fun `flat video row - channel is the artist, view count is dropped`() {
        val t = flatSongs.byId("3g4kgJ1Ye_E")
        assertEquals("Grimm's VGM", t.artist)
        assertEquals(0.0, t.durationSeconds, 0.0)
    }

    @Test fun `flat multi-artist row joins every linked artist`() {
        assertEquals("The Deku Trio, GameChops, Unplugged", flatSongs.byId("jXX93PprOMQ").artist)
    }

    @Test fun `songs shelf row - artist, album and length from the subtitle`() {
        val t = shelfSongs.byId("Ba1mzFtR_Zk")
        assertEquals("Insaneintherainmusic", t.artist)
        assertEquals("Mii Channel Theme", t.album)
        assertEquals(313.0, t.durationSeconds, 0.0)
    }

    @Test fun `songs shelf multi-artist row`() {
        val t = shelfSongs.byId("jXX93PprOMQ")
        assertEquals("The Deku Trio, GameChops, Unplugged", t.artist)
        assertEquals("Mii Channel Jazz", t.album)
        assertEquals(163.0, t.durationSeconds, 0.0)
    }

    @Test fun `songs shelf row whose artist has no link falls back to the first group`() {
        val t = shelfSongs.byId("-fKJLtBYCAo")
        assertEquals("Nuovi Venti Saxophone Quartet", t.artist)
        assertEquals(81.0, t.durationSeconds, 0.0)
    }

    @Test fun `an album titled like a length is not the length`() {
        // JAY-Z's album "4:44" is a linked subtitle group; the real length is the unlinked 3:52.
        val t = shelfSongs.byId("JRW5TbVApFw")
        assertEquals("JAŸ-Z", t.artist)
        assertEquals("4:44", t.album)
        assertEquals(232.0, t.durationSeconds, 0.0)
    }

    @Test fun `an unlinked co-artist between linked ones is kept`() {
        // "Yeahman(UC), Hajna & Mina Shankha(UC)": Hajna has no artist page.
        assertEquals("Yeahman, Hajna, Mina Shankha", shelfSongs.byId("L3HuPWCaIrU").artist)
    }

    @Test fun `podcast episode takes the show as its artist, not the date`() {
        assertEquals("OC Podcast", flatSongs.byId("RY_y2mtHl5c").artist)
    }

    @Test fun `a view count is never the artist`() {
        // The live Grimm's VGM row with its channel run removed, so the view count
        // is the first subtitle group: the one place the view-count check decides.
        val row = Json.parseToJsonElement(
            """
            {"playlistItemData":{"videoId":"3g4kgJ1Ye_E"},"flexColumns":[
              {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Mii Channel Theme Cover"}]}}},
              {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
                {"text":"Video"},{"text":" • "},{"text":"845 views"}]}}}]}
            """,
        ).jsonObject
        assertEquals("", parseTrackSummaryFromListItem(row)!!.artist)
    }

    @Test fun `a first group that only links an album is never the artist`() {
        // Hand-built (no live row has this today): "Song" • <album>@MPREb_ • "3:00".
        val row = Json.parseToJsonElement(
            """
            {"playlistItemData":{"videoId":"handBuilt01"},"flexColumns":[
              {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Someday"}]}}},
              {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
                {"text":"Song"},{"text":" • "},
                {"text":"Is This It","navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_handBuilt01"}}},
                {"text":" • "},{"text":"3:00"}]}}}]}
            """,
        ).jsonObject
        val t = parseTrackSummaryFromListItem(row, fallbackArtist = "The Strokes")!!
        assertEquals("The Strokes", t.artist)
        assertEquals("Is This It", t.album)
        assertEquals(180.0, t.durationSeconds, 0.0)
    }

    @Test fun `fixed-column row still reads its length from fixedColumns`() {
        val shelf = Json.parseToJsonElement(fixture("search_artist.json")).jsonObject
            .navigatePath("contents", "tabbedSearchResultsRenderer", "tabs")!!.jsonArray[0].jsonObject
            .navigatePath("tabRenderer", "content", "sectionListRenderer", "contents")!!.jsonArray[1].jsonObject
            .navigatePath("musicShelfRenderer") as JsonObject
        val t = parseSongsShelf(shelf).first()
        assertEquals("Lootpack, Madlib", t.artist)
        assertEquals("Soundpieces: Da Antidote", t.album)
        assertEquals(201.0, t.durationSeconds, 0.0)
    }
}
