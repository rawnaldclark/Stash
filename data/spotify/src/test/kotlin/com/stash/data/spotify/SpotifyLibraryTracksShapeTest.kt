package com.stash.data.spotify

import com.stash.data.spotify.model.SpotifyTrackItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins [parseLibraryTracksResponse] to the real `fetchLibraryTracks` response
 * shape, so issue #410's fix rests on an asserted contract rather than on a
 * guess about what Spotify sends.
 *
 * The shape in `fetch_library_tracks_liked.json` is not invented: it is the
 * response documented for persisted-query hash `087278b2…f944240` by
 * independent clients using that same hash (notably
 * `fabiogaliano/hearted.music`'s `SPOTIFY-API.md`, and
 * `sceneq/myutils`' `spotify-favorites-extract.jq`, which extracts
 * `.data.me.library.tracks.items[].addedAt.isoString` from a live capture).
 * The add date sits on the **item wrapper**, a sibling of `track` — not inside
 * the track object.
 *
 * The fixture's array order is deliberately NOT newest-first (2019, 2026,
 * 2023). That is the #410 failure mode: the sync used the array index as the
 * playlist position, so an order-less `fetchLibraryTracks` could park a
 * years-old like at position 0.
 */
class SpotifyLibraryTracksShapeTest {

    private fun loadFixture(name: String): String =
        this::class.java.classLoader!!
            .getResourceAsStream("fixtures/$name")!!
            .bufferedReader()
            .use { it.readText() }

    private fun parse(json: String): List<SpotifyTrackItem> =
        parseLibraryTracksResponse(Json.parseToJsonElement(json).jsonObject)

    private fun parseLiked(): List<SpotifyTrackItem> =
        parse(loadFixture("fetch_library_tracks_liked.json"))

    @Test
    fun `walks the documented data me library tracks items path`() {
        assertEquals(3, parseLiked().size)
    }

    @Test
    fun `reads addedAt isoString off the item wrapper not the track`() {
        val byName = parseLiked().associateBy { it.track!!.name }

        assertEquals("2019-03-04T11:22:33Z", byName["Old Favourite"]?.addedAt)
        assertEquals("2026-08-01T09:00:00Z", byName["Newest Like"]?.addedAt)
        assertEquals("2023-05-15T18:30:00Z", byName["Middle Like"]?.addedAt)
    }

    @Test
    fun `fixture arrives in the wrong order so the sort is doing real work`() {
        assertEquals(
            listOf("Old Favourite", "Newest Like", "Middle Like"),
            parseLiked().map { it.track!!.name },
        )
    }

    @Test
    fun `sorts newest saved first regardless of the order Spotify returned`() {
        assertEquals(
            listOf("Newest Like", "Middle Like", "Old Favourite"),
            sortLikedSongsByAddedAtDesc(parseLiked()).map { it.track!!.name },
        )
    }

    @Test
    fun `parses track metadata alongside the add date`() {
        val newest = parseLiked().first { it.track!!.name == "Newest Like" }.track!!

        assertEquals("newestLike00000000000", newest.id)
        assertEquals("spotify:track:newestLike00000000000", newest.uri)
        assertEquals(187500L, newest.duration_ms)
        assertEquals(listOf("Artist Two"), newest.artists.map { it.name })
        assertEquals("Album Two", newest.album?.name)
        assertEquals(
            "https://i.scdn.co/image/newest640",
            newest.album?.images?.firstOrNull()?.url,
        )
    }

    @Test
    fun `keeps multiple artists in order and tolerates empty cover art`() {
        val middle = parseLiked().first { it.track!!.name == "Middle Like" }.track!!

        assertEquals(listOf("Artist Two", "Artist Three"), middle.artists.map { it.name })
        assertNotNull(middle.album)
        assertNull(middle.album?.images?.firstOrNull()?.url)
    }

    @Test
    fun `accepts the libraryTracks alias path`() {
        val aliased = parse(
            """
            {"data":{"me":{"libraryTracks":{"items":[
              {"addedAt":{"isoString":"2024-01-02T03:04:05Z"},
               "track":{"data":{"uri":"spotify:track:aliasPath00000000000","name":"Alias Path"}}}
            ]}}}}
            """.trimIndent(),
        )

        assertEquals(1, aliased.size)
        assertEquals("Alias Path", aliased.single().track?.name)
        assertEquals("2024-01-02T03:04:05Z", aliased.single().addedAt)
    }

    @Test
    fun `unrecognised shape yields no tracks instead of throwing`() {
        assertEquals(emptyList<SpotifyTrackItem>(), parse("""{"data":{"me":{"somethingElse":{}}}}"""))
        assertEquals(emptyList<SpotifyTrackItem>(), parse("""{"errors":[{"message":"nope"}]}"""))
    }

    @Test
    fun `skips non-track uris such as local files and episodes`() {
        val mixed = parse(
            """
            {"data":{"me":{"library":{"tracks":{"items":[
              {"addedAt":{"isoString":"2025-01-01T00:00:00Z"},
               "track":{"data":{"uri":"spotify:episode:podcast000000000000","name":"An Episode"}}},
              {"addedAt":{"isoString":"2025-02-02T00:00:00Z"},
               "track":{"data":{"uri":"spotify:track:realTrack00000000000","name":"A Track"}}}
            ]}}}}}
            """.trimIndent(),
        )

        assertEquals(listOf("A Track"), mixed.map { it.track!!.name })
    }
}
