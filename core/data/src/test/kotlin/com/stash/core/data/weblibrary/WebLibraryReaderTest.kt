package com.stash.core.data.weblibrary

import com.stash.core.data.weblink.handoff.WireSong
import com.stash.core.model.weblink.SongRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The app's reader of `stash-web-library` v1 (library-file-v1 "Reading rules") and the importer's merge rules, pure. */
class WebLibraryReaderTest {
    private val now = 1_759_838_400_000L

    private fun read(json: String) = WebLibraryReader.read(json, now)

    @Test fun `the app's own golden export reads back whole`() {
        val c = read(File("src/test/resources/weblibrary/golden-v1.json").readText())
        assertEquals(listOf("Halo", "Pink + White", "Nights"), c.likes.map { it.song.title })
        assertEquals(listOf("app-2", "app-6"), c.playlists.map { it.id })
        assertEquals("Fw12ab34", c.playlists[1].follow!!.id)
        assertEquals(listOf("Ivy", "Nights", "Pink + White"), c.history.map { it.song.title })
        assertEquals("USUM71607009", c.likes[1].song.isrc)
        assertEquals("uxpDa-c-4Mc", c.likes[1].song.youtubeId)
    }

    @Test fun `a file that isn't one, or comes from a newer Stash, is refused with the words the screen shows`() {
        assertEquals(WebLibraryReader.NOT_A_BACKUP, assertThrows(WebLibraryReadException::class.java) { read("not json") }.message)
        assertEquals(WebLibraryReader.NOT_A_BACKUP, assertThrows(WebLibraryReadException::class.java) { read("""{"kind":"other","v":1}""") }.message)
        assertEquals(WebLibraryReader.NOT_A_BACKUP, assertThrows(WebLibraryReadException::class.java) { read("""{"kind":"stash-web-library","v":"1"}""") }.message)
        assertEquals(WebLibraryReader.NEWER, assertThrows(WebLibraryReadException::class.java) { read("""{"kind":"stash-web-library","v":2}""") }.message)
        assertEquals(WebLibraryReader.TOO_BIG, assertThrows(WebLibraryReadException::class.java) {
            WebLibraryReader.read(java.io.ByteArrayInputStream(ByteArray(WebLibraryReader.MAX_BYTES + 1) { ' '.code.toByte() }), now)
        }.message)
    }

    @Test fun `missing parts are empty, bad entries are dropped, values are rebuilt`() {
        val c = read(
            """{"kind":"stash-web-library","v":1,"settings":{"volume":1},"likes":[
                {"item":{"title":"  A‮song ","artist":"X","isrc":"usabc1234567","durationMs":-5,"refs":{"youtube":"aaaaaaaaaaa","bad key!":"x"},
                  "artwork":[{"url":"http://insecure/a.jpg"},{"url":"https://i.scdn.co/image/b"}]},"likedAt":"1"},
                {"item":{"title":"","artist":"Y"},"likedAt":5},
                "junk"],
              "playlists":[
                {"id":"p1","name":"One","items":[{"title":"S","artist":"A"},{"artist":"no title"}],"follow":{"id":"short","version":1}},
                {"id":"p1","name":"Duplicate id","items":[]},
                {"id":"bad id","name":"x","items":[]},
                {"id":"p2","name":"   ","items":[]},
                {"id":"p3","name":"No items"}],
              "history":[{"item":{"title":"Old","artist":"A"},"playedAt":100},{"item":{"title":"New","artist":"A"},"playedAt":200},{"item":{"title":"No time","artist":"A"}}]}""",
        )
        val like = c.likes.single()
        assertEquals("A song", like.song.title) // the bidi override became a space, spaces collapsed and trimmed
        assertEquals("USABC1234567", like.song.isrc)
        assertNull(like.song.durationMs)
        assertEquals(mapOf("youtube" to "aaaaaaaaaaa"), like.song.refs)
        assertEquals(listOf("https://i.scdn.co/image/b"), like.song.artwork)
        assertEquals("a likedAt that isn't a number falls back to now", now, like.likedAt)
        assertEquals(listOf("p1"), c.playlists.map { it.id })
        assertEquals("One", c.playlists[0].name)
        assertEquals(1, c.playlists[0].items.size)
        assertNull("not a share id", c.playlists[0].follow)
        assertEquals(listOf("New", "Old"), c.history.map { it.song.title })
    }

    @Test fun `history keeps the newest 5,000`() {
        val plays = (1..5_100).joinToString(",") { """{"item":{"title":"T$it","artist":"A"},"playedAt":$it}""" }
        val c = read("""{"kind":"stash-web-library","v":1,"history":[$plays]}""")
        assertEquals(5_000, c.history.size)
        assertEquals(5_100L, c.history.first().playedAt)
        assertEquals(101L, c.history.last().playedAt)
    }

    // -------------------------------------------------------------------------------------------- merge plan

    private fun song(title: String, artist: String = "A", isrc: String? = null) = WireSong(title, artist, isrc = isrc)
    private fun content(
        likes: List<WireSong> = emptyList(),
        playlists: List<ImportedPlaylist> = emptyList(),
        plays: List<Pair<WireSong, Long>> = emptyList(),
    ) = WebLibraryContent(likes.map { ImportedLike(it, 1) }, playlists, plays.map { ImportedPlay(it.first, it.second) })

    private val empty = LocalLibrary(emptyList(), emptySet(), emptyMap(), emptySet())

    @Test fun `a like already here under either key is skipped, and one liked twice in the file is taken once`() {
        val local = empty.copy(likes = listOf(SongRef("Halo", "Beyoncé", "USSM10804554")))
        val plan = WebLibraryMergePlanner.plan(
            content(likes = listOf(song("Halo", "Beyonce"), song("Halo", "Beyoncé", "USSM10804554"), song("Ivy"), song("ivy"), song("Halo", "Beyoncé", "GBAAA0000001"))),
            ImportSelection.ALL,
            local,
        )
        // "Halo · Beyonce" (no ISRC) and the same ISRC are the song here; another ISRC is another recording.
        assertEquals(listOf("Ivy", "Halo"), plan.likes.map { it.song.title })
        assertEquals("GBAAA0000001", plan.likes[1].song.isrc)
        assertEquals(3, plan.likesSkipped)
    }

    @Test fun `playlists - new, merged (only the songs it lacks, occurrence by occurrence), followed or left alone`() {
        val shared = ImportedPlaylist("w2", "Robin's mix", listOf(song("X")), 1, 1, WebLibraryFile.Follow("Fw12ab34", 3))
        val known = ImportedPlaylist("w3", "Known mix", listOf(song("Y")), 1, 1, WebLibraryFile.Follow("Ab12Cd34", 1))
        val merged = ImportedPlaylist("w1", "Night drive", listOf(song("Ivy"), song("New one"), song("Ivy"), song("Pink")), 1, 1)
        val fresh = ImportedPlaylist("w4", "Fresh", listOf(song("Z")), 1, 1)
        val local = empty.copy(
            targets = mapOf("w1" to LocalPlaylist(7, listOf(SongRef("Ivy", "A"), SongRef("Pink", "A")))),
            followedShareIds = setOf("Ab12Cd34"),
        )
        val plan = WebLibraryMergePlanner.plan(content(playlists = listOf(merged, shared, known, fresh)), ImportSelection.ALL, local)
        val append = plan.playlists[0] as PlaylistAction.Append
        assertEquals(7L, append.localId)
        // Ivy is here once, so the file's second Ivy is new; Pink is here.
        assertEquals(listOf("New one", "Ivy"), append.songs.map { it.title })
        assertTrue(plan.playlists[1] is PlaylistAction.Follow)
        assertTrue(plan.playlists[2] is PlaylistAction.Skip)
        assertTrue(plan.playlists[3] is PlaylistAction.Create)
    }

    @Test fun `the selection takes exactly the ticked parts`() {
        val c = content(
            likes = listOf(song("L")),
            playlists = listOf(ImportedPlaylist("a", "A", emptyList(), 1, 1), ImportedPlaylist("b", "B", emptyList(), 1, 1)),
            plays = listOf(song("P") to 5L),
        )
        val plan = WebLibraryMergePlanner.plan(c, ImportSelection(likes = false, plays = false, playlistIds = setOf("b")), empty)
        assertTrue(plan.likes.isEmpty() && plan.plays.isEmpty())
        assertEquals(0, plan.likesSkipped + plan.playsSkipped)
        assertEquals(listOf("b"), plan.playlists.map { it.playlist.id })
    }

    @Test fun `the same play (words and start) is never added twice`() {
        val local = empty.copy(playKeys = setOf(WebLibraryMergePlanner.playKey(SongRef("Ivy", "Frank Ocean"), 100)))
        val plan = WebLibraryMergePlanner.plan(
            content(plays = listOf(song("Ivy", "Frank Ocean", "USUM71600001") to 100L, song("ivy", "frank ocean") to 200L, song("Ivy", "Frank Ocean") to 200L)),
            ImportSelection.ALL,
            local,
        )
        assertEquals(listOf(200L), plan.plays.map { it.playedAt })
        assertEquals(2, plan.playsSkipped)
    }

    @Test fun `missing songs stays fast for two big lists`() {
        val have = (1..10_000).map { SongRef("Song $it", "A") }
        val incoming = (5_001..15_000).map { song("Song $it") }
        val t = System.nanoTime()
        assertEquals(5_000, WebLibraryMergePlanner.missingSongs(have, incoming).size)
        assertTrue((System.nanoTime() - t) / 1_000_000 < 5_000)
    }
}
