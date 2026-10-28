package com.stash.feature.record.art

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

/**
 * The Worker's lookup run here (worker/art.ts `lookUp`), on answers saved from Deezer and Apple (src/test/resources/art,
 * 2026-10-26) and on the Worker's own test cases (worker/art.test.ts, with addresses on the hosts the record allows).
 */
class ArtLookupTest {

    private fun fixture(name: String): String = requireNotNull(javaClass.getResource("/art/$name")) { name }.readText()

    /** Deezer and Apple answering by path; every URL asked is kept. */
    private class Upstream(
        val dzAlbum: String? = null,
        val dzTrack: String? = null,
        val dzArtist: String? = null,
        val itunes: String? = null,
        val status: Int = 200,
    ) : ArtHttp {
        val asked = mutableListOf<String>()

        override suspend fun get(url: String): ArtReply {
            asked += url
            if (status != 200) return ArtReply(status)
            val u = URI(url)
            val body = when {
                u.host == "api.deezer.com" && u.path == "/search/album" -> dzAlbum
                u.host == "api.deezer.com" && u.path == "/search/artist" -> dzArtist
                u.host == "api.deezer.com" -> dzTrack
                else -> itunes
            }
            return ArtReply(200, body ?: """{"data":[],"results":[]}""")
        }
    }

    /** No real waiting: the pauses asked for are kept. */
    private class Waits {
        val waits = mutableListOf<Long>()
        fun pace(gapMs: Long = 0, maxWaitMs: Long = Pace.MAX_WAIT_MS, now: () -> Long = { 0L }) =
            Pace(gapMs = gapMs, maxWaitMs = maxWaitMs, now = now, sleep = { waits += it })
    }

    /** Apple's pace with no waiting: these tests aren't about it (the ones that are pass their own). */
    private fun noWait() = Pace(gapMs = 0, now = { 0L }, sleep = {})

    private fun lookup(http: ArtHttp, pace: Pace = Waits().pace(), apple: Pace = noWait()) = ArtLookup(http, pace, apple)

    private fun q(url: String): String? =
        URI(url).rawQuery.split('&').map { it.split('=', limit = 2) }.firstOrNull { it[0] == "q" || it[0] == "term" }?.get(1)?.let { URLDecoder.decode(it, "UTF-8") }

    private val dz = "https://cdn-images.dzcdn.net/images/cover"

    // ── Saved answers ───────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `an album found on Deezer by its artist and name`() = runTest {
        val up = Upstream(dzAlbum = fixture("deezer-album-blonde.json"))
        val found = lookup(up).lookUp(ArtSong("Ivy", "Frank Ocean", "Blonde"))
        assertThat(found).isEqualTo(Found.Cover("$dz/f798a866107715dd6dc1049e498ce21f/500x500-000000-80-0-0.jpg"))
        assertThat(up.asked).hasSize(1)
        assertThat(q(up.asked[0])).isEqualTo("artist:\"Frank Ocean\" album:\"Blonde\"")
    }

    @Test fun `an artist's picture, the same name, never Deezer's empty placeholder or a near name`() = runTest {
        val up = Upstream(dzArtist = fixture("deezer-artist-frank-ocean.json"))
        val found = lookup(up).lookUp(ArtSong("Frank Ocean", "Frank Ocean", isArtist = true))
        // the first exact "Frank Ocean" with a picture (the feat. credits and "Frank Ocean (IT)" are other artists)
        assertThat(found).isEqualTo(Found.Cover("https://cdn-images.dzcdn.net/images/artist/882155c08dc31d6464d6d580083c968c/500x500-000000-80-0-0.jpg"))
        assertThat(up.asked.single()).contains("/search/artist")
        // the name as free text (Deezer's artist:"…" leaves the artist out of its own search)
        assertThat(q(up.asked[0])).isEqualTo("Frank Ocean")
    }

    @Test fun `a song Deezer's track search doesn't find goes to Apple, sized 600`() = runTest {
        val up = Upstream(dzTrack = fixture("deezer-track-ivy-empty.json"), itunes = fixture("itunes-song-ivy.json"))
        val found = lookup(up).lookUp(ArtSong("Self Control", "Frank Ocean"))
        assertThat(found).isEqualTo(
            Found.Cover("https://is1-ssl.mzstatic.com/image/thumb/Music115/v4/bb/45/68/bb4568f3-68cd-619d-fbcb-4e179916545d/BlondCover-Final.jpg/600x600bb.jpg"),
        )
        assertThat(up.asked).hasSize(2)
        // free text: Deezer's track field filters answer nothing since 2026-10
        assertThat(q(up.asked[0])).isEqualTo("Frank Ocean Self Control")
        assertThat(up.asked[1]).isEqualTo("https://itunes.apple.com/search?term=Frank+Ocean+Self+Control&media=music&entity=song&limit=10")
    }

    @Test fun `a song Deezer finds by its words gives its album's cover`() = runTest {
        val up = Upstream(dzTrack = fixture("deezer-track-ivy-hits.json"))
        assertThat(lookup(up).lookUp(ArtSong("Ivy", "Frank Ocean")))
            .isEqualTo(Found.Cover("$dz/f798a866107715dd6dc1049e498ce21f/500x500-000000-80-0-0.jpg"))
    }

    @Test fun `none, for a while, when Deezer answered and neither has it`() = runTest {
        // Apple's album search for "Frank Ocean Blonde" lists only singles, and Ivy only by other artists
        val up = Upstream(dzAlbum = fixture("deezer-empty.json"), dzTrack = fixture("deezer-track-ivy-empty.json"), itunes = fixture("itunes-album-blonde.json"))
        assertThat(lookup(up).lookUp(ArtSong("Ivy", "Frank Ocean", "Blonde"))).isEqualTo(Found.Cover(null))
        assertThat(up.asked.last()).contains("entity=album")
        val song = Upstream(dzTrack = fixture("deezer-track-ivy-empty.json"), itunes = fixture("itunes-song-ivy.json"))
        assertThat(lookup(song).lookUp(ArtSong("Ivy", "Frank Ocean"))).isEqualTo(Found.Cover(null))
    }

    // ── The Worker's cases ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `the album by its artist first, the exact name over one that starts the same`() = runTest {
        val up = Upstream(
            dzAlbum = """{"data":[{"title":"Roots Bloody Roots (Tribute)","artist":{"name":"Crisalide"},"cover_big":"$dz/a.jpg"},""" +
                """{"title":"Roots (Expanded Edition)","artist":{"name":"Sepultura"},"cover_big":"$dz/roots.jpg"}]}""",
        )
        assertThat(lookup(up).lookUp(ArtSong("Ratamahatta", "Sepultura", "Roots"))).isEqualTo(Found.Cover("$dz/roots.jpg"))
        assertThat(q(up.asked.single())).isEqualTo("artist:\"Sepultura\" album:\"Roots\"")
    }

    @Test fun `falls back to the song on Deezer, then to Apple`() = runTest {
        val track = Upstream(dzTrack = """{"data":[{"title":"Ivy","artist":{"name":"Frank Ocean"},"album":{"title":"Blonde","cover_big":"$dz/blonde.jpg"}}]}""")
        assertThat(lookup(track).lookUp(ArtSong("Ivy", "Frank Ocean", "Blond"))).isEqualTo(Found.Cover("$dz/blonde.jpg"))
        val apple = Upstream(itunes = """{"results":[{"artistName":"Frank Ocean","collectionName":"Blonde","artworkUrl100":"https://is1-ssl.mzstatic.com/x/100x100bb.jpg"}]}""")
        assertThat(lookup(apple).lookUp(ArtSong("Ivy", "Frank Ocean", "Blonde"))).isEqualTo(Found.Cover("https://is1-ssl.mzstatic.com/x/600x600bb.jpg"))
        assertThat(lookup(Upstream()).lookUp(ArtSong("Zero", "Nobody", "Nothing"))).isEqualTo(Found.Cover(null))
    }

    @Test fun `a hit the record's page can't show is no hit`() = runTest {
        val up = Upstream(
            dzAlbum = """{"data":[{"title":"Roots","artist":{"name":"Sepultura"},"cover_big":"https://dz/roots.jpg"},""" +
                """{"title":"Roots","artist":{"name":"Sepultura"},"cover_big":"$dz/ok.jpg"}]}""",
        )
        assertThat(lookup(up).lookUp(ArtSong("R", "Sepultura", "Roots"))).isEqualTo(Found.Cover("$dz/ok.jpg"))
    }

    @Test fun `the Worker's artist case, the empty placeholder and a near name skipped`() = runTest {
        val up = Upstream(
            dzArtist = """{"data":[{"name":"Muerte Y Sepultura","nb_fan":900,"picture_big":"https://cdn-images.dzcdn.net/images/artist/aa/500.jpg"},""" +
                """{"name":"Sepultura","nb_fan":3,"picture_big":"https://cdn-images.dzcdn.net/images/artist//500x500.jpg"},""" +
                """{"name":"Sepultura","nb_fan":1204336,"picture_big":"https://cdn-images.dzcdn.net/images/artist/bb/500.jpg"}]}""",
        )
        assertThat(lookup(up).lookUp(ArtSong("Sepultura", "Sepultura", isArtist = true)))
            .isEqualTo(Found.Cover("https://cdn-images.dzcdn.net/images/artist/bb/500.jpg"))
    }

    @Test fun `the song step searches the words as free text, and still holds the hits to the artist and the title`() = runTest {
        val up = Upstream(
            dzTrack = """{"data":[{"title":"Nude (Live)","artist":{"name":"Radiohead Tribute Band"},"album":{"cover_big":"$dz/tribute.jpg"}},""" +
                """{"title":"Nude","artist":{"name":"Radiohead"},"album":{"cover_big":"$dz/in-rainbows.jpg"}}]}""",
        )
        assertThat(lookup(up).lookUp(ArtSong("Nude - 2007 Remaster", "Radiohead"))).isEqualTo(Found.Cover("$dz/in-rainbows.jpg"))
        assertThat(q(up.asked.single())).isEqualTo("Radiohead Nude")
    }

    @Test fun `an artist's picture is the exact artist's among the ten, not the tribute band Deezer lists first`() = runTest {
        val up = Upstream(
            dzArtist = """{"data":[{"name":"Radiohead Tribute Band","picture_big":"https://cdn-images.dzcdn.net/images/artist/aa/500.jpg"},""" +
                """{"name":"Radiohead","picture_big":"https://cdn-images.dzcdn.net/images/artist/rh/500.jpg"}]}""",
        )
        assertThat(lookup(up).lookUp(ArtSong("Radiohead", "Radiohead", isArtist = true)))
            .isEqualTo(Found.Cover("https://cdn-images.dzcdn.net/images/artist/rh/500.jpg"))
        // with no exact name, none: never a near one (a wrong face is worse than initials)
        val near = Upstream(dzArtist = """{"data":[{"name":"Radiohead Tribute Band","picture_big":"https://cdn-images.dzcdn.net/images/artist/aa/500.jpg"}]}""")
        assertThat(lookup(near).lookUp(ArtSong("Radiohead", "Radiohead", isArtist = true))).isEqualTo(Found.Cover(null))
    }

    @Test fun `Queen - of the hits named Queen, the one with the most fans, though a namesake of 21 is listed first`() = runTest {
        val up = Upstream(
            dzArtist = """{"data":[{"name":"Queen","nb_fan":21,"picture_big":"https://cdn-images.dzcdn.net/images/artist/small/500.jpg"},""" +
                """{"name":"Queen + Adam Lambert","nb_fan":1100000,"picture_big":"https://cdn-images.dzcdn.net/images/artist/qal/500.jpg"},""" +
                """{"name":"Queen","nb_fan":12812442,"picture_big":"https://cdn-images.dzcdn.net/images/artist/band/500.jpg"}]}""",
        )
        assertThat(lookup(up).lookUp(ArtSong("Queen", "Queen", isArtist = true)))
            .isEqualTo(Found.Cover("https://cdn-images.dzcdn.net/images/artist/band/500.jpg"))
        // the most-fanned Queen with Deezer's placeholder: none, never the namesake's face
        val blank = Upstream(
            dzArtist = """{"data":[{"name":"Queen","nb_fan":21,"picture_big":"https://cdn-images.dzcdn.net/images/artist/small/500.jpg"},""" +
                """{"name":"Queen","nb_fan":12812442,"picture_big":"https://cdn-images.dzcdn.net/images/artist//500x500.jpg"}]}""",
        )
        assertThat(lookup(blank).lookUp(ArtSong("Queen", "Queen", isArtist = true))).isEqualTo(Found.Cover(null))
    }

    @Test fun `Apple refusing, or not answering, is ask later, never none`() = runTest {
        for (status in listOf(0, 403, 429, 500, 503)) {
            val http = ArtHttp { url -> if (URI(url).host == "api.deezer.com") ArtReply(200, """{"data":[]}""") else ArtReply(status) }
            assertThat(lookup(http).lookUp(ArtSong("Ivy", "Frank Ocean", "Blonde"))).isEqualTo(Found.Later)
        }
        // another error is an answer: none
        val notFound = ArtHttp { url -> if (URI(url).host == "api.deezer.com") ArtReply(200, """{"data":[]}""") else ArtReply(404) }
        assertThat(lookup(notFound).lookUp(ArtSong("Ivy", "Frank Ocean", "Blonde"))).isEqualTo(Found.Cover(null))
    }

    @Test fun `Apple refusing pauses Apple alone, for a minute - Deezer is still asked`() = runTest {
        var clock = 0L
        var status = 429
        val asked = mutableListOf<String>()
        val http = ArtHttp { url ->
            asked += URI(url).host
            if (URI(url).host == "api.deezer.com") ArtReply(200, """{"data":[]}""") else ArtReply(status, """{"results":[]}""")
        }
        val look = ArtLookup(http, Waits().pace(), noWait(), now = { clock })
        assertThat(look.lookUp(ArtSong("Ivy", "Frank Ocean", "Blonde"))).isEqualTo(Found.Later)
        assertThat(asked.count { it == "itunes.apple.com" }).isEqualTo(1)
        // within the minute: Deezer asked (album, song), Apple not
        status = 200
        asked.clear()
        clock = Pace.ITUNES_PAUSE_MS - 1
        assertThat(look.lookUp(ArtSong("Nights", "Frank Ocean", "Blonde"))).isEqualTo(Found.Later)
        assertThat(asked).containsExactly("api.deezer.com", "api.deezer.com")
        // after it: Apple again
        asked.clear()
        clock = Pace.ITUNES_PAUSE_MS
        assertThat(look.lookUp(ArtSong("Nights", "Frank Ocean", "Blonde"))).isEqualTo(Found.Cover(null))
        assertThat(asked.last()).isEqualTo("itunes.apple.com")
        // a 5xx is no answer: later, but no pause
        status = 503
        clock += 1
        look.lookUp(ArtSong("Pink + White", "Frank Ocean", "Blonde"))
        status = 200
        asked.clear()
        assertThat(look.lookUp(ArtSong("Solo", "Frank Ocean", "Blonde"))).isEqualTo(Found.Cover(null))
        assertThat(asked.last()).isEqualTo("itunes.apple.com")
    }

    @Test fun `Apple's searches are paced to about twenty a minute, and one that can't have its turn soon is asked later`() = runTest {
        val waits = mutableListOf<Long>()
        val apple = Pace(gapMs = Pace.ITUNES_GAP_MS, maxWaitMs = Pace.ITUNES_MAX_WAIT_MS, now = { 0L }, sleep = { waits += it })
        var asked = 0
        val http = ArtHttp { url -> if (URI(url).host == "api.deezer.com") ArtReply(200, """{"data":[]}""") else ArtReply(200, """{"results":[]}""").also { asked++ } }
        val look = ArtLookup(http, Waits().pace(), apple)
        val found = (0 until 8).map { look.lookUp(ArtSong("T$it", "A$it")) }
        // 3 s apart: the 1st now, then 3, 6, 9 and 12 s on; the rest would wait past 12 s
        assertThat(waits).containsExactly(3_000L, 6_000L, 9_000L, 12_000L).inOrder()
        assertThat(asked).isEqualTo(5)
        assertThat(found.count { it == Found.Later }).isEqualTo(3)
        assertThat(Pace.ITUNES_GAP_MS).isEqualTo(3_000L)
    }

    // ── Deezer's limit, and refusals ────────────────────────────────────────────────────────────────────────────────────

    @Test fun `asks again after a quota refusal, after a pause, and finds the cover`() = runTest {
        var n = 0
        val http = ArtHttp {
            if (n++ == 0) ArtReply(200, fixture("deezer-quota.json")) else ArtReply(200, fixture("deezer-album-blonde.json"))
        }
        val w = Waits()
        assertThat(lookup(http, w.pace()).lookUp(ArtSong("Ivy", "Frank Ocean", "Blonde"))).isInstanceOf(Found.Cover::class.java)
        assertThat(n).isEqualTo(2)
        assertThat(w.waits).contains(1200L)
    }

    @Test fun `later (not none) when Deezer keeps refusing, and Apple isn't asked in its place`() = runTest {
        var apple = 0
        val http = ArtHttp { url ->
            if (URI(url).host == "api.deezer.com") ArtReply(200, fixture("deezer-quota.json")) else ArtReply(200, """{"results":[]}""").also { apple++ }
        }
        val w = Waits()
        assertThat(lookup(http, w.pace()).lookUp(ArtSong("Ivy", "Frank Ocean", "Blonde"))).isEqualTo(Found.Later)
        assertThat(apple).isEqualTo(0)
        assertThat(w.waits).containsAtLeast(1200L, 2500L).inOrder()
    }

    @Test fun `no answer or a 429 or 5xx is a refusal, another error is later`() = runTest {
        for (status in listOf(0, 429, 500, 503)) {
            assertThat(lookup(Upstream(status = status)).lookUp(ArtSong("I", "A", "B"))).isEqualTo(Found.Refused)
        }
        assertThat(lookup(Upstream(status = 404)).lookUp(ArtSong("I", "A", "B"))).isEqualTo(Found.Later)
        assertThat(lookup(ArtHttp { ArtReply(200, "not json") }).lookUp(ArtSong("I", "A", "B"))).isEqualTo(Found.Later)
        assertThat(lookup(ArtHttp { ArtReply(200, """{"error":{"code":800}}""") }).lookUp(ArtSong("I", "A", "B"))).isEqualTo(Found.Later)
    }

    @Test fun `later rather than queue a search behind too many others`() = runTest {
        var asked = 0
        val pace = Pace(gapMs = 1000, maxWaitMs = 8000, now = { 0L }, sleep = {})
        val http = ArtHttp { asked++; ArtReply(200, """{"data":[]}""") }
        val found = (0 until 12).map { i -> async { lookup(http, pace).lookUp(ArtSong("T", "Q$i", isArtist = true)) } }.awaitAll()
        assertThat(found.count { it == Found.Later }).isGreaterThan(0)
        assertThat(asked).isLessThan(12)
    }

    @Test fun `Deezer's searches are spaced the gap apart`() = runTest {
        val waits = mutableListOf<Long>()
        val pace = Pace(gapMs = 110, now = { 0L }, sleep = { waits += it })
        val http = ArtHttp { ArtReply(200, """{"data":[]}""") }
        (1..3).map { i -> async { lookup(http, pace).lookUp(ArtSong("T", "A$i", isArtist = true)) } }.awaitAll()
        assertThat(waits).containsExactly(110L, 220L)
    }
}
