package com.stash.data.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser-contract tests for [parseWebApiPlaylistPage].
 *
 * The parser reads Spotify Web API `/v1/playlists/{id}/tracks` JSON and
 * must thread the `explicit` flag and `external_ids.isrc` through to the
 * [com.stash.data.spotify.model.SpotifyTrackObject] DTO so downstream
 * matching can use them.
 */
class SpotifyTrackParserTest {

    private fun loadFixture(name: String): String =
        this::class.java.classLoader!!
            .getResourceAsStream("fixtures/$name")!!
            .bufferedReader()
            .use { it.readText() }

    @Test
    fun `parses explicit flag and ISRC from webapi playlist response`() {
        val body = loadFixture("webapi_playlist_tracks.json")

        val (tracks, nextUrl) = parseWebApiPlaylistPage(body)

        assertNull("next URL should be null when response has next:null", nextUrl)
        assertEquals(3, tracks.size)

        val smoothCriminal = tracks[0].track!!
        assertEquals("Smooth Criminal - 2012 Remaster", smoothCriminal.name)
        assertFalse(
            "Smooth Criminal 2012 Remaster is not explicit",
            smoothCriminal.explicit,
        )
        assertEquals("USSM11200115", smoothCriminal.isrc)

        val explicitTrack = tracks[1].track!!
        assertTrue(
            "Track flagged explicit:true in JSON must parse as explicit",
            explicitTrack.explicit,
        )
        assertEquals("USUG11900001", explicitTrack.isrc)
    }

    @Test
    fun `missing external_ids yields null ISRC`() {
        val body = loadFixture("webapi_playlist_tracks.json")

        val (tracks, _) = parseWebApiPlaylistPage(body)
        val legacy = tracks[2].track!!

        assertNull(
            "track JSON with no external_ids must yield null ISRC, not a blank string",
            legacy.isrc,
        )
        assertFalse(legacy.explicit)
    }
}
