package com.stash.core.addons

import com.google.common.truth.Truth.assertThat
import com.stash.addons.contract.Codec
import com.stash.addons.contract.Delivery
import com.stash.addons.contract.ErrorCode
import com.stash.addons.contract.Limits
import com.stash.addons.contract.StreamRequest
import com.stash.addons.contract.Tier
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

/**
 * The Eclipse-family reader on hostile and sloppy input (review-882 M1, with J1, N1 and N9): a
 * number no rule expected, ids written as numbers, repeated ids, rows that are not rows, and
 * values nested thousands deep. Whatever arrives, the reader drops the part it cannot use or
 * refuses the answer; nothing is thrown past the client, and nothing is walked recursively.
 */
class AddonEclipseHostileTest {

    private val user = AddonValidator.Options(publicOnly = true)

    private fun j(s: String) = Json.parseToJsonElement(s)

    /** [depth] objects, one inside the other. kotlinx.serialization parses these on the heap. */
    private fun deepObject(depth: Int) = "{\"a\":".repeat(depth) + "1" + "}".repeat(depth)

    /** [depth] arrays, one inside the other: these overflow the parser's own stack. */
    private fun deepArray(depth: Int) = "[".repeat(depth) + "]".repeat(depth)

    // ------------------------------------------------------------------------------------------ numbers (N1, N9)

    @Test fun `digits too long to be a bit rate state no quality, and nothing is thrown`() {
        // `toInt()` on these threw NumberFormatException out of the reader.
        val q = "99999999999999999999 kbps"
        assertThat(AddonEclipse.deriveTier(Codec.MP3, null, null, null, q)).isEqualTo(Tier.LOW)
        val info = AddonEclipse.streamInfo(j("""{"url":"https://cdn.example.org/a.mp3","format":"mp3","quality":"$q"}"""), user)
        assertThat(info.tier).isEqualTo(Tier.LOW)
        assertThat(AddonEclipse.tierIsDefault(info)).isTrue()
        // The quality text is cut at 40 characters: 35 digits and "kbps" still fit.
        AddonEclipse.streamInfo(j("""{"url":"https://cdn.example.org/a.mp3","quality":"${"9".repeat(35)}kbps"}"""), user)
        AddonEclipse.streamInfo(j("""{"url":"https://cdn.example.org/a.mp3","quality":"${"9".repeat(60)}kbps"}"""), user)
        // A real one still reads.
        assertThat(AddonEclipse.deriveTier(null, null, null, null, "320 kbps")).isEqualTo(Tier.HIGH)
        assertThat(AddonEclipse.deriveTier(null, null, null, null, "128kbps")).isEqualTo(Tier.LOW)
    }

    @Test fun `an id written as a number keeps its own digits`() {
        fun id(literal: String) = AddonEclipse.track(j("""{"id":$literal,"title":"T","artist":"A"}"""), user)?.id
        assertThat(id("7")).isEqualTo("7")
        assertThat(id("-7")).isEqualTo("-7")
        // 2^53 + 1: through a Double it came out as ...992, and /stream/{id} asked for another song.
        assertThat(id("9007199254740993")).isEqualTo("9007199254740993")
        assertThat(id("12345678901234567890")).isEqualTo("12345678901234567890")
        // Written another way, it is taken only while a Double holds it exactly.
        assertThat(id("12.0")).isEqualTo("12")
        assertThat(id("1e3")).isEqualTo("1000")
        assertThat(id("1.5")).isNull()
        assertThat(id("1e30")).isNull()
        // Not ids at all.
        assertThat(id("true")).isNull()
        assertThat(id("null")).isNull()
        assertThat(id("{}")).isNull()
        assertThat(id("[1]")).isNull()
        assertThat(id("\"\"")).isNull()
        assertThat(id("\"abc\"")).isEqualTo("abc")
    }

    // ------------------------------------------------------------------------------------------ rows

    @Test fun `a repeated id, a row that is not an object and a row with no title are dropped, the rest kept`() {
        val r = AddonEclipse.searchResponse(
            j(
                """{"tracks":[
                    {"id":"1","title":"T","artist":"A"},
                    {"id":"1","title":"Twin","artist":"A"},
                    {"id":1,"title":"The same id as a number","artist":"A"},
                    42, null, "x", [], {},
                    {"id":"2","artist":"A"},
                    {"id":"3","title":"U","artist":"B","duration":"long","isrc":12,"explicit":"yes"}
                ]}""",
            ),
            user,
        )
        assertThat(r.tracks.map { it.id to it.title }).containsExactly("1" to "T", "3" to "U").inOrder()
        assertThat(r.tracks[1].durationMs).isNull()
        assertThat(r.tracks[1].isrc).isNull()
        assertThat(r.tracks[1].explicit).isNull()
    }

    @Test fun `no more rows are read than a search may carry`() {
        val rows = (1..(Limits.TRACKS + 300)).joinToString(",") { """{"id":"$it","title":"T$it","artist":"A"}""" }
        assertThat(AddonEclipse.searchResponse(j("""{"tracks":[$rows]}"""), user).tracks).hasSize(Limits.TRACKS)
    }

    @Test fun `text an add-on sends is cleaned and cut before anything shows it`() {
        val long = "x".repeat(5_000)
        val t = AddonEclipse.track(j("""{"id":"1","title":"a‮b\u0000c   d$long","artist":"A"}"""), user)!!
        assertThat(t.title).startsWith("a b c d")
        assertThat(t.title.length).isAtMost(Limits.TITLE)
    }

    // ------------------------------------------------------------------------------------------ deep nesting (J1)

    @Test fun `a manifest with deep values in the keys the reader knows reads them as absent or refuses, without walking them`() {
        val deep = deepObject(5_000)
        val read = AddonEclipse.manifest(
            j(
                """{"id":"deep.addon","name":$deep,"version":$deep,"description":$deep,"icon":$deep,
                    "resources":["search","stream",$deep],"settings":[$deep],"catalogs":[$deep],"unknown":$deep}""",
            ),
            user,
        )
        assertThat(read.manifest.id).isEqualTo("deep.addon")
        assertThat(read.manifest.name).isEqualTo("deep.addon")
        assertThat(read.manifest.description).isNull()
        assertThat(read.manifest.icon).isNull()
        assertThat(read.manifest.resources).containsExactly("search", "stream").inOrder()
        assertThat(read.manifest.settings).isNull()
        assertThat(read.manifest.catalogs).isNull()
        // An id that is not text or a number is no Eclipse manifest at all.
        assertThrows(AddonContractException::class.java) { AddonEclipse.manifest(j("""{"id":$deep,"resources":["search"]}"""), user) }
    }

    @Test fun `a stream answer with deep values is read by its plain fields, and a deep url or drm is not played`() {
        val deep = deepObject(5_000)
        val info = AddonEclipse.streamInfo(
            j("""{"url":"https://cdn.example.org/a.flac","codec":"flac","quality":$deep,"bitrate":$deep,"expiresAt":$deep,"container":$deep,"x":$deep}"""),
            user,
        )
        assertThat(info.tier).isEqualTo(Tier.LOSSLESS)
        assertThat(info.bitrate).isNull()
        assertThat(info.expiresAt).isNull()
        assertThrows(AddonContractException::class.java) { AddonEclipse.streamInfo(j("""{"url":$deep}"""), user) }
        val drm = assertThrows(AddonRefusalException::class.java) {
            AddonEclipse.streamInfo(j("""{"url":"https://cdn.example.org/a.flac","codec":"flac","drm":$deep}"""), user)
        }
        assertThat(drm.code).isEqualTo(ErrorCode.QUALITY_UNAVAILABLE)
    }

    // ------------------------------------------------------------------------------------------ through the client

    private lateinit var server: MockWebServer
    private lateinit var client: HttpAddonClient
    private val gate = HttpAddonClientTest.RecordingGate()

    private val host = object : AddonHostOptions {
        override val allowLocalHttp = true
    }

    @Before fun setUp() {
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        client = HttpAddonClient(OkHttpClient(), gate, NoFirstPartyAddons, UnsignedAddons, host)
    }

    @After fun tearDown() = server.shutdown()

    private fun addon(): InstalledAddon {
        val read = AddonValidator.readManifest(
            j("""{"id":"com.example.plain","name":"Plain","version":"1","resources":["search","stream"]}"""),
            client.optionsFor(publicOnly = true),
        )
        return InstalledAddon(
            base = "http://127.0.0.1:${server.port}/tok/", manifest = read.manifest, userAdded = true, dialect = read.dialect, eclipse = read.eclipse,
        )
    }

    private val lossless = StreamRequest(Tier.HIRES, Tier.LOSSLESS, listOf(Delivery.NONE, Delivery.DASH))

    @Test fun `answers nested 100,000 deep come back as answers or as invalid, and nothing escapes the client`() = runBlocking<Unit> {
        val objects = deepObject(100_000)
        val arrays = deepArray(100_000)

        // Deep objects in a key the reader ignores: the answer is read.
        server.enqueue(MockResponse().setBody("""{"url":"https://cdn.example.org/a.flac","codec":"flac","x":$objects}"""))
        assertThat(client.stream(addon(), "1", lossless)).isInstanceOf(AddonResult.Ok::class.java)
        server.enqueue(MockResponse().setBody("""{"tracks":[{"id":"1","title":"T","artist":"A","x":$objects}],"y":$objects}"""))
        assertThat((client.search(addon(), "x") as AddonResult.Ok).value.tracks.single().id).isEqualTo("1")

        // Deep arrays overflow the JSON parser's own stack on today's library: then the answer is
        // invalid. Should a later one parse them, they sit in keys the reader never walks. Either
        // way a result comes back and no StackOverflowError leaves the client.
        fun AddonResult<*>.readOrInvalid() = this is AddonResult.Ok || (this as AddonResult.Failed).failure is AddonFailure.InvalidResponse
        server.enqueue(MockResponse().setBody("""{"url":"https://cdn.example.org/a.flac","codec":"flac","x":$arrays}"""))
        assertThat(client.stream(addon(), "1", lossless).readOrInvalid()).isTrue()
        server.enqueue(MockResponse().setBody("""{"tracks":$arrays}"""))
        assertThat(client.search(addon(), "x").readOrInvalid()).isTrue()

        // And the same in an Eclipse manifest: under the 64 KiB cap, read; nothing thrown either way.
        server.enqueue(MockResponse().setBody("""{"id":"deep.addon","resources":["search","stream"],"x":${deepObject(8_000)}}"""))
        val manifest = (client.readManifest("http://127.0.0.1:${server.port}/tok/", publicOnly = true) as AddonResult.Ok).value
        assertThat(manifest.dialect).isEqualTo(AddonDialect.ECLIPSE)
        server.enqueue(MockResponse().setBody("""{"id":"deep.addon","resources":${deepArray(20_000)}}"""))
        assertThat((client.readManifest("http://127.0.0.1:${server.port}/tok/", publicOnly = true) as AddonResult.Failed).failure)
            .isInstanceOf(AddonFailure.InvalidResponse::class.java)
    }

    @Test fun `a quality no rule expected is an answer, not a failure of the add-on`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"url":"https://cdn.example.org/a.mp3","format":"mp3","quality":"99999999999999999999 kbps"}"""))
        val r = client.stream(addon(), "1", StreamRequest(Tier.HIGH, Tier.HIGH))
        assertThat(r).isInstanceOf(AddonResult.Ok::class.java)
        assertThat(gate.events.none { it.startsWith("failure") }).isTrue()
    }
}
