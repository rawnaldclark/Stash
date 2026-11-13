package com.stash.data.download.lossless.addon

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.stash.core.addons.BlockReason
import com.stash.data.download.lossless.relay.SignedJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The app's reader of the signed add-on index against the PUBLISHER'S OWN BYTES: the fixtures of
 * `infra/lossless-config/test/fixtures/` (branch `feat/addons-phase4-index`, commit `321ec000`),
 * copied byte for byte into `src/test/resources/addon-index-fixtures/` (that folder's
 * `.gitattributes` keeps git from touching their line endings, as the signatures cover every byte).
 * `cases.json` says what a reader must do with each; this test does exactly that, so the reader
 * accepts what the publisher emits and refuses what it says must be refused, for the reason it names.
 */
class AddonIndexFixturesTest {

    private fun bytes(path: String): ByteArray =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("addon-index-fixtures/$path")) { "missing fixture $path" }.use { it.readBytes() }

    private val publicKey = String(bytes("pubkey.b64")).trim()

    private val cases = Json.parseToJsonElement(String(bytes("cases.json"))).jsonObject.getValue("cases").jsonArray.map { it.jsonObject }

    private fun signatureOf(dir: String) = String(bytes("$dir/index.json.sig")).trim()

    @Test fun `every fixture is read the way cases json says a reader must`() {
        assertThat(cases).hasSize(14)
        for (case in cases) {
            val dir = case.getValue("dir").jsonPrimitive.content
            val body = bytes("$dir/index.json")
            assertWithMessage("$dir: the bytes are the publisher's").that(body.size).isEqualTo(case.getValue("bytes").jsonPrimitive.int)
            val signed = SignedJson.verify(body, signatureOf(dir), publicKey)
            assertWithMessage("$dir: signature").that(signed).isEqualTo(case.getValue("signature").jsonPrimitive.content == "valid")
            val parsed = runCatching { AddonIndex.parse(body) }
            when (case.getValue("expect").jsonPrimitive.content) {
                "accept" -> {
                    assertWithMessage("$dir: accepted").that(signed).isTrue()
                    val index = parsed.getOrThrow()
                    assertWithMessage("$dir: updated_at").that(index.updatedAt).isEqualTo(case.getValue("updated_at").jsonPrimitive.long)
                    assertWithMessage("$dir: addons").that(index.addons).hasSize(case.getValue("addons").jsonPrimitive.int)
                    assertWithMessage("$dir: blocked").that(index.blocked).hasSize(case.getValue("blocked").jsonPrimitive.int)
                    // Where the case says how each block's reason reads: a word a reader doesn't know reads as
                    // "unknown" (here BlockReason.OTHER), and the add-on is blocked all the same.
                    case["blocked_reasons"]?.jsonArray?.map { it.jsonPrimitive.content }?.let { reasons ->
                        assertWithMessage("$dir: blocked_reasons").that(index.blocked.map { it.reason.wire ?: "unknown" })
                            .containsExactlyElementsIn(reasons).inOrder()
                    }
                    // Where the case says how each add-on's `platforms` reads: a word a reader doesn't know
                    // drops out (null: the entry names none; an empty list: none this reader knows).
                    case["addon_platforms"]?.jsonArray?.let { expected ->
                        val want = expected.map { e -> if (e is JsonNull) null else e.jsonArray.map { it.jsonPrimitive.content } }
                        assertWithMessage("$dir: addon_platforms").that(index.addons.map { it.platforms }).isEqualTo(want)
                    }
                }
                "refuse" -> when (val by = case.getValue("refused_by").jsonPrimitive.content) {
                    // Only the signature is wrong: the body itself would read.
                    "signature" -> assertWithMessage("$dir: refused by its signature").that(signed).isFalse()
                    else -> {
                        // The signature is good, so the named rule is the only thing that refuses it.
                        assertWithMessage("$dir: its signature is good").that(signed).isTrue()
                        val refused = parsed.exceptionOrNull() as? AddonIndex.Refused
                        assertWithMessage("$dir: refused by $by").that(refused).isNotNull()
                        when (by) {
                            "type" -> assertWithMessage(dir).that(refused!!.rule).isEqualTo("type")
                            "v" -> assertWithMessage(dir).that(refused!!.rule).isEqualTo("v")
                            "size" -> assertWithMessage(dir).that(refused!!.rule).isEqualTo("size")
                            "rule" -> assertWithMessage(dir).that(refused!!.rule).isNoneOf("type", "v", "size", "not JSON", "not UTF-8")
                            else -> error("cases.json names a reason this test doesn't know: $by")
                        }
                    }
                }
                else -> error("cases.json: unknown expect in $dir")
            }
        }
    }

    @Test fun `a signed lossless json served as the index is refused by type, not by its signature or the floor`() {
        // Both files are signed with one key: without `type`, this would pass as an index.
        val body = bytes("refuse-lossless-json/index.json")
        assertThat(SignedJson.verify(body, signatureOf("refuse-lossless-json"), publicKey)).isTrue()
        assertThat(String(body)).contains("\"updated_at\":1794441600")
        val refused = assertThrows(AddonIndex.Refused::class.java) { AddonIndex.parse(body) }
        assertThat(refused.rule).isEqualTo("type")
    }

    @Test fun `the full index reads field by field`() {
        val index = AddonIndex.parse(bytes("valid/index.json"))
        assertThat(index.updatedAt).isEqualTo(1794355200L)
        val (tidal, radio, sixty) = index.addons
        // A default: no manifest here (its URL carries a token and stays in the signed config).
        assertThat(tidal).isEqualTo(AddonIndex.Entry(id = "tidal", name = "Tidal", summary = "Lossless and Hi-Res"))
        assertThat(tidal.isDefault).isTrue()
        assertThat(radio.manifest).isEqualTo("https://addons.example.org/radio/manifest.json")
        assertThat(radio.icon).isEqualTo("https://addons.example.org/radio/icon.png")
        assertThat(radio.minBuild).isEqualTo(160)
        assertThat(radio.platforms).containsExactly("android", "web").inOrder()
        assertThat(radio.offeredTo(build = 160)).isTrue()
        assertThat(radio.offeredTo(build = 159)).isFalse()
        // Sixty UTF-16 units, non-ASCII text and a port all read.
        assertThat(sixty.name.length).isEqualTo(60)
        assertThat(sixty.summary.length).isEqualTo(60)
        assertThat(sixty.manifest).isEqualTo("https://sixty.example.org:8443/manifest.json")
        assertThat(index.blocked).containsExactly(
            AddonIndex.Block("https://bad.example", "bad-addon", BlockReason.HARMFUL),
            AddonIndex.Block("https://broken.example:8443", null, BlockReason.BROKEN),
            AddonIndex.Block("https://fake-stash.example", "tidal", BlockReason.IMPERSONATION),
        ).inOrder()
        // A block with an id covers that id only; one without covers the origin.
        assertThat(index.blockOf("https://bad.example", "bad-addon")).isNotNull()
        assertThat(index.blockOf("https://bad.example", "other")).isNull()
        assertThat(index.blockOf("https://bad.example", null)).isNull()
        assertThat(index.blockOf("https://broken.example:8443", "anything")).isNotNull()
        assertThat(index.blockOf("https://broken.example:8443", null)).isNotNull()
        assertThat(index.blockOf("https://broken.example", "anything")).isNull()
    }

    @Test fun `fields a later publisher adds are ignored`() {
        val index = AddonIndex.parse(bytes("valid-unknown-fields/index.json"))
        assertThat(index.addons.single()).isEqualTo(AddonIndex.Entry(id = "tidal", name = "Tidal", summary = "Lossless and Hi-Res"))
        assertThat(index.blocked.single()).isEqualTo(AddonIndex.Block("https://bad.example", null, BlockReason.HARMFUL))
    }

    // ------------------------------------------------------------------------------------------ rules the fixtures don't each cover

    private fun index(addons: String = "[]", blocked: String = "[]", head: String = """"type":"stash-addon-index","v":1,"updated_at":5""") =
        """{$head,"addons":$addons,"blocked":$blocked}""".toByteArray()

    private fun refusedRule(body: ByteArray): String = assertThrows(AddonIndex.Refused::class.java) { AddonIndex.parse(body) }.rule

    @Test fun `a bad entry refuses the whole index, never just itself`() {
        val good = """{"id":"a","name":"A","summary":"S","default":true}"""
        assertThat(AddonIndex.parse(index(addons = "[$good]")).addons).hasSize(1)
        // Each of these is one entry rule broken beside a good entry.
        val bad = listOf(
            """{"id":"UPPER","name":"A","summary":"S","default":true}""",
            """{"id":"b","name":"","summary":"S","default":true}""",
            """{"id":"b","name":" lead","summary":"S","default":true}""",
            """{"id":"b","name":"two  spaces","summary":"S","default":true}""",
            """{"id":"b","name":"${"x".repeat(61)}","summary":"S","default":true}""",
            """{"id":"b","name":"zero​width","summary":"S","default":true}""",
            """{"id":"b","name":"A","summary":"S"}""",
            """{"id":"b","name":"A","summary":"S","default":false}""",
            """{"id":"b","name":"A","summary":"S","default":true,"manifest":"https://x.example/manifest.json"}""",
            """{"id":"b","name":"A","summary":"S","manifest":"http://x.example/manifest.json"}""",
            """{"id":"b","name":"A","summary":"S","manifest":"https://x.example/manifest.json?t=1"}""",
            """{"id":"b","name":"A","summary":"S","manifest":"https://x.example/other.json"}""",
            // A path part that looks like a token: an official manifest must be public.
            """{"id":"b","name":"A","summary":"S","manifest":"https://x.example/v1/a1b2c3d4e5f6a7b8/manifest.json"}""",
            """{"id":"b","name":"A","summary":"S","default":true,"icon":"ftp://x.example/i.png"}""",
            """{"id":"b","name":"A","summary":"S","default":true,"icon":null}""",
            """{"id":"b","name":"A","summary":"S","default":true,"min_build":0}""",
            """{"id":"b","name":"A","summary":"S","default":true,"min_build":1.5}""",
            """{"id":"b","name":"A","summary":"S","default":true,"platforms":[]}""",
            """{"id":"b","name":"A","summary":"S","default":true,"platforms":"android"}""",
            """{"id":"b","name":"A","summary":"S","default":true,"platforms":[1]}""",
            """{"id":"b","name":"A","summary":"S","default":true,"platforms":["android","android"]}""",
            """{"id":"a","name":"A","summary":"S","default":true}""", // the id twice
        )
        for (entry in bad) assertWithMessage(entry).that(refusedRule(index(addons = "[$good,$entry]"))).startsWith("addons[1]")
    }

    @Test fun `a platform word this app does not know drops out of its entry and never refuses the index`() {
        // review-885 M14: the day a later list says "ios", an older app must still take that list's blocks.
        val read = AddonIndex.parse(
            index(
                addons = """[
                    {"id":"a","name":"A","summary":"S","default":true},
                    {"id":"b","name":"B","summary":"S","manifest":"https://b.example/manifest.json","platforms":["ios","android"]},
                    {"id":"c","name":"C","summary":"S","manifest":"https://c.example/manifest.json","platforms":["ios"]}
                ]""",
                blocked = """[{"origin":"https://bad.example","reason":"harmful"}]""",
            ),
        )
        assertThat(read.blocked).hasSize(1)
        assertThat(read.addons.map { it.platforms }).containsExactly(null, listOf("android"), emptyList<String>()).inOrder()
        // No platform named: everywhere. One this app knows among them: here. None it knows: not here.
        assertThat(read.addons.map { it.offeredTo(160) }).containsExactly(true, true, false).inOrder()
    }

    @Test fun `a blocked origin is written one way only`() {
        assertThat(AddonIndex.parse(index(blocked = """[{"origin":"https://bad.example","reason":"harmful"}]""")).blocked).hasSize(1)
        for (origin in listOf(
            "http://bad.example", "https://bad.example/", "https://bad.example/path", "https://Bad.example", "https://bad.example:443",
            "https://bad.example.", "https://user@bad.example", "bad.example", "",
        )) {
            assertWithMessage(origin).that(refusedRule(index(blocked = """[{"origin":"$origin","reason":"harmful"}]"""))).isEqualTo("blocked[0].origin")
        }
        // The same origin twice, or once for all of it and once for one id.
        val twice = """[{"origin":"https://bad.example","reason":"harmful"},{"origin":"https://bad.example","reason":"broken"}]"""
        assertThat(refusedRule(index(blocked = twice))).startsWith("blocked[1]")
        val overlap = """[{"origin":"https://bad.example","id":"x","reason":"harmful"},{"origin":"https://bad.example","reason":"broken"}]"""
        assertThat(refusedRule(index(blocked = overlap))).startsWith("blocked[1]")
    }

    @Test fun `a block whose reason this app doesn't know still blocks, with no reason to give`() {
        val body = index(blocked = """[{"origin":"https://bad.example","reason":"harmful"},{"origin":"https://spam.example","id":"spammer","reason":"spam"}]""")
        val index = AddonIndex.parse(body)
        assertThat(index.blocked).containsExactly(
            AddonIndex.Block("https://bad.example", null, BlockReason.HARMFUL),
            AddonIndex.Block("https://spam.example", "spammer", BlockReason.OTHER),
        ).inOrder()
        assertThat(index.blockOf("https://spam.example", "spammer")!!.reason).isEqualTo(BlockReason.OTHER)
        // The publisher's own case for it: both add-ons are blocked, and the word from the file is not kept.
        val later = AddonIndex.parse(bytes("valid-unknown-reason/index.json"))
        assertThat(later.blocked.map { it.reason }).containsExactly(BlockReason.HARMFUL, BlockReason.OTHER).inOrder()
        assertThat(later.blockOf("https://bad.example", null)).isNotNull()
        assertThat(later.blockOf("https://spam.example", "any-id")).isNotNull()
        assertThat(later.toString()).doesNotContain("spam\"")
        // A block with no reason at all, or one that isn't text, is still a broken entry.
        assertThat(refusedRule(index(blocked = """[{"origin":"https://bad.example"}]"""))).isEqualTo("blocked[0].reason")
        assertThat(refusedRule(index(blocked = """[{"origin":"https://bad.example","reason":3}]"""))).isEqualTo("blocked[0].reason")
        assertThat(BlockReason.of("harmful")).isEqualTo(BlockReason.HARMFUL)
        assertThat(BlockReason.of("anything-else")).isEqualTo(BlockReason.OTHER)
    }

    @Test fun `an add-on that is listed and blocked at once refuses the index`() {
        val listed = """[{"id":"radio","name":"R","summary":"S","manifest":"https://x.example/radio/manifest.json"}]"""
        assertThat(refusedRule(index(addons = listed, blocked = """[{"origin":"https://x.example","reason":"broken"}]"""))).contains("blocked")
        // Another id on that origin being blocked is no contradiction.
        assertThat(AddonIndex.parse(index(addons = listed, blocked = """[{"origin":"https://x.example","id":"other","reason":"broken"}]""")).blocked).hasSize(1)
    }

    @Test fun `the head rules - type, v, updated_at, both lists`() {
        assertThat(refusedRule(index(head = """"v":1,"updated_at":5"""))).isEqualTo("type")
        assertThat(refusedRule(index(head = """"type":"stash-addon-index","v":"1","updated_at":5"""))).isEqualTo("v")
        assertThat(refusedRule(index(head = """"type":"stash-addon-index","v":1,"updated_at":0"""))).isEqualTo("updated_at")
        assertThat(refusedRule(index(head = """"type":"stash-addon-index","v":1,"updated_at":"5""""))).isEqualTo("updated_at")
        assertThat(refusedRule("""{"type":"stash-addon-index","v":1,"updated_at":5,"addons":[]}""".toByteArray())).isEqualTo("blocked")
        assertThat(refusedRule("[]".toByteArray())).isEqualTo("not a JSON object")
        assertThat(refusedRule("not json".toByteArray())).isEqualTo("not JSON")
        assertThat(refusedRule(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + index())).isEqualTo("not JSON")
        assertThat(refusedRule(byteArrayOf(0xC3.toByte(), 0x28))).isEqualTo("not UTF-8")
        // A whole number written another way is still that number, as it is for the publisher.
        assertThat(AddonIndex.parse(index(head = """"type":"stash-addon-index","v":1.0,"updated_at":5e0""")).updatedAt).isEqualTo(5L)
    }

    @Test fun `a path part that looks like a token is told from a plain one as the publisher does`() {
        for (plain in listOf("radio", "v1", "my-addon", "tok-123", "a.b")) assertWithMessage(plain).that(AddonIndex.looksLikeToken(plain)).isFalse()
        for (token in listOf("a1b2c3d4", "12345678", "abcdefghijklmnopqrst", "Upper", "under_score", "x".repeat(33), "")) {
            assertWithMessage(token).that(AddonIndex.looksLikeToken(token)).isTrue()
        }
    }
}
