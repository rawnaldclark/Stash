package com.stash.core.data.weblink.mirror

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.weblink.SyncVectors
import com.stash.core.data.weblink.handoff.HandoffWire
import com.stash.core.model.weblink.Hlc
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The mirror's shared vectors (stash-player `src/lib/sync/fixtures/mirror-vectors.json`, a byte-for-byte copy here; review S7):
 * config, ops and state read and written back, the §7.4 wait table, and the view after each batch and `since`. The web runs the
 * same file in `vectors.test.ts`. Key order is not part of the contract: documents compare as parsed values.
 */
class MirrorVectorsTest {
    private val raw = SyncVectors.bytes("mirror-vectors.json")
    private val v = Json.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject

    private fun obj(e: JsonElement?) = e!!.jsonObject
    private fun arr(e: JsonElement?) = e!!.jsonArray
    private fun parse(text: String) = Json.parseToJsonElement(text)

    @Test fun `the file is the pinned copy`() {
        assertThat(SyncVectors.sha256(raw)).isEqualTo(MIRROR_SHA256)
    }

    @Test fun `config - read, written back, and refused`() {
        for (c in arr(obj(v["config"])["read"])) {
            val doc = obj(c)["doc"].toString()
            assertThat(parse(MirrorWire.configJson(MirrorWire.readConfig(doc)))).isEqualTo(obj(c)["expect"])
        }
        for (c in arr(obj(v["config"])["errors"])) {
            val e = assertThrows(MirrorWireException::class.java) { MirrorWire.readConfig(obj(c)["doc"].toString()) }
            assertThat(e.newer).isEqualTo(obj(c)["error"]!!.jsonPrimitive.content == "newer")
        }
    }

    @Test fun `ops - read, written back`() {
        for (c in arr(obj(v["ops"])["read"])) {
            val b = MirrorWire.readOps(obj(c)["doc"].toString())
            assertThat(parse(MirrorWire.opsJson(b.device, b.ops))).isEqualTo(obj(c)["expect"])
        }
    }

    @Test fun `state - read, written back`() {
        for (c in arr(obj(v["state"])["read"])) {
            assertThat(parse(MirrorWire.stateJson(MirrorWire.readState(obj(c)["doc"].toString())))).isEqualTo(obj(c)["expect"])
        }
    }

    @Test fun `the 7_4 wait table`() {
        for (r in arr(v["wait"])) {
            val o = obj(r)
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val got = MirrorView.likesJoinWait(
                self = str("self")!!, me = str("me")!!, dir = Dir.of(str("dir")), by = str("by"), phone = str("phone"),
                browsers = arr(o["browsers"]).map { it.jsonPrimitive.content },
            )
            com.google.common.truth.Truth.assertWithMessage(str("name")!!).that(got).isEqualTo(str("expect"))
        }
    }

    private fun hlc(h: Hlc) = buildJsonArray { add(JsonPrimitive(h.wall)); add(JsonPrimitive(h.counter)); add(JsonPrimitive(h.device)) }

    /** The view as the web's test writes it. */
    private fun out(view: SpaceView): JsonElement = buildJsonObject {
        putJsonArray("likes") {
            view.likes.forEach { l -> add(buildJsonObject { put("s", HandoffWire.songJson(l.s)); put("on", l.on); put("at", hlc(l.at)); put("seq", l.seq) }) }
        }
        putJsonArray("plays") {
            view.plays.forEach { p -> add(buildJsonObject { put("s", HandoffWire.songJson(p.s)); put("playedAt", p.playedAt); put("device", p.device); put("seq", p.seq) }) }
        }
        put("clearedBefore", view.clearedBefore)
        put("clearedSeq", view.clearedSeq)
        putJsonObject("playlists") {
            view.playlists.forEach { (id, p) ->
                put(
                    id,
                    buildJsonObject {
                        put("name", p.name)
                        put("items", p.items?.let { items -> JsonArray(items.map(HandoffWire::songJson)) } ?: JsonNull)
                        p.follow?.let { f -> putJsonObject("follow") { put("id", f.id); put("version", f.version) } }
                        if (p.ro) put("ro", true)
                        put("at", hlc(p.at))
                        put("hash", p.hash)
                        put("seq", p.seq)
                    },
                )
            }
        }
        putJsonArray("joined") { view.joined.forEach { add(JsonPrimitive(it)) } }
    }

    @Test fun `the view - each batch applied in turn, then since`() {
        var view = SpaceView()
        for (step in arr(obj(v["view"])["apply"])) {
            val b = obj(obj(step)["batch"])
            val device = b["device"]!!.jsonPrimitive.content
            val doc = buildJsonObject { put("kind", "stash-mirror-ops"); put("v", 1); put("device", device); put("ops", b["ops"]!!) }
            val ops = MirrorWire.readOps(doc.toString()).ops
            view = MirrorView.applyBatch(view, device, b["seq"]!!.jsonPrimitive.long, ops, b["fromPhone"]!!.jsonPrimitive.content == "true")
            assertThat(out(view)).isEqualTo(obj(step)["expect"])
        }
        for (c in arr(obj(v["view"])["since"])) {
            val s = obj(obj(c)["since"])
            fun k(name: String) = KindConfig(Dir.BOTH, s[name]!!.jsonPrimitive.long)
            val cfg = MirrorConfig(Hlc(0, 0, "d_00000000"), likes = k("likes"), plays = k("plays"), playlists = k("playlists"))
            assertThat(out(MirrorView.sinceFilter(view, cfg))).isEqualTo(obj(c)["expect"])
        }
    }

    companion object {
        /** Pinned on both sides (stash-player `vectors.test.ts` `VECTOR_SHA256`): change both repos together, or neither. */
        const val MIRROR_SHA256 = "c07eb95fc9f1efaf27d592ca0c561800c4e89aa62f2577a10fcdeffa6bf79dfb"
    }
}
