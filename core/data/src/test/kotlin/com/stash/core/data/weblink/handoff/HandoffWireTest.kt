package com.stash.core.data.weblink.handoff

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/** The handoff documents (sync-v1 §5.1): written as the contract says, read strictly. */
class HandoffWireTest {
    private val song = WireSong(
        title = "Pink + White",
        artist = "Frank Ocean",
        album = "Blonde",
        durationMs = 184_000,
        isrc = "USUM71607007",
        refs = mapOf("youtube" to "uzS3WG6__G4"),
        artwork = listOf("https://i.scdn.co/image/abc"),
    )

    @Test
    fun `now and queue round-trip`() {
        val now = StashNow(true, 151_000, 1.0, 7, "q_AAAAAAAAAAAAAAAAAAAAAA", song, false, HandoffRepeat.ALL, "Night drive", 2)
        assertThat(HandoffWire.readNow(HandoffWire.nowJson(now))).isEqualTo(now)
        val q = StashQueue("q_AAAAAAAAAAAAAAAAAAAAAA", listOf(song, song.copy(title = "Ivy", phoneOnly = true)), 1, 0, listOf(1, 0))
        assertThat(HandoffWire.readQueue(HandoffWire.queueJson(q))).isEqualTo(q)
    }

    @Test
    fun `phoneOnly is written only when true, absent optionals are left out`() {
        val text = HandoffWire.songJson(WireSong("A", "B")).toString()
        assertThat(text).isEqualTo("""{"title":"A","artist":"B"}""")
        assertThat(HandoffWire.songJson(WireSong("A", "B", phoneOnly = true)).toString()).contains("\"phoneOnly\":true")
    }

    @Test
    fun `a newer version is refused as newer, another kind as wrong, unknown fields ignored`() {
        val e = assertThrows(HandoffWireException::class.java) {
            HandoffWire.readNow("""{"kind":"stash-now","v":2,"playing":true,"positionMs":0,"index":0,"queueId":"q_x"}""")
        }
        assertThat(e.newer).isTrue()
        assertThrows(HandoffWireException::class.java) { HandoffWire.readNow("""{"kind":"stash-queue","v":1}""") }
        val n = HandoffWire.readNow("""{"kind":"stash-now","v":1,"playing":false,"positionMs":5,"index":0,"queueId":"q_x","shuffle":false,"repeat":"one","future":{"a":1}}""")
        assertThat(n.repeat).isEqualTo(HandoffRepeat.ONE)
        assertThat(n.song).isNull()
    }

    @Test
    fun `values are checked by JSON type`() {
        assertThrows(HandoffWireException::class.java) {
            HandoffWire.readNow("""{"kind":"stash-now","v":1,"playing":"yes","positionMs":0,"index":0,"queueId":"q_x"}""")
        }
        assertThrows(HandoffWireException::class.java) {
            HandoffWire.readNow("""{"kind":"stash-now","v":1,"playing":true,"positionMs":"5","index":0,"queueId":"q_x"}""")
        }
        assertThrows(HandoffWireException::class.java) {
            HandoffWire.readNow("""{"kind":"stash-now","v":1,"playing":true,"positionMs":-1,"index":0,"queueId":"q_x"}""")
        }
    }

    @Test
    fun `a bad song stays a hole, a bad original is dropped, songs are cleaned`() {
        val q = HandoffWire.readQueue(
            """{"kind":"stash-queue","v":1,"id":"q_x","index":0,"offset":0,"original":[0,0,1],
               "items":[{"title":" A‮  b ","artist":"C","isrc":"bad","artwork":[{"url":"http://x/y"},{"url":"https://x/z"}],"refs":{"youtube":"abc","bad key":"x"}},
                        {"title":"","artist":"D"},
                        {"title":"E","artist":"F","durationMs":90000000}]}""",
        )
        assertThat(q.items).hasSize(3)
        assertThat(q.items[0]!!.title).isEqualTo("A b")
        assertThat(q.items[0]!!.isrc).isNull()
        assertThat(q.items[0]!!.artwork).containsExactly("https://x/z")
        assertThat(q.items[0]!!.refs).containsExactly("youtube", "abc")
        assertThat(q.items[1]).isNull()
        assertThat(q.items[2]!!.durationMs).isNull()
        assertThat(q.original).isNull()
    }

    @Test
    fun `an index outside the items is refused`() {
        assertThrows(HandoffWireException::class.java) {
            HandoffWire.readQueue("""{"kind":"stash-queue","v":1,"id":"q_x","index":3,"items":[{"title":"A","artist":"B"}]}""")
        }
    }
}
