package com.stash.data.spotify

import com.stash.core.auth.TokenManager
import com.stash.core.auth.spotify.SpotifyAuthManager
import com.stash.core.model.SyncResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sync's REFRESH mode mirrors a playlist DOWN to whatever the fetch
 * returned, so a fetch that quietly stops early deletes real data. These pin
 * the two Spotify walks that used to do exactly that:
 *
 *  - `fetchPlaylist` (GraphQL): a page after the first failing (HTTP 429/5xx,
 *    or a 200 carrying only `errors`) ended the walk and the pages read so far
 *    came back as an ordinary, complete success.
 *  - `fetchLibraryTracks` (Liked Songs): one filtered item made a full page
 *    parse short, so the sync's `parsed < pageSize` test ended the walk there.
 */
class SpotifyApiClientPaginationTest {

    /** Response per (operation, offset); anything unlisted answers HTTP 500. */
    private val pages = mutableMapOf<Pair<String, Int>, Pair<Int, String>>()

    /** Web API `/v1/playlists/{id}/tracks` response per playlist id; unlisted answers HTTP 500. */
    private val webApiPages = mutableMapOf<String, Pair<Int, String>>()

    private val tokenManager = mockk<TokenManager> {
        coEvery { getSpotifyAccessToken() } returns "access-token"
        coEvery { getSpotifyClientId() } returns "client-id"
        coEvery { forceRefreshSpotifyAccessToken() } returns null
    }

    private val authManager = mockk<SpotifyAuthManager> {
        every { getClientVersion() } returns "1.2.3.4.g0"
        coEvery { getClientToken("client-id") } returns "client-token"
        // No client_credentials token: the Web API prong is skipped, so every
        // playlist fetch goes through the GraphQL walk under test.
        coEvery { getClientCredentialsToken() } returns null
        every { scrapePersistedQueryHash(any(), any()) } returns null
    }

    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        val url = chain.request().url
        val (code, body) = if (url.encodedPath.startsWith("/v1/playlists/")) {
            webApiPages[url.pathSegments[2]] ?: (500 to """{"error":"boom"}""")
        } else {
            val operation = url.queryParameter("operationName").orEmpty()
            val offset = Regex(""""offset":(\d+)""")
                .find(url.queryParameter("variables").orEmpty())!!.groupValues[1].toInt()
            pages[operation to offset] ?: (500 to """{"error":"boom"}""")
        }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()

    private val client = SpotifyApiClient(http, tokenManager, authManager)

    // ── fetchPlaylist (GraphQL) ──────────────────────────────────────────

    @Test fun `a failed page after the first marks the playlist partial`() = runBlocking {
        pages["fetchPlaylist" to 0] = 200 to playlistPage(range = 0 until 100, totalCount = 250)
        // offset 100 is unlisted: HTTP 500, which executeGraphQL turns into null.

        val result = client.getPlaylistTracks("pl") as SyncResult.Success

        assertEquals(100, result.data.tracks.size)
        assertTrue("a truncated walk must not pass for the whole playlist", result.data.partial)
    }

    @Test fun `an errors-only page after the first marks the playlist partial`() = runBlocking {
        pages["fetchPlaylist" to 0] = 200 to playlistPage(range = 0 until 100, totalCount = 250)
        pages["fetchPlaylist" to 100] = 200 to """{"errors":[{"message":"rate limited"}]}"""

        val result = client.getPlaylistTracks("pl") as SyncResult.Success

        assertEquals(100, result.data.tracks.size)
        assertTrue(result.data.partial)
    }

    @Test fun `a walk short of Spotify's own totalCount is partial`() = runBlocking {
        pages["fetchPlaylist" to 0] = 200 to playlistPage(range = 0 until 40, totalCount = 300)

        val result = client.getPlaylistTracks("pl") as SyncResult.Success

        assertTrue(result.data.partial)
    }

    @Test fun `a complete walk is not partial`() = runBlocking {
        pages["fetchPlaylist" to 0] = 200 to playlistPage(range = 0 until 100, totalCount = 130)
        pages["fetchPlaylist" to 100] = 200 to playlistPage(range = 100 until 130, totalCount = 130)

        val result = client.getPlaylistTracks("pl") as SyncResult.Success

        assertEquals(130, result.data.tracks.size)
        assertFalse(result.data.partial)
    }

    @Test fun `a filtered item on a full page does not end the walk`() = runBlocking {
        pages["fetchPlaylist" to 0] = 200 to playlistPage(range = 0 until 100, totalCount = 150, localAt = 7)
        pages["fetchPlaylist" to 100] = 200 to playlistPage(range = 100 until 150, totalCount = 150)

        val result = client.getPlaylistTracks("pl") as SyncResult.Success

        assertEquals(149, result.data.tracks.size)
        assertFalse(result.data.partial)
    }

    // ── countVerified: the proof the sync needs to skip a playlist later ──

    @Test fun `a walk that saw every item Spotify counts is count-verified`() = runBlocking {
        pages["fetchPlaylist" to 0] = 200 to playlistPage(range = 0 until 100, totalCount = 130)
        pages["fetchPlaylist" to 100] = 200 to playlistPage(range = 100 until 130, totalCount = 130)

        val result = client.getPlaylistTracks("pl") as SyncResult.Success

        assertTrue(result.data.countVerified)
    }

    @Test fun `a filtered item still counts toward the verified walk`() = runBlocking {
        // A local file is dropped by the parser but was walked: the raw count matches Spotify's.
        pages["fetchPlaylist" to 0] = 200 to playlistPage(range = 0 until 100, totalCount = 150, localAt = 7)
        pages["fetchPlaylist" to 100] = 200 to playlistPage(range = 100 until 150, totalCount = 150)

        val result = client.getPlaylistTracks("pl") as SyncResult.Success

        assertTrue(result.data.countVerified)
    }

    @Test fun `a walk inside the 95 percent tolerance is complete but not count-verified`() = runBlocking {
        // 97 of 100: fine to mirror (not partial), not proof enough to skip next time.
        pages["fetchPlaylist" to 0] = 200 to playlistPage(range = 0 until 97, totalCount = 100)

        val result = client.getPlaylistTracks("pl") as SyncResult.Success

        assertFalse(result.data.partial)
        assertFalse(result.data.countVerified)
    }

    @Test fun `a walk with no count from Spotify is not count-verified`() = runBlocking {
        pages["fetchPlaylist" to 0] = 200 to playlistPage(range = 0 until 30, totalCount = null)

        val result = client.getPlaylistTracks("pl") as SyncResult.Success

        assertFalse(result.data.partial)
        assertFalse(result.data.countVerified)
    }

    @Test fun `a partial walk is never count-verified`() = runBlocking {
        pages["fetchPlaylist" to 0] = 200 to playlistPage(range = 0 until 100, totalCount = 200)
        // offset 100 is unlisted: HTTP 500 → the walk is partial.

        val result = client.getPlaylistTracks("pl") as SyncResult.Success

        assertTrue(result.data.partial)
        assertFalse(result.data.countVerified)
    }

    // ── revisionId: what the sync cross-checks against the listing's ─────

    @Test fun `the walk reports the revision its first page carried`() = runBlocking {
        pages["fetchPlaylist" to 0] = 200 to playlistPage(range = 0 until 100, totalCount = 130, revisionId = "AAAAAFirst")
        pages["fetchPlaylist" to 100] = 200 to playlistPage(range = 100 until 130, totalCount = 130, revisionId = "AAAAALater")

        val result = client.getPlaylistTracks("pl") as SyncResult.Success

        assertEquals("the version the walk started from", "AAAAAFirst", result.data.revisionId)
    }

    @Test fun `a page with no revision reports none`() = runBlocking {
        pages["fetchPlaylist" to 0] = 200 to playlistPage(range = 0 until 30, totalCount = 30)

        val result = client.getPlaylistTracks("pl") as SyncResult.Success

        assertNull(result.data.revisionId)
        assertTrue("the page itself still parses", result.data.countVerified)
    }

    @Test fun `a failed first page is still an error`() = runBlocking {
        // Nothing listed: every page answers HTTP 500.
        assertTrue(client.getPlaylistTracks("pl") is SyncResult.Error)
    }

    // ── No songs: an answer only when Spotify's own count says so (#747) ──

    @Test fun `an empty playlist is read fine, with no songs`() = runBlocking {
        pages["fetchPlaylist" to 0] = 200 to playlistPage(range = 0 until 0, totalCount = 0, revisionId = "AAAAAEmpty")

        val result = client.getPlaylistTracks("pl") as SyncResult.Success

        assertTrue(result.data.tracks.isEmpty())
        assertFalse(result.data.partial)
        assertTrue("its count proves it complete, so a later sync can skip it", result.data.countVerified)
        assertEquals("AAAAAEmpty", result.data.revisionId)
    }

    @Test fun `an empty playlist answered with its count and no song list is read fine too`() = runBlocking {
        pages["fetchPlaylist" to 0] = 200 to """{"data":{"playlistV2":{"content":{"totalCount":0}}}}"""

        val result = client.getPlaylistTracks("pl") as SyncResult.Success

        assertTrue(result.data.tracks.isEmpty())
        assertTrue(result.data.countVerified)
    }

    @Test fun `a playlist of only local files and episodes is read fine, with no songs`() = runBlocking {
        pages["fetchPlaylist" to 0] = 200 to playlistPageOf(
            items = listOf(trackData(0, local = true), EPISODE_DATA, trackData(2, local = true)),
            totalCount = 3,
        )

        val result = client.getPlaylistTracks("pl") as SyncResult.Success

        assertTrue(result.data.tracks.isEmpty())
        assertFalse(result.data.partial)
    }

    @Test fun `an empty page while Spotify still counts songs is an error`() = runBlocking {
        pages["fetchPlaylist" to 0] = 200 to playlistPage(range = 0 until 0, totalCount = 40)

        assertTrue("must never mirror a playlist down to nothing", client.getPlaylistTracks("pl") is SyncResult.Error)
    }

    @Test fun `an empty page with no count is an error`() = runBlocking {
        pages["fetchPlaylist" to 0] = 200 to playlistPage(range = 0 until 0, totalCount = null)

        assertTrue(client.getPlaylistTracks("pl") is SyncResult.Error)
    }

    @Test fun `no song list is an error unless Spotify counts zero with no errors`() = runBlocking {
        pages["fetchPlaylist" to 0] = 200 to """{"data":{"playlistV2":{"content":{"totalCount":40}}}}"""
        assertTrue(client.getPlaylistTracks("pl") is SyncResult.Error)

        pages["fetchPlaylist" to 0] = 200 to
            """{"data":{"playlistV2":{"content":{"totalCount":0}}},"errors":[{"message":"rate limited"}]}"""
        assertTrue(client.getPlaylistTracks("pl") is SyncResult.Error)
    }

    @Test fun `songs in a shape the parser no longer reads are an error, not an empty playlist`() = runBlocking {
        // Spotify counts 3 and sends 3, but none is a local file or an episode: songs
        // the parser couldn't read (renamed type, missing data, missing uri).
        val renamedType = """{"__typename":"TrackV2","uri":"spotify:track:t1","name":"One"}"""
        val noUri = """{"__typename":"Track","name":"Two"}"""
        pages["fetchPlaylist" to 0] = 200 to
            """{"data":{"playlistV2":{"content":{"totalCount":3,"items":[""" +
            """{"itemV2":{"data":$renamedType}},{"itemV2":{"data":$noUri}},{"itemV3":{"data":{}}}]}}}}"""

        assertTrue("must never mirror a playlist down to nothing", client.getPlaylistTracks("pl") is SyncResult.Error)
    }

    @Test fun `an empty list beside errors is an error`() = runBlocking {
        pages["fetchPlaylist" to 0] = 200 to
            """{"data":{"playlistV2":{"content":{"totalCount":0,"items":[]}}},"errors":[{"message":"rate limited"}]}"""

        assertTrue(client.getPlaylistTracks("pl") is SyncResult.Error)
    }

    @Test fun `a later page answering with a count of 0 and no list leaves the walk partial`() = runBlocking {
        pages["fetchPlaylist" to 0] = 200 to playlistPage(range = 0 until 100, totalCount = 250)
        pages["fetchPlaylist" to 100] = 200 to """{"data":{"playlistV2":{"content":{"totalCount":0}}}}"""

        val result = client.getPlaylistTracks("pl") as SyncResult.Success

        assertEquals(100, result.data.tracks.size)
        assertTrue("never mirrored down to the first page", result.data.partial)
        assertFalse(result.data.countVerified)
    }

    @Test fun `a later page's count of 0 can't vouch for a short walk`() = runBlocking {
        pages["fetchPlaylist" to 0] = 200 to playlistPage(range = 0 until 100, totalCount = 250)
        pages["fetchPlaylist" to 100] = 200 to playlistPage(range = 0 until 0, totalCount = 0)

        val result = client.getPlaylistTracks("pl") as SyncResult.Success

        assertTrue("the first page's count of 250 stands", result.data.partial)
    }

    @Test fun `only local files on the first page, then a failed page, is an error`() = runBlocking {
        pages["fetchPlaylist" to 0] = 200 to playlistPageOf(items = (0 until 100).map { trackData(it, local = true) }, totalCount = 150)
        // offset 100 is unlisted: HTTP 500, so the walk is partial with nothing read.

        assertTrue(client.getPlaylistTracks("pl") is SyncResult.Error)
    }

    // ── The Web API prong asks for no count, so an empty answer goes to GraphQL ──

    @Test fun `an empty Web API answer is confirmed by the GraphQL count`() = runBlocking {
        coEvery { authManager.getClientCredentialsToken() } returns "cc-token"
        webApiPages["pl"] = 200 to """{"items":[],"next":null}"""
        pages["fetchPlaylist" to 0] = 200 to playlistPage(range = 0 until 0, totalCount = 0)

        val result = client.getPlaylistTracks("pl") as SyncResult.Success

        assertTrue(result.data.tracks.isEmpty())
        assertTrue("only the GraphQL walk can verify a count", result.data.countVerified)
    }

    @Test fun `an empty Web API answer GraphQL can't confirm is an error`() = runBlocking {
        coEvery { authManager.getClientCredentialsToken() } returns "cc-token"
        // A page that doesn't parse ends the Web API walk with nothing, like an empty one.
        webApiPages["pl"] = 200 to "<html>not json</html>"
        pages["fetchPlaylist" to 0] = 200 to playlistPage(range = 0 until 0, totalCount = 25)

        assertTrue(client.getPlaylistTracks("pl") is SyncResult.Error)
    }

    @Test fun `a Web API answer with songs is still taken as it is`() = runBlocking {
        coEvery { authManager.getClientCredentialsToken() } returns "cc-token"
        webApiPages["pl"] = 200 to
            """{"items":[{"track":{"id":"t1","name":"One","uri":"spotify:track:t1","artists":[{"id":"a","name":"A"}]}}],"next":null}"""

        val result = client.getPlaylistTracks("pl") as SyncResult.Success

        assertEquals(listOf("t1"), result.data.tracks.map { it.track?.id })
    }

    // ── fetchLibraryTracks (Liked Songs) ─────────────────────────────────

    @Test fun `a liked page with a filtered item reports the raw count`() = runBlocking {
        pages["fetchLibraryTracks" to 0] = 200 to likedPage(range = 0 until 50, totalCount = 1200, localAt = 3)

        val page = (client.getLikedSongs(limit = 50, offset = 0) as SyncResult.Success).data

        assertEquals(49, page.tracks.size)
        assertEquals("pagination must see the full page", 50, page.rawItemCount)
        assertEquals(1200, page.totalCount)
    }

    @Test fun `a liked page whose every item is filtered is still a success`() = runBlocking {
        pages["fetchLibraryTracks" to 50] = 200 to likedPage(range = 50 until 52, totalCount = 60, localAt = null, allLocal = true)

        val page = (client.getLikedSongs(limit = 50, offset = 50) as SyncResult.Success).data

        assertEquals(0, page.tracks.size)
        assertEquals(2, page.rawItemCount)
    }

    @Test fun `an empty liked page is Empty`() = runBlocking {
        pages["fetchLibraryTracks" to 100] = 200 to likedPage(range = 100 until 100, totalCount = 100)

        assertTrue(client.getLikedSongs(limit = 50, offset = 100) is SyncResult.Empty)
    }

    @Test fun `an unrecognised liked response is an error, not the end of the library`() = runBlocking {
        pages["fetchLibraryTracks" to 50] = 200 to """{"errors":[{"message":"rate limited"}]}"""

        assertTrue(client.getLikedSongs(limit = 50, offset = 50) is SyncResult.Error)
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    private companion object {
        /** A podcast episode saved to a playlist: walked, then left out by the parser. */
        const val EPISODE_DATA = """{"__typename":"Episode","uri":"spotify:episode:ep1","name":"An episode"}"""
    }

    private fun trackData(i: Int, local: Boolean): String {
        val uri = if (local) "spotify:local:Artist:Album:Local+$i:200" else "spotify:track:track$i"
        return """{"__typename":"Track","uri":"$uri","name":"Track $i",""" +
            """"artists":{"items":[{"uri":"spotify:artist:a$i","profile":{"name":"Artist $i"}}]}}"""
    }

    /**
     * A `fetchPlaylist` page; [totalCount] null leaves the count out, as an older
     * shape would, and [revisionId] null leaves the playlist's version out.
     */
    private fun playlistPage(range: IntRange, totalCount: Int?, localAt: Int? = null, revisionId: String? = null): String =
        playlistPageOf(range.map { i -> trackData(i, local = i == localAt) }, totalCount, revisionId)

    /** A `fetchPlaylist` page holding [items], each an item's `data` object. */
    private fun playlistPageOf(items: List<String>, totalCount: Int?, revisionId: String? = null): String {
        val list = items.joinToString(",") { """{"itemV2":{"data":$it}}""" }
        val count = totalCount?.let { """"totalCount":$it,""" }.orEmpty()
        val revision = revisionId?.let { """"revisionId":"$it",""" }.orEmpty()
        return """{"data":{"playlistV2":{$revision"content":{$count"items":[$list]}}}}"""
    }

    private fun likedPage(
        range: IntRange,
        totalCount: Int,
        localAt: Int? = null,
        allLocal: Boolean = false,
    ): String {
        val items = range.joinToString(",") { i ->
            """{"addedAt":{"isoString":"2026-01-01T00:00:00Z"},""" +
                """"track":{"data":${trackData(i, local = allLocal || i == localAt)}}}"""
        }
        return """{"data":{"me":{"library":{"tracks":{"totalCount":$totalCount,"items":[$items]}}}}}"""
    }
}
