package com.stash.core.data.weblink.remote

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.stash.core.data.weblink.SyncVectors
import com.stash.core.data.weblink.handoff.HandoffWire
import com.stash.core.data.weblink.handoff.HandoffWireException
import com.stash.core.data.weblink.handoff.WireSong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.fail
import org.junit.Test

/**
 * sync-v1 §5.7 against `remote-vectors.json`, a byte-for-byte copy of stash-player `src/lib/sync/fixtures/` (the copy rule,
 * docs/sync-v1.md §9: the web's `remote.test.ts` pins the same SHA-256). Every reader on both sides, the phone's writer. Remote v2
 * (spec 2026-11-03 §4): the driving fields of a state, and `load`, `upNext`, `jump`, `stop` and `release`.
 */
class RemoteVectorsTest {
    private val raw = SyncVectors.bytes("remote-vectors.json")
    private val v = Json.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject

    @Test fun `the file is the pinned copy`() {
        assertThat(SyncVectors.sha256(raw)).isEqualTo(REMOTE_SHA256)
    }

    @Test fun `places`() {
        val st = v["places"]!!.jsonObject["state"]!!.jsonObject
        val cmd = v["places"]!!.jsonObject["cmd"]!!.jsonObject
        assertThat(RemoteWire.statePlace(st.s("from"))).isEqualTo(st.s("place"))
        assertThat(RemoteWire.cmdPlace(cmd.s("from"), cmd.s("to"))).isEqualTo(cmd.s("place"))
    }

    @Test fun `states read as the vectors say, or are refused`() {
        for (c in v["states"]!!.jsonArray.map { it.jsonObject }) {
            val name = c.s("name")
            val doc = c["doc"].toString()
            val error = c["error"]?.jsonPrimitive?.content
            if (error != null) {
                refused(name, error) { RemoteWire.readState(doc) }
                continue
            }
            val r = c["read"]!!.jsonObject
            val s = RemoteWire.readState(doc)
            assertWithMessage(name).that(s.sid).isEqualTo(r.s("sid"))
            assertWithMessage(name).that(s.needsGesture).isEqualTo(r["needsGesture"]!!.jsonPrimitive.booleanOrNull)
            assertWithMessage(name).that(s.playing).isEqualTo(r["playing"]!!.jsonPrimitive.booleanOrNull)
            assertWithMessage(name).that(s.positionMs).isEqualTo(r["positionMs"]!!.jsonPrimitive.longOrNull)
            assertWithMessage(name).that(s.durationMs).isEqualTo(r["durationMs"]!!.jsonPrimitive.longOrNull)
            assertWithMessage(name).that(s.rate).isEqualTo(r["rate"]!!.jsonPrimitive.doubleOrNull)
            assertWithMessage(name).that(s.volume).isEqualTo(r["volume"].numOrNull())
            assertWithMessage(name).that(s.canSeek).isEqualTo(r["canSeek"]!!.jsonPrimitive.booleanOrNull)
            assertWithMessage(name).that(s.title).isEqualTo(r["title"].strOrNull())
            assertWithMessage(name).that(s.artist).isEqualTo(r["artist"].strOrNull())
            assertWithMessage(name).that(s.art).isEqualTo(r["art"].strOrNull())
            // Remote v2: the driving fields.
            assertWithMessage("$name: caps").that(s.caps).containsExactlyElementsIn(r["caps"]!!.jsonArray.map { it.jsonPrimitive.content }).inOrder()
            assertWithMessage("$name: driver").that(s.driver).isEqualTo(r["driver"].strOrNull())
            assertWithMessage("$name: iid").that(s.iid).isEqualTo(r["iid"].strOrNull())
            assertWithMessage("$name: next").that(s.next).isEqualTo(r["next"].strOrNull())
            assertWithMessage("$name: phase").that(s.phase?.wire).isEqualTo(r["phase"].strOrNull())
            assertWithMessage("$name: pendingPlay").that(s.pendingPlay).isEqualTo(r["pendingPlay"]!!.jsonPrimitive.booleanOrNull)
            assertWithMessage("$name: canDrive").that(s.canDrive).isEqualTo(RemoteWire.CAP_DRIVE in s.caps)
        }
    }

    @Test fun `commands read as the vectors say, an unknown one is ignored, a bad one refused`() {
        for (c in v["cmds"]!!.jsonArray.map { it.jsonObject }) {
            val name = c.s("name")
            val doc = c["doc"].toString()
            val error = c["error"]?.jsonPrimitive?.content
            if (error != null) {
                refused(name, error) { RemoteWire.readCmd(doc) }
                continue
            }
            val r = c["read"]!!
            val got = RemoteWire.readCmd(doc)
            if (r is JsonNull) {
                assertWithMessage(name).that(got).isNull()
                continue
            }
            val o = r.jsonObject
            assertWithMessage(name).that(got).isNotNull()
            assertWithMessage(name).that(got!!.sid).isEqualTo(o.s("sid"))
            assertWithMessage(name).that(got.n).isEqualTo(o["n"]!!.jsonPrimitive.longOrNull)
            assertWithMessage(name).that(got.command.wire).isEqualTo(o.s("cmd"))
            when (val x = got.command) {
                is RemoteCommand.Seek -> assertWithMessage(name).that(x.positionMs).isEqualTo(o["positionMs"]!!.jsonPrimitive.longOrNull)
                is RemoteCommand.Volume -> assertWithMessage(name).that(x.volume).isEqualTo(o["volume"].numOrNull())
                is RemoteCommand.Load -> {
                    val item = o["item"]!!.jsonObject
                    assertWithMessage("$name: id").that(x.item.id).isEqualTo(item.s("id"))
                    assertWithMessage("$name: song").that(view(x.item.song)).isEqualTo(item["song"])
                    assertWithMessage("$name: positionMs").that(x.positionMs).isEqualTo(o["positionMs"]!!.jsonPrimitive.longOrNull)
                    assertWithMessage("$name: play").that(x.play).isEqualTo(o["play"]!!.jsonPrimitive.booleanOrNull)
                    assertWithMessage("$name: from").that(x.from).isEqualTo(o["from"].strOrNull())
                }
                is RemoteCommand.UpNext -> {
                    val items = o["items"]!!.jsonArray.map { it.jsonObject }
                    assertWithMessage("$name: ids").that(x.items.map { it.id }).containsExactlyElementsIn(items.map { it.s("id") }).inOrder()
                    assertWithMessage("$name: songs").that(x.items.map { view(it.song) }).containsExactlyElementsIn(items.map { it["song"] }).inOrder()
                }
                is RemoteCommand.Jump -> assertWithMessage(name).that(x.id).isEqualTo(o.s("id"))
                is RemoteCommand.Release -> assertWithMessage(name).that(x.pause).isEqualTo(o["pause"]!!.jsonPrimitive.booleanOrNull)
                RemoteCommand.Play, RemoteCommand.Pause, RemoteCommand.Next, RemoteCommand.Prev, RemoteCommand.Stop ->
                    // Nothing else is read for these, whatever rides beside them.
                    assertWithMessage(name).that(o.keys).containsExactly("sid", "n", "cmd")
            }
        }
    }

    @Test fun `the phone writes commands exactly as the vectors do`() {
        val cmds = v["writes"]!!.jsonArray.map { it.jsonObject }.filter { it["doc"]!!.jsonObject.s("kind") == RemoteWire.CMD_KIND }
        assertThat(cmds.map { it["in"]!!.jsonObject.s("cmd") }.toSet())
            .containsAtLeast("seek", "play", "load", "upNext", "jump", "stop", "release")
        for (c in cmds) {
            val i = c["in"]!!.jsonObject
            val command = commandOf(i)
            val written = RemoteWire.cmdJson(RemoteCmd(i.s("sid"), i["n"]!!.jsonPrimitive.longOrNull!!, command))
            assertWithMessage(c.s("name")).that(Json.parseToJsonElement(written)).isEqualTo(c["doc"])
            // And reads back to what went in.
            assertWithMessage(c.s("name")).that(RemoteWire.readCmd(written)!!.command).isEqualTo(command)
        }
    }

    /** A vector's `in` as the command this phone would send. */
    private fun commandOf(i: JsonObject): RemoteCommand = when (i.s("cmd")) {
        "seek" -> RemoteCommand.Seek(i["positionMs"]!!.jsonPrimitive.longOrNull!!)
        "volume" -> RemoteCommand.Volume(i["volume"].numOrNull()!!)
        "play" -> RemoteCommand.Play
        "pause" -> RemoteCommand.Pause
        "next" -> RemoteCommand.Next
        "prev" -> RemoteCommand.Prev
        "load" -> RemoteCommand.Load(
            item = itemOf(i["item"]!!.jsonObject),
            positionMs = i["positionMs"]!!.jsonPrimitive.longOrNull!!,
            play = i["play"]!!.jsonPrimitive.booleanOrNull!!,
            from = i["from"].strOrNull(),
        )
        "upNext" -> RemoteCommand.UpNext(i["items"]!!.jsonArray.map { itemOf(it.jsonObject) })
        "jump" -> RemoteCommand.Jump(i.s("id"))
        "stop" -> RemoteCommand.Stop
        "release" -> RemoteCommand.Release(i["pause"]!!.jsonPrimitive.booleanOrNull!!)
        else -> error("no writer for ${i.s("cmd")}")
    }

    /** The phone's songs are its §2.1 Songs, read back as the handoff reads them (cover sizes kept). */
    private fun itemOf(o: JsonObject) = RemoteItem(o.s("id"), HandoffWire.readSong(o["song"])!!)

    /** The vectors' view of a song in a read command (their "about"). */
    private fun view(s: WireSong?): JsonElement = if (s == null) {
        JsonNull
    } else {
        buildJsonObject {
            put("title", s.title)
            put("artist", s.artist)
            put("album", s.album)
            put("durationMs", s.durationMs ?: 0L)
            put("isrc", s.isrc)
            put("youtube", s.youtubeId)
            put("art", s.artwork.firstOrNull())
        }
    }

    private fun refused(name: String, error: String, read: () -> Unit) {
        try {
            read()
            fail("$name: expected a refusal")
        } catch (e: HandoffWireException) {
            assertWithMessage(name).that(e.newer).isEqualTo(error == "newer")
        }
    }

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonElement?.strOrNull(): String? = if (this == null || this is JsonNull) null else jsonPrimitive.content
    private fun JsonElement?.numOrNull(): Double? = if (this == null || this is JsonNull) null else jsonPrimitive.doubleOrNull

    private companion object {
        /**
         * stash-player `src/lib/sync/remote.test.ts` pins the same bytes (feat/remote-drive e9694691, Remote v2). Change both repos
         * and both pins together, or neither.
         */
        const val REMOTE_SHA256 = "eee899ddc56c3e02cc8d4a142f27643c2289097fdfd08b5f434dffce3e1053a7"
    }
}
