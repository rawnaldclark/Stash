package com.stash.feature.record.bridge

import com.google.common.truth.Truth.assertThat
import com.stash.feature.record.archive.ArchivePlay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test

/**
 * The bridge's messages. Reading runs the web player's own `readAsk` cases (src/lib/ui/spotify/record.test.ts) on the JSON
 * the page sends; writing must come out as `JSON.stringify` writes it.
 */
class RecordMessagesTest {
    private fun read(json: String) = RecordMessages.read(json)
    private fun s(title: String, artist: String = "A", album: String? = null) =
        """{"title":"$title","artist":"$artist"${album?.let { ""","album":"$it"""" } ?: ""}}"""

    // ── readAsk ─────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `reads the asks`() {
        assertThat(read("""{"type":"stash:import"}""")).isEqualTo(RecordAsk.Import)
        assertThat(read("""{"type":"stash:close"}""")).isEqualTo(RecordAsk.Close)
        assertThat(read("""{"type":"stash:close","anything":"else"}""")).isEqualTo(RecordAsk.Close)
        assertThat(read("""{"type":"stash:ready","plays":1234}""")).isEqualTo(RecordAsk.Ready(1234))
        assertThat(read("""{"type":"stash:play","songs":[${s("One", "X", "LP")}],"index":0,"from":"The Record · 3 March 2024"}"""))
            .isEqualTo(RecordAsk.Play(listOf(RecordSong("One", "X", "LP")), 0, "The Record · 3 March 2024"))
        assertThat(read("""{"type":"stash:hello","v":1}""")).isEqualTo(RecordAsk.Hello(1))
    }

    @Test fun `drops anything else, other types, wrong shapes, no data`() {
        for (d in listOf(
            "null", "\"stash:import\"", "42", "[]", "{}", """{"type":"stash:reload"}""", """{"type":"stash:theme","theme":"dark"}""",
            """{"type":"play"}""", """{"type":"stash:play"}""", """{"type":"stash:play","songs":"x"}""", """{"type":"stash:play","songs":{}}""",
            "not json", "", """{"type":"stash:hello"}""", """{"type":"stash:hello","v":"1"}""", """{"type":"stash:hello","v":0}""",
            """{"type":"stash:hello","v":1.5}""", """{"type":"stash:live","plays":[]}""",
        )) {
            assertThat(read(d)).isNull()
        }
    }

    @Test fun `a ready with a bad count says 0 plays`() {
        for (plays in listOf("", ",\"plays\":-3", ",\"plays\":\"12\"", ",\"plays\":null", ",\"plays\":true")) {
            assertThat(read("""{"type":"stash:ready"$plays}""")).isEqualTo(RecordAsk.Ready(0))
        }
        assertThat(read("""{"type":"stash:ready","plays":7.9}""")).isEqualTo(RecordAsk.Ready(7))
        assertThat(read("""{"type":"stash:ready","plays":1e30}""")).isEqualTo(RecordAsk.Ready(Int.MAX_VALUE))
    }

    @Test fun `a song needs a title and an artist, a bad one is dropped and the index still names the song it named`() {
        val songs = listOf(s("One"), """{"title":"","artist":"A"}""", "null", """{"title":3,"artist":"A"}""", s("Two"), """{"title":"Three"}""", s("Four")).joinToString(",")
        val a = read("""{"type":"stash:play","songs":[$songs],"index":4,"from":"x"}""") as RecordAsk.Play
        assertThat(a.songs.map { it.title }).containsExactly("One", "Two", "Four").inOrder()
        assertThat(a.songs[a.index].title).isEqualTo("Two")
        // pointing at a bad song: the next good one
        val b = read("""{"type":"stash:play","songs":[$songs],"index":5,"from":"x"}""") as RecordAsk.Play
        assertThat(b.songs[b.index].title).isEqualTo("Four")
        assertThat(read("""{"type":"stash:play","songs":[null,{"title":" ","artist":"A"}],"index":0,"from":"x"}""")).isNull()
    }

    @Test fun `an index out of range or not a whole number is held to the list`() {
        fun at(index: String) = (read("""{"type":"stash:play","songs":[${s("One")},${s("Two")}]$index,"from":""}""") as RecordAsk.Play).index
        assertThat(at(",\"index\":-5")).isEqualTo(0)
        assertThat(at(",\"index\":99")).isEqualTo(1)
        assertThat(at(",\"index\":1.5")).isEqualTo(0)
        assertThat(at(",\"index\":\"1\"")).isEqualTo(0)
        assertThat(at("")).isEqualTo(0)
        assertThat(at(",\"index\":1.0")).isEqualTo(1)
    }

    @Test fun `caps the list and cuts every string, control characters go`() {
        val many = (0 until RecordMessages.MAX_SONGS + 500).joinToString(",") { s("S$it") }
        val a = read("""{"type":"stash:play","songs":[$many],"index":${RecordMessages.MAX_SONGS + 100},"from":"${"f".repeat(1000)}"}""") as RecordAsk.Play
        assertThat(a.songs).hasSize(RecordMessages.MAX_SONGS)
        assertThat(a.index).isEqualTo(RecordMessages.MAX_SONGS - 1)
        assertThat(a.from).hasLength(RecordMessages.MAX_FROM)
        val long = read("""{"type":"stash:play","songs":[{"title":"${"t".repeat(10_000)}","artist":"Ar\u0000t\nist","album":" \u0007 "}],"index":0,"from":""}""") as RecordAsk.Play
        assertThat(long.songs[0].title).hasLength(RecordMessages.MAX_TEXT)
        assertThat(long.songs[0].artist).isEqualTo("Ar t ist")
        assertThat(long.songs[0].album).isNull()
        assertThat(long.from).isEqualTo("Your record")
    }

    @Test fun `keeps only the fields it knows`() {
        val a = read("""{"type":"stash:play","songs":[{"title":"One","artist":"A","key":"evil","refs":{"x":1},"__proto__":{"polluted":1}}],"index":0,"from":"x","extra":1}""")
        assertThat(a).isEqualTo(RecordAsk.Play(listOf(RecordSong("One", "A")), 0, "x"))
    }

    @Test fun `reads counts, every source a whole number, a count of the wrong shape is 0, counts that aren't an object are nothing`() {
        assertThat(read("""{"type":"stash:counts","counts":{"spotify":6086,"stash":12.7,"lastfm":400}}""")).isEqualTo(RecordAsk.Counts(6086, 12, 400))
        assertThat(read("""{"type":"stash:counts","counts":{"spotify":-1,"stash":"9","evil":5}}""")).isEqualTo(RecordAsk.Counts(0, 0, 0))
        assertThat(read("""{"type":"stash:counts"}""")).isNull()
        assertThat(read("""{"type":"stash:counts","counts":"all"}""")).isNull()
    }

    @Test fun `reads what covers the record asks for, songs and albums by their words, artists alone, nothing else`() {
        val a = read(
            """{"type":"stash:art","asks":[{"id":"Ivy|Frank Ocean","artist":"Frank Ocean","title":"Ivy","album":"Blonde"},""" +
                """{"id":"Frank Ocean","artist":"Frank Ocean"},{"id":"","artist":"x"},{"id":"no artist","title":"x"},"junk"]}""",
        )
        assertThat(a).isEqualTo(
            RecordAsk.Art(listOf(RecordArtAsk("Ivy|Frank Ocean", "Frank Ocean", "Ivy", "Blonde"), RecordArtAsk("Frank Ocean", "Frank Ocean"))),
        )
        assertThat((a as RecordAsk.Art).asks[1].isArtist).isTrue()
        assertThat(read("""{"type":"stash:art","asks":[]}""")).isNull()
        assertThat(read("""{"type":"stash:art","asks":"nope"}""")).isNull()
        val many = (0 until 400).joinToString(",") { """{"id":"a$it","artist":"A$it"}""" }
        assertThat((read("""{"type":"stash:art","asks":[$many]}""") as RecordAsk.Art).asks).hasSize(150)
        // an id longer than a song's two names isn't one of the record's
        assertThat(read("""{"type":"stash:art","asks":[{"id":"${"i".repeat(602)}","artist":"A"}]}""")).isNull()
    }

    @Test fun `reads a file's chunk, never trusting it`() {
        val ok = RecordAsk.Save("0b9a-4c", "Your record.png", "image/png", share = false, seq = 2, done = true, data = "AAEC")
        fun save(extra: String) = read("""{"type":"stash:save","id":"0b9a-4c","name":"Your record.png","mime":"image/png","action":"save","seq":2,"done":true,"data":"AAEC"$extra}""")
        assertThat(save("")).isEqualTo(ok)
        assertThat(read("""{"type":"stash:save","id":"x","name":"race","mime":"video/mp4","action":"share","seq":0,"done":false,"data":""}"""))
            .isEqualTo(RecordAsk.Save("x", "race", "video/mp4", share = true, seq = 0, done = false, data = ""))
        // the page's fallback type for a file it couldn't type
        assertThat((read("""{"type":"stash:save","id":"x","name":"f","mime":"application/octet-stream","action":"save","seq":0,"done":true,"data":""}""") as RecordAsk.Save).mime)
            .isEqualTo("application/octet-stream")
        for (bad in listOf(
            """{"type":"stash:save","id":"","name":"n","mime":"image/png","action":"save","seq":0,"done":true,"data":""}""",
            """{"type":"stash:save","id":"x","name":" ","mime":"image/png","action":"save","seq":0,"done":true,"data":""}""",
            """{"type":"stash:save","id":"x","name":"n","mime":"text/html","action":"save","seq":0,"done":true,"data":""}""",
            """{"type":"stash:save","id":"x","name":"n","mime":"image/png","action":"open","seq":0,"done":true,"data":""}""",
            """{"type":"stash:save","id":"x","name":"n","mime":"image/png","action":"save","seq":-1,"done":true,"data":""}""",
            """{"type":"stash:save","id":"x","name":"n","mime":"image/png","action":"save","seq":1.5,"done":true,"data":""}""",
            """{"type":"stash:save","id":"x","name":"n","mime":"image/png","action":"save","seq":0,"done":"true","data":""}""",
            """{"type":"stash:save","id":"x","name":"n","mime":"image/png","action":"save","seq":0,"done":true,"data":3}""",
            """{"type":"stash:save","id":"x","name":"n","mime":"image/png","action":"save","seq":0,"done":true}""",
        )) {
            assertThat(read(bad)).isNull()
        }
        // a chunk is at most 524,286 bytes (the page's SAVE_CHUNK_BYTES): its base64, 699,048 characters, is taken
        val full = "A".repeat(699_048)
        assertThat(read("""{"type":"stash:save","id":"x","name":"n","mime":"image/png","action":"save","seq":0,"done":false,"data":"$full"}""")).isNotNull()
        val over = "A".repeat(699_056)
        assertThat(read("""{"type":"stash:save","id":"x","name":"n","mime":"image/png","action":"save","seq":0,"done":false,"data":"$over"}""")).isNull()
    }

    @Test fun `a message too big to be one of the record's is dropped unread`() {
        assertThat(read(" ".repeat(RecordMessages.MAX_MESSAGE + 1))).isNull()
    }

    // ── Host → record ───────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `an archive chunk is the rows as the web keeps them, absent fields left out`() {
        val rows = listOf(
            ArchivePlay(id = "ivy|frank ocean|1", t = "Ivy", a = "Frank Ocean", al = "Blonde", s = 1, ms = 249_000, f = 256),
            ArchivePlay(id = "x|y|2", t = "X", a = "Y", sid = "0123456789abcdefABCDEF", s = 2, ms = 210_000, f = 384),
        )
        val m = Json.parseToJsonElement(RecordMessages.archive(rows, seq = 3, done = true)).jsonObject
        assertThat(m["type"]!!.jsonPrimitive.content).isEqualTo("stash:archive")
        assertThat(m["seq"]!!.jsonPrimitive.long).isEqualTo(3)
        assertThat(m["done"]!!.jsonPrimitive.content).isEqualTo("true")
        val r = m["rows"]!!.jsonArray
        assertThat(r[0].jsonObject.keys).containsExactly("id", "t", "a", "al", "s", "ms", "f")
        assertThat(r[1].jsonObject.keys).containsExactly("id", "t", "a", "sid", "s", "ms", "f")
        assertThat(r[1].jsonObject["f"]!!.jsonPrimitive.long).isEqualTo(384)
        assertThat(Json.parseToJsonElement(RecordMessages.archive(emptyList(), 0, true)).jsonObject["rows"]).isEqualTo(JsonArray(emptyList()))
    }

    @Test fun `strings are written as JSON stringify writes them, a lone surrogate escaped`() {
        val tricky = "q\"b\\s/\b\u000C\n\r\t\u0001\u007F é🎵" + "\uD83C"
        val out = StringBuilder().apply { with(RecordMessages) { quote(tricky) } }.toString()
        assertThat(out).isEqualTo("\"q\\\"b\\\\s/\\b\\f\\n\\r\\t\\u0001\u007F é🎵\\ud83c\"")
        // and it reads back as it was: a title cut through a pair keeps its id across the bridge
        val back = Json.parseToJsonElement("{\"t\":$out}").jsonObject["t"]!!.jsonPrimitive.content
        assertThat(back).isEqualTo(tricky)
    }

    @Test fun `theme, back and covers`() {
        assertThat(RecordMessages.theme(light = true)).isEqualTo("""{"type":"stash:theme","theme":"light"}""")
        assertThat(RecordMessages.theme(light = false)).isEqualTo("""{"type":"stash:theme","theme":"dark"}""")
        assertThat(RecordMessages.BACK).isEqualTo("""{"type":"stash:back"}""")
        val c = Json.parseToJsonElement(RecordMessages.covers(listOf("Ivy|Frank Ocean" to "https://x/a.jpg", "Nobody" to null))).jsonObject
        assertThat(c["type"]!!.jsonPrimitive.content).isEqualTo("stash:covers")
        val list = c["covers"]!!.jsonArray.map { it as JsonObject }
        assertThat(list[0]["url"]!!.jsonPrimitive.content).isEqualTo("https://x/a.jpg")
        assertThat(list[1]["url"]).isEqualTo(JsonNull)
    }

    @Test fun `save-abort and backed in, save-ack out`() {
        assertThat(read("""{"type":"stash:save-abort","id":"u-1"}""")).isEqualTo(RecordAsk.SaveAbort("u-1"))
        assertThat(read("""{"type":"stash:save-abort"}""")).isNull()
        assertThat(read("""{"type":"stash:save-abort","id":""}""")).isNull()
        assertThat(read("""{"type":"stash:save-abort","id":"${"x".repeat(129)}"}""")).isNull()
        assertThat(read("""{"type":"stash:backed"}""")).isEqualTo(RecordAsk.Backed)
        val ack = Json.parseToJsonElement(RecordMessages.saveAck("u-\"1", 7)).jsonObject
        assertThat(ack["type"]!!.jsonPrimitive.content).isEqualTo("stash:save-ack")
        assertThat(ack["id"]!!.jsonPrimitive.content).isEqualTo("u-\"1")
        // a number, not a string: the page ignores a seq that isn't one
        assertThat(ack["seq"]!!.jsonPrimitive.isString).isFalse()
        assertThat(ack["seq"]!!.jsonPrimitive.int).isEqualTo(7)
    }
}
