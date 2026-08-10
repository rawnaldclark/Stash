package com.stash.data.spotify

import com.stash.data.spotify.model.SpotifyTrackItem
import com.stash.data.spotify.model.SpotifyTrackObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Order contract for Spotify Liked Songs (issue #410).
 *
 * The sync stores list index as playlist position and the Liked Songs screen
 * renders `ORDER BY position ASC`, so whatever order leaves this module is
 * exactly what the user sees. `fetchLibraryTracks` is called without an
 * `order` variable, so these tests pin that the order comes from the add date
 * Spotify reports rather than from the position in the response.
 */
class SpotifyLikedSongsOrderTest {

    private fun wrapper(json: String): JsonObject =
        Json.parseToJsonElement(json) as JsonObject

    private fun item(name: String, addedAt: String?) = SpotifyTrackItem(
        track = SpotifyTrackObject(
            id = name.lowercase(),
            name = name,
            uri = "spotify:track:${name.lowercase()}",
        ),
        addedAt = addedAt,
    )

    private val List<SpotifyTrackItem>.names: List<String>
        get() = map { it.track!!.name }

    // ── parseLibraryAddedAt ──────────────────────────────────────────────

    @Test
    fun `reads the nested addedAt isoString the library response carries`() {
        val added = parseLibraryAddedAt(
            wrapper("""{"addedAt":{"isoString":"2024-05-03T12:00:00Z"},"track":{}}""")
        )

        assertEquals("2024-05-03T12:00:00Z", added)
    }

    @Test
    fun `falls back to a flat addedAt string and to the web api added_at`() {
        assertEquals(
            "2024-05-03T12:00:00Z",
            parseLibraryAddedAt(wrapper("""{"addedAt":"2024-05-03T12:00:00Z"}""")),
        )
        assertEquals(
            "2022-03-01T09:15:00Z",
            parseLibraryAddedAt(wrapper("""{"added_at":"2022-03-01T09:15:00Z"}""")),
        )
    }

    @Test
    fun `yields null instead of throwing when no add date is present`() {
        assertNull(parseLibraryAddedAt(wrapper("""{"track":{"name":"Euphoria"}}""")))
        // A shape that moved under us must not take the whole item down.
        assertNull(parseLibraryAddedAt(wrapper("""{"addedAt":{"unexpected":1}}""")))
    }

    // ── sortLikedSongsByAddedAtDesc ──────────────────────────────────────

    @Test
    fun `orders newest saved first regardless of the order the endpoint returned`() {
        // #410 exactly: a track saved two years ago handed back at index 0.
        val fetched = listOf(
            item("Euphoria", "2024-05-03T12:00:00Z"),
            item("Saved Yesterday", "2026-08-01T10:00:00Z"),
            item("Saved Long Ago", "2022-03-01T09:15:00Z"),
        )

        val ordered = sortLikedSongsByAddedAtDesc(fetched)

        assertEquals(
            listOf("Saved Yesterday", "Euphoria", "Saved Long Ago"),
            ordered.names,
        )
    }

    @Test
    fun `leaves the page untouched when no item reports an add date`() {
        val fetched = listOf(item("First", null), item("Second", null))

        val ordered = sortLikedSongsByAddedAtDesc(fetched)

        // Same instance: an endpoint that stops reporting add dates must
        // degrade to the previous behaviour, not to a scrambled library.
        assertSame(fetched, ordered)
    }

    @Test
    fun `sinks undated and unparseable items to the end in their original order`() {
        val fetched = listOf(
            item("No Date A", null),
            item("Older", "2022-03-01T09:15:00Z"),
            item("Unparseable", "last tuesday"),
            item("Newer", "2026-08-01T10:00:00Z"),
            item("No Date B", null),
        )

        val ordered = sortLikedSongsByAddedAtDesc(fetched)

        assertEquals(
            listOf("Newer", "Older", "No Date A", "Unparseable", "No Date B"),
            ordered.names,
        )
    }
}
