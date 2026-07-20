package com.stash.data.spotify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser-contract tests for [parseLibraryPage].
 *
 * The parser reads Spotify GraphQL `libraryV3` JSON. Three properties
 * matter for issues #48/#26/#80/#136 (folder playlists never synced):
 *
 *  1. Folder items must surface their URIs so the fetch worker can
 *     descend into them — not be silently skipped.
 *  2. The RAW item count must be reported so pagination advances past
 *     pages diluted by folders/pseudo-playlists instead of mistaking a
 *     post-filter short page for the end of the library.
 *  3. Playlist parsing itself must be unchanged (id, name, owner, art,
 *     track count).
 */
class SpotifyLibraryParserTest {

    private fun loadFixture(name: String): String =
        this::class.java.classLoader!!
            .getResourceAsStream("fixtures/$name")!!
            .bufferedReader()
            .use { it.readText() }

    private fun parseFixture(name: String) =
        parseLibraryPage(Json.parseToJsonElement(loadFixture(name)).jsonObject)

    @Test
    fun `parses playlists and skips pseudo-playlist and NotFound rows`() {
        val page = parseFixture("libraryv3_with_folders.json")

        assertEquals(2, page.playlists.size)
        val first = page.playlists[0]
        assertEquals("6wyGbrLCQHiRK1pESUTo8Z", first.id)
        assertEquals("Top Level Playlist", first.name)
        assertEquals("testuser", first.owner.id)
        assertEquals("https://i.scdn.co/image/abc123", first.images?.firstOrNull()?.url)
        assertEquals(42, first.tracks?.total)

        val second = page.playlists[1]
        assertEquals("2v6l94wkQJwa2EU3rcJf0n", second.id)
        assertEquals(7, second.tracks?.total)
    }

    @Test
    fun `surfaces folder URIs instead of dropping folder items`() {
        val page = parseFixture("libraryv3_with_folders.json")

        assertEquals(
            listOf("spotify:user:testuser:folder:8d1d2954cb43e146"),
            page.folderUris,
        )
    }

    @Test
    fun `reports the RAW item count for pagination, not the post-filter count`() {
        val page = parseFixture("libraryv3_with_folders.json")

        // 5 raw items (1 pseudo + 2 playlists + 1 folder + 1 NotFound) —
        // a paginator using playlists.size (2) would stop a 50-item walk
        // at the first folder-heavy page.
        assertEquals(5, page.rawItemCount)
        assertTrue(page.isComplete)
    }

    @Test
    fun `empty or malformed response parses as an empty terminal page`() {
        val page = parseLibraryPage(
            Json.parseToJsonElement("""{"data":{"me":{}}}""").jsonObject,
        )
        assertEquals(0, page.rawItemCount)
        assertTrue(page.playlists.isEmpty())
        assertTrue(page.folderUris.isEmpty())
        assertFalse(page.isComplete)
    }

    @Test
    fun `GraphQL errors and malformed playlist rows mark inventory incomplete`() {
        val withErrors = parseLibraryPage(
            Json.parseToJsonElement(
                """{"errors":[{"message":"partial"}],"data":{"me":{"libraryV3":{"items":[]}}}}""",
            ).jsonObject,
        )
        val malformedPlaylist = parseLibraryPage(
            Json.parseToJsonElement(
                """{"data":{"me":{"libraryV3":{"items":[{"item":{"data":{"__typename":"Playlist"}}}]}}}}""",
            ).jsonObject,
        )

        assertFalse(withErrors.isComplete)
        assertFalse(malformedPlaylist.isComplete)
    }
}
