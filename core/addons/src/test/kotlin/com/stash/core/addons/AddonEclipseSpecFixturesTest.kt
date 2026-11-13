package com.stash.core.addons

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.stash.addons.contract.Delivery
import com.stash.addons.contract.Descriptor
import com.stash.addons.contract.ErrorCode
import com.stash.addons.contract.Manifest
import com.stash.addons.contract.SearchResponse
import com.stash.addons.contract.StreamInfo
import com.stash.addons.contract.StreamRequest
import com.stash.addons.contract.Tier
import com.stash.addons.contract.Track
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress

/**
 * The spec's own Eclipse-family fixtures (`src/test/resources/addon-fixtures/eclipse`; where they
 * come from is written once, in the `SOURCE.md` beside them) through the app's reader, held to the
 * spec's `expect.json` (review-882 M10). The checker and the web player read the same files
 * through the spec's shared readers, so the three agree on what an Eclipse-family add-on says.
 *
 * Each expectation is decoded into the contract's own types and compared whole with what
 * [AddonEclipse] read, so a field the reader adds, drops or reads differently fails here.
 *
 * Two things in `expect.json` are the spec's and not checked against this reader:
 *  - `rowStream` (a search row's own `streamURL`, for an add-on with no `/stream` route). Stash
 *    plays no such row: an add-on that lists no `stream` is refused at Add ("offers nothing Stash
 *    can play yet").
 *  - `pause` for a 5xx with no `Retry-After` is "counts toward the breaker" here (three in a row
 *    open it), not an immediate pause. The test reads both as "the add-on's circuit heard of it".
 */
class AddonEclipseSpecFixturesTest {

    private val root: File = File(
        requireNotNull(javaClass.classLoader?.getResource("addon-fixtures/eclipse/expect.json")) { "fixtures not on the test classpath" }.toURI(),
    ).parentFile

    private val json = Json { ignoreUnknownKeys = true }

    /** The fixtures' hosts are `example.org` names: read as an add-on the user added, base rules on. */
    private val user = AddonValidator.Options(publicOnly = true)

    private fun load(name: String): JsonElement = Json.parseToJsonElement(File(root, name).readText())

    private val expect: JsonObject = load("expect.json").jsonObject

    private fun entries(folder: String): List<Pair<String, JsonObject>> =
        expect.filterKeys { it.startsWith("$folder/") }.map { (name, e) -> name to e.jsonObject }.also {
            assertWithMessage("expect.json names no $folder fixture").that(it).isNotEmpty()
        }

    @Test fun `every fixture file has an expectation, and every expectation is one this test reads`() {
        val files = root.walkTopDown().filter { it.isFile && it.extension == "json" && it.name != "expect.json" }
            .map { it.relativeTo(root).invariantSeparatorsPath }.toSet()
        assertThat(expect.keys).containsExactlyElementsIn(files)
        assertThat(expect.keys.map { it.substringBefore('/') }.toSet()).containsExactly("manifest", "search", "resolve", "stream", "error")
    }

    // ------------------------------------------------------------------------------------------ manifests

    private val passes = mapOf(
        "lossless" to StreamRequest(Tier.HIRES, Tier.LOSSLESS, listOf(Delivery.NONE, Delivery.DASH)),
        "high" to StreamRequest(Tier.HIGH, Tier.HIGH),
        "low" to StreamRequest(Tier.LOW, Tier.LOW),
    )

    @Test fun `manifests read as the v1 manifest the spec expects, with their switches and the quality sent on each pass`() {
        for ((name, e) in entries("manifest")) {
            if (e["refused"] != null) {
                assertThat(e["refused"]!!.jsonPrimitive.content).isEqualTo("only-bitchord")
                val refused = assertThrows(name, AddonContractException::class.java) { AddonEclipse.manifest(load(name), user) }
                assertWithMessage(name).that(refused.reason).isEqualTo(AddonEclipse.ONLY_BITCHORD)
                continue
            }
            val read = AddonEclipse.manifest(load(name), user)
            assertWithMessage(name).that(read.manifest).isEqualTo(json.decodeFromJsonElement(Manifest.serializer(), e["value"]!!))
            assertWithMessage(name).that(read.dialect).isEqualTo(AddonDialect.ECLIPSE)
            val traits = read.eclipse!!
            assertWithMessage("$name viaIsrc").that(traits.resolveViaIsrc).isEqualTo(e["viaIsrc"]!!.jsonPrimitive.boolean)
            assertWithMessage("$name allowDownloads").that(traits.allowDownloads).isEqualTo(e["allowDownloads"]!!.jsonPrimitive.boolean)
            for ((pass, wanted) in e["quality"]!!.jsonObject) {
                assertWithMessage("$name quality on the $pass pass")
                    .that(HttpAddonClient.eclipseQuality(traits.qualityOption, passes.getValue(pass)))
                    .isEqualTo(wanted.jsonPrimitive.content)
            }
        }
    }

    // ------------------------------------------------------------------------------------------ search and resolve

    @Test fun `a search answer reads as the spec expects - tracks, artists and albums, playlists dropped`() {
        for ((name, e) in entries("search")) {
            val read = AddonEclipse.searchResponse(load(name), user)
            assertWithMessage(name).that(read).isEqualTo(json.decodeFromJsonElement(SearchResponse.serializer(), e["value"]!!))
        }
    }

    @Test fun `resolve and resolve-isrc answers read as the spec expects`() {
        for ((name, e) in entries("resolve")) {
            val asked = e["asked"]!!.jsonObject
            val song = Descriptor(
                title = asked["title"]!!.jsonPrimitive.content,
                artist = asked["artist"]!!.jsonPrimitive.content,
                isrc = asked["isrc"]?.jsonPrimitive?.content,
            )
            val answer = load(name)
            // `{ item }` is /resolve; anything else is /resolve-isrc, which carries only an id.
            val read = if ("item" in answer.jsonObject) AddonEclipse.resolveResponse(answer, user) else AddonEclipse.resolveIsrcResponse(answer, song)
            val wanted = e["value"]!!.jsonObject["item"]!!
            if (wanted is JsonNull) {
                assertWithMessage(name).that(read).isNull()
            } else {
                assertWithMessage(name).that(read).isEqualTo(json.decodeFromJsonElement(Track.serializer(), wanted))
            }
        }
    }

    // ------------------------------------------------------------------------------------------ streams

    @Test fun `stream answers read as the spec expects - the stream, not playable, or a miss`() {
        for ((name, e) in entries("stream")) {
            when {
                e["miss"] != null -> {
                    val miss = assertThrows(name, AddonRefusalException::class.java) { AddonEclipse.streamInfo(load(name), user) }
                    assertWithMessage(name).that(miss.code).isEqualTo(ErrorCode.NOT_FOUND)
                }
                e["playable"] != null -> {
                    assertThat(e["playable"]!!.jsonPrimitive.boolean).isFalse()
                    val refused = assertThrows(name, AddonRefusalException::class.java) { AddonEclipse.streamInfo(load(name), user) }
                    assertWithMessage(name).that(refused.code).isEqualTo(ErrorCode.QUALITY_UNAVAILABLE)
                }
                else -> assertWithMessage(name).that(AddonEclipse.streamInfo(load(name), user))
                    .isEqualTo(json.decodeFromJsonElement(StreamInfo.serializer(), e["value"]!!))
            }
        }
    }

    // ------------------------------------------------------------------------------------------ errors, through the client

    private lateinit var server: MockWebServer
    private lateinit var client: HttpAddonClient
    private val gate = HttpAddonClientTest.RecordingGate()

    @Before fun setUp() {
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        client = HttpAddonClient(
            OkHttpClient(), gate, NoFirstPartyAddons, UnsignedAddons,
            object : AddonHostOptions {
                override val allowLocalHttp = true
            },
        )
    }

    @After fun tearDown() = server.shutdown()

    @Test fun `answers that are not a stream are acted on as the spec expects, by status`() = runBlocking<Unit> {
        val read = AddonValidator.readManifest(
            Json.parseToJsonElement("""{"id":"com.example.plain","name":"Plain","version":"1","resources":["search","stream"]}"""),
            client.optionsFor(publicOnly = true),
        )
        val addon = InstalledAddon(
            base = "http://127.0.0.1:${server.port}/tok/", manifest = read.manifest, userAdded = true, dialect = read.dialect, eclipse = read.eclipse,
        )
        val key = HttpAddonClient.rateKey(addon.id)
        for ((name, e) in entries("error")) {
            val answer = load(name).jsonObject
            val response = MockResponse().setResponseCode(answer["status"]!!.jsonPrimitive.int)
                .setBody(answer["body"]?.toString() ?: "")
            answer["headers"]?.jsonObject?.forEach { (header, value) -> response.setHeader(header, value.jsonPrimitive.content) }
            server.enqueue(response)
            gate.events.clear()

            // Paced, as a download asks: a "still preparing" answer is then reported, not waited for.
            val result = when (val route = answer["route"]!!.jsonPrimitive.content) {
                "stream" -> client.stream(addon, "track_123", passes.getValue("lossless"), paced = true)
                "search" -> client.search(addon, "song title", paced = true)
                else -> error("$name: a route this test does not call: $route")
            }

            val wanted = e["value"]!!.jsonObject
            val failure = (result as AddonResult.Failed).failure as AddonFailure.Refused
            assertWithMessage("$name code").that(failure.code.wire).isEqualTo(wanted["code"]!!.jsonPrimitive.content)
            assertWithMessage("$name miss").that(failure.isMiss).isEqualTo(wanted["miss"]!!.jsonPrimitive.boolean)
            assertWithMessage("$name preparing").that(failure.preparing).isEqualTo((wanted["preparing"] as? JsonPrimitive)?.boolean ?: false)
            assertWithMessage("$name onlyBitChord").that(failure.onlyBitChord).isEqualTo((wanted["onlyBitChord"] as? JsonPrimitive)?.boolean ?: false)
            assertWithMessage("$name retryAfter").that(failure.retryAfterSec).isEqualTo((wanted["retryAfter"] as? JsonPrimitive)?.int)
            // Paused, or counted toward the breaker: anything the circuit heard of but a plain answer.
            val heard = gate.events.any { it == "failure $key" || it.startsWith("rateLimited $key ") }
            assertWithMessage("$name pause").that(heard).isEqualTo(wanted["pause"]!!.jsonPrimitive.boolean)
        }
    }
}
