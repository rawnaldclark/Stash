package com.stash.data.download.matching

import com.stash.core.data.sync.TrackMatcher
import com.stash.data.ytmusic.InnerTubeClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Signed-out searches (live 2026-09-28) return one itemSectionRenderer per row and no
 * titled "Albums" shelf. For "Nina Simone I Put a Spell on You" the top card is the music
 * video, so the album is only findable among the flat rows.
 */
class AlbumMatchExecutorFlatSearchTest {

    private fun fixture(name: String) = Json.parseToJsonElement(
        this::class.java.classLoader!!.getResourceAsStream("fixtures/$name")!!
            .bufferedReader().use { it.readText() },
    ).jsonObject

    @Test
    fun `finds the album among signed-out flat rows when the top card is a video`() {
        val executor = AlbumMatchExecutor(mock<InnerTubeClient>(), TrackMatcher())

        val browseId = executor.extractAlbumBrowseId(
            fixture("innertube_search_signed_out_album.json"),
            targetAlbum = "I Put a Spell on You",
            targetArtist = "Nina Simone",
        )

        assertEquals("MPREb_IIWn9LYU83g", browseId)
    }

    /**
     * YouTube Music keeps an album's explicit and clean editions as two albums of
     * one title. `innertube_search_album_editions.json` is the live flat album row
     * for The Life Of Pablo (explicit edition MPREb_ZIbQK9RFLQY, badged; search
     * 2026-10-26), listed after a copy of it standing for the clean edition: badge
     * removed and the clean edition's real browseId (MPREb_GDKC8gIBAVI), since the
     * probe's search didn't list both. The clean one comes first, as the first
     * match is what was always taken.
     */
    @Test
    fun `picks the album's explicit edition unless the track is known clean`() {
        val executor = AlbumMatchExecutor(mock<InnerTubeClient>(), TrackMatcher())
        fun edition(explicit: Boolean?) = executor.extractAlbumBrowseId(
            fixture("innertube_search_album_editions.json"),
            targetAlbum = "The Life Of Pablo",
            targetArtist = "Kanye West",
            targetExplicit = explicit,
        )

        assertEquals("MPREb_ZIbQK9RFLQY", edition(true))
        assertEquals("MPREb_ZIbQK9RFLQY", edition(null))
        assertEquals("MPREb_GDKC8gIBAVI", edition(false))
    }
}
