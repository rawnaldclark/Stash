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
}
