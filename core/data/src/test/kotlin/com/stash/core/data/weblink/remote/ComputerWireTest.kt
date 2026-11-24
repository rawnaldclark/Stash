package com.stash.core.data.weblink.remote

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.weblink.handoff.HandoffWireException
import com.stash.core.data.weblink.handoff.WireSong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertThrows
import org.junit.Test

/** The documents of a computer's remote, as Stash on a computer writes and reads them (`stash_core::link::remote`). */
class ComputerWireTest {
    /** A state as the computer's `state_doc` writes it. */
    private val state = """
        {"kind":"stash-remote-state","v":1,"sid":"5UcTxxl5nneqSLyw","needsGesture":false,"playing":true,"positionMs":149,
         "durationMs":184000,"rate":1,"volume":0.35,"canSeek":true,"caps":["queue"],"shuffle":false,"repeat":"one",
         "song":{"title":"Pink + White","artist":"Frank Ocean","album":"Blonde","durationMs":184000,
                 "artwork":[{"url":"https://x/b.jpg"}]},
         "phase":"playing","iid":"q1","from":"Phone queue",
         "queue":{"at":3,"length":9,"items":[
            {"iid":"q1","song":{"title":"Pink + White","artist":"Frank Ocean"}},
            {"iid":"q2","song":{"title":"Nights","artist":"Frank Ocean"}},
            {"iid":"q2","song":{"title":"Twice","artist":"Frank Ocean"}},
            {"iid":"bad id!","song":{"title":"Solo","artist":"Frank Ocean"}},
            {"iid":"q5","song":{"title":"   ","artist":"Frank Ocean"}}]},
         "unknown":"ignored"}
    """.trimIndent()

    @Test
    fun `a computer's state reads with its queue window, and bad or repeated items are left out`() {
        val s = ComputerWire.readState(state)
        assertThat(s.sid).isEqualTo("5UcTxxl5nneqSLyw")
        assertThat(s.playing).isTrue()
        assertThat(s.loading).isFalse()
        assertThat(s.volume).isEqualTo(0.35)
        assertThat(s.song?.title).isEqualTo("Pink + White")
        assertThat(s.song?.artwork).containsExactly("https://x/b.jpg")
        assertThat(s.iid).isEqualTo("q1")
        assertThat(s.at).isEqualTo(3)
        assertThat(s.length).isEqualTo(9)
        assertThat(s.items.map { it.iid to it.song.title }).containsExactly("q1" to "Pink + White", "q2" to "Nights").inOrder()
        assertThat(s.repeat).isEqualTo("one")
        assertThat(s.from).isEqualTo("Phone queue")
        assertThat(ComputerWire.keepsQueue(state)).isTrue()
    }

    @Test
    fun `another kind, a newer version or a bad field is refused`() {
        assertThrows(HandoffWireException::class.java) { ComputerWire.readState(state.replace("stash-remote-state", "stash-now")) }
        val newer = assertThrows(HandoffWireException::class.java) { ComputerWire.readState(state.replace("\"v\":1", "\"v\":2")) }
        assertThat(newer.newer).isTrue()
        assertThrows(HandoffWireException::class.java) { ComputerWire.readState(state.replace("\"playing\":true", "\"playing\":\"yes\"")) }
        assertThrows(HandoffWireException::class.java) { ComputerWire.readState(state.replace("5UcTxxl5nneqSLyw", "short")) }
    }

    @Test
    fun `a start carries the songs with ids, where to start and where it plays from`() {
        val songs = listOf(
            WireSong("Pink + White", "Frank Ocean", isrc = "USUM71607009", refs = mapOf("youtube" to "uxpDa-c-4Mc")),
            WireSong("Nights", "Frank Ocean"),
        )
        val doc = Json.parseToJsonElement(ComputerWire.cmdJson("5UcTxxl5nneqSLyw", 7, ComputerCommand.Start(songs, 5, 1200, true, "Blonde"))).jsonObject
        assertThat(doc["kind"]!!.jsonPrimitive.content).isEqualTo("stash-remote-cmd")
        assertThat(doc["cmd"]!!.jsonPrimitive.content).isEqualTo("start")
        assertThat(doc["n"]!!.jsonPrimitive.long).isEqualTo(7)
        val items = doc["items"]!!.jsonArray
        assertThat(items.map { it.jsonObject["id"]!!.jsonPrimitive.content }).containsExactly("p0", "p1").inOrder()
        assertThat(items[0].jsonObject["song"]!!.jsonObject["isrc"]!!.jsonPrimitive.content).isEqualTo("USUM71607009")
        // A start past the songs starts on the last.
        assertThat(doc["at"]!!.jsonPrimitive.int).isEqualTo(1)
        assertThat(doc["from"]!!.jsonPrimitive.content).isEqualTo("Blonde")
    }

    @Test
    fun `the queue commands name the item and its new place`() {
        fun cmd(c: ComputerCommand) = Json.parseToJsonElement(ComputerWire.cmdJson("5UcTxxl5nneqSLyw", 1, c)).jsonObject
        assertThat(cmd(ComputerCommand.Move("q4", 2)).let { it["iid"]!!.jsonPrimitive.content to it["to"]!!.jsonPrimitive.int }).isEqualTo("q4" to 2)
        assertThat(cmd(ComputerCommand.Add(listOf(WireSong("A", "B")), true))["next"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(cmd(ComputerCommand.SkipTo("q9"))["cmd"]!!.jsonPrimitive.content).isEqualTo("skipTo")
        assertThat(cmd(ComputerCommand.Volume(1.7))["volume"]!!.jsonPrimitive.content).isEqualTo("1.0")
    }
}
