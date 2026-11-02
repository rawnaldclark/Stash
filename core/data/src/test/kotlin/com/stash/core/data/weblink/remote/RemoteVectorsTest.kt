package com.stash.core.data.weblink.remote

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.weblink.SyncVectors
import com.stash.core.data.weblink.handoff.HandoffWireException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * sync-v1 §5.7 against `remote-vectors.json`, a byte-for-byte copy of stash-player `src/lib/sync/fixtures/` (the copy rule,
 * docs/sync-v1.md §9: the web's `remote.test.ts` pins the same SHA-256). Every reader on both sides, the phone's writer.
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
            assertThat(s.sid).named(name).isEqualTo(r.s("sid"))
            assertThat(s.needsGesture).named(name).isEqualTo(r["needsGesture"]!!.jsonPrimitive.booleanOrNull)
            assertThat(s.playing).named(name).isEqualTo(r["playing"]!!.jsonPrimitive.booleanOrNull)
            assertThat(s.positionMs).named(name).isEqualTo(r["positionMs"]!!.jsonPrimitive.longOrNull)
            assertThat(s.durationMs).named(name).isEqualTo(r["durationMs"]!!.jsonPrimitive.longOrNull)
            assertThat(s.rate).named(name).isEqualTo(r["rate"]!!.jsonPrimitive.doubleOrNull)
            assertThat(s.volume).named(name).isEqualTo(r["volume"].numOrNull())
            assertThat(s.canSeek).named(name).isEqualTo(r["canSeek"]!!.jsonPrimitive.booleanOrNull)
            assertThat(s.title).named(name).isEqualTo(r["title"].strOrNull())
            assertThat(s.artist).named(name).isEqualTo(r["artist"].strOrNull())
            assertThat(s.art).named(name).isEqualTo(r["art"].strOrNull())
        }
    }

    @Test fun `commands read as the vectors say; an unknown one is ignored, a bad one refused`() {
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
                assertThat(got).named(name).isNull()
                continue
            }
            val o = r.jsonObject
            assertThat(got).named(name).isNotNull()
            assertThat(got!!.sid).named(name).isEqualTo(o.s("sid"))
            assertThat(got.n).named(name).isEqualTo(o["n"]!!.jsonPrimitive.longOrNull)
            assertThat(got.command.wire).named(name).isEqualTo(o.s("cmd"))
            when (val x = got.command) {
                is RemoteCommand.Seek -> assertThat(x.positionMs).named(name).isEqualTo(o["positionMs"]!!.jsonPrimitive.longOrNull)
                is RemoteCommand.Volume -> assertThat(x.volume).named(name).isEqualTo(o["volume"].numOrNull())
                else -> Unit
            }
        }
    }

    @Test fun `the phone writes commands exactly as the vectors do`() {
        val cmds = v["writes"]!!.jsonArray.map { it.jsonObject }.filter { it["doc"]!!.jsonObject.s("kind") == RemoteWire.CMD_KIND }
        assertThat(cmds).isNotEmpty()
        for (c in cmds) {
            val i = c["in"]!!.jsonObject
            val command = when (i.s("cmd")) {
                "seek" -> RemoteCommand.Seek(i["positionMs"]!!.jsonPrimitive.longOrNull!!)
                "volume" -> RemoteCommand.Volume(i["volume"].numOrNull()!!)
                "play" -> RemoteCommand.Play
                "pause" -> RemoteCommand.Pause
                "next" -> RemoteCommand.Next
                else -> RemoteCommand.Prev
            }
            val written = RemoteWire.cmdJson(RemoteCmd(i.s("sid"), i["n"]!!.jsonPrimitive.longOrNull!!, command))
            assertThat(Json.parseToJsonElement(written)).isEqualTo(c["doc"])
            // And reads back to what went in.
            assertThat(RemoteWire.readCmd(written)!!.command).isEqualTo(command)
        }
    }

    private fun refused(name: String, error: String, read: () -> Unit) {
        try {
            read()
            fail("$name: expected a refusal")
        } catch (e: HandoffWireException) {
            assertThat(e.newer).named(name).isEqualTo(error == "newer")
        }
    }

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonElement?.strOrNull(): String? = if (this == null || this is JsonNull) null else jsonPrimitive.content
    private fun JsonElement?.numOrNull(): Double? = if (this == null || this is JsonNull) null else jsonPrimitive.doubleOrNull

    private companion object {
        /** stash-player `src/lib/sync/remote.test.ts` pins the same bytes. Change both repos and both pins together, or neither. */
        const val REMOTE_SHA256 = "c903a0249ba23e435f48dee7fdfbb3e12faf2ed3817140c7024f37ff5022c9aa"
    }
}
