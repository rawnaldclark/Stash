package com.stash.core.model.listen

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.share.SharedTrack
import org.junit.Test

class RoomProtocolTest {
    private val track = SharedTrack("Avril 14th", "Aphex Twin", durationMs = 125_000, isrc = "GBBPW0100025")

    @Test fun `every client message survives a round trip`() {
        val all = listOf(
            ClientMessage.Hello(name = "Rawn", hostKey = "KEY"), ClientMessage.Ping(42),
            ClientMessage.Load(track, 30_000, listOf(track)), ClientMessage.Play, ClientMessage.Pause,
            ClientMessage.Seek(90_000), ClientMessage.Queue(emptyList()), ClientMessage.Status("ready", 3),
            ClientMessage.Suggest(track), ClientMessage.SuggestionAction("s1", "add"),
            ClientMessage.React(RoomProtocol.EMOJI[0]), ClientMessage.MakeHost("m2"), ClientMessage.End,
            ClientMessage.Hello(name = "Rawn", v = 2, guestsAdd = false), ClientMessage.Load(track, 0, emptyList(), "unavailable", qv = 4),
            ClientMessage.Queue(listOf(track), qv = 7), ClientMessage.Add(listOf(track), next = true),
            ClientMessage.Unqueue(track.copy(addedBy = "m2"), at = 3), ClientMessage.Settings(guestsAdd = true),
        )
        for (m in all) {
            assertThat(RoomProtocol.json.decodeFromString(ClientMessage.serializer(), RoomProtocol.encode(m))).isEqualTo(m)
        }
    }

    @Test fun `the wire form puts the type in t and leaves out nulls`() {
        assertThat(RoomProtocol.encode(ClientMessage.Play)).isEqualTo("""{"t":"play"}""")
        assertThat(RoomProtocol.encode(ClientMessage.Hello(name = "Rawn"))).isEqualTo("""{"t":"hello","name":"Rawn"}""")
        assertThat(RoomProtocol.encode(ClientMessage.Load(track, 0, emptyList()))).isEqualTo(
            """{"t":"load","track":{"t":"Avril 14th","a":"Aphex Twin","d":125000,"isrc":"GBBPW0100025"},"positionMs":0,"queue":[]}""",
        )
    }

    @Test fun `who added a song and who did what travel as by and why, and are left out when unknown`() {
        assertThat(RoomProtocol.encode(ClientMessage.Load(track.copy(addedBy = "M2"), 0, emptyList(), why = "skip"))).isEqualTo(
            """{"t":"load","track":{"t":"Avril 14th","a":"Aphex Twin","d":125000,"isrc":"GBBPW0100025","by":"M2"},"positionMs":0,"queue":[],"why":"skip"}""",
        )
        val prepare = RoomProtocol.decode(
            """{"t":"prepare","trackKey":2,"track":{"t":"Xtal","a":"Aphex Twin","by":"M2"},"positionMs":0,"deadlineMs":9,"by":"M1","why":"back"}""",
        ) as ServerMessage.Prepare
        assertThat(prepare.track.addedBy).isEqualTo("M2")
        assertThat(prepare.by).isEqualTo("M1")
        assertThat(prepare.why).isEqualTo("back")
        val timeline = RoomProtocol.decode("""{"t":"timeline","rev":3,"trackKey":2,"positionMs":0,"atRoomMs":0,"playing":false,"by":"M1"}""")
        assertThat((timeline as ServerMessage.TimelineUpdate).by).isEqualTo("M1")
    }

    @Test fun `a song's cover travels as art, and is left out when unknown`() {
        val cover = "https://i.scdn.co/image/ab67616d0000b273aa"
        assertThat(RoomProtocol.encode(ClientMessage.Queue(listOf(track.copy(artUrl = cover))))).contains(""""art":"$cover"""")
        assertThat(RoomProtocol.encode(ClientMessage.Queue(listOf(track)))).doesNotContain("art")
        val update = RoomProtocol.decode("""{"t":"queue","queue":[{"t":"Xtal","a":"Aphex Twin","art":"$cover"}]}""")
        assertThat((update as ServerMessage.QueueUpdate).queue.single().artUrl).isEqualTo(cover)
    }

    @Test fun `a welcome exactly as the Worker sends it decodes`() {
        val text = """{"t":"welcome","memberId":"m1","token":"tok","state":{"rev":3,"host":"m1","track":null,"trackKey":0,
            "timeline":{"positionMs":0,"atRoomMs":1000,"playing":false},"queue":[],
            "members":[{"id":"m1","name":null,"joinedAt":1000,"status":"buffering"}],"suggestions":[],"phase":{"kind":"playing"}}}"""
        val welcome = RoomProtocol.decode(text) as ServerMessage.Welcome
        assertThat(welcome.memberId).isEqualTo("m1")
        assertThat(welcome.state.host).isEqualTo("m1")
        assertThat(welcome.state.members.single().name).isNull()
        assertThat(welcome.state.phase.kind).isEqualTo(RoomPhase.PLAYING)
    }

    @Test fun `unknown types and junk decode to null, extra fields are ignored`() {
        assertThat(RoomProtocol.decode("""{"t":"pong","c":1,"r":2,"extra":true}""")).isEqualTo(ServerMessage.Pong(1, 2))
        assertThat(RoomProtocol.decode("""{"t":"prepare","trackKey":2,"track":{"t":"Xtal","a":"Aphex Twin"},"positionMs":0,"deadlineMs":9}"""))
            .isEqualTo(ServerMessage.Prepare(2, SharedTrack("Xtal", "Aphex Twin"), 0, 9))
        assertThat(RoomProtocol.decode("""{"t":"nope"}""")).isNull()
        assertThat(RoomProtocol.decode("not json")).isNull()
    }

    @Test fun `guest adds go on the wire as the room reads them`() {
        assertThat(RoomProtocol.encode(ClientMessage.Hello(name = "Rawn", v = RoomProtocol.VERSION, guestsAdd = true)))
            .isEqualTo("""{"t":"hello","name":"Rawn","v":2,"guestsAdd":true}""")
        assertThat(RoomProtocol.encode(ClientMessage.Add(listOf(SharedTrack("Xtal", "Aphex Twin")), next = false)))
            .isEqualTo("""{"t":"add","tracks":[{"t":"Xtal","a":"Aphex Twin"}],"next":false}""")
        assertThat(RoomProtocol.encode(ClientMessage.Unqueue(SharedTrack("Xtal", "Aphex Twin", addedBy = "M2"), at = 1)))
            .isEqualTo("""{"t":"unqueue","track":{"t":"Xtal","a":"Aphex Twin","by":"M2"},"at":1}""")
        assertThat(RoomProtocol.encode(ClientMessage.Settings(false))).isEqualTo("""{"t":"settings","guestsAdd":false}""")
        assertThat(RoomProtocol.encode(ClientMessage.Queue(emptyList(), qv = 5))).isEqualTo("""{"t":"queue","queue":[],"qv":5}""")
        // An older Worker never sent a version: the host's whole queues say nothing about one.
        assertThat(RoomProtocol.encode(ClientMessage.Queue(emptyList()))).isEqualTo("""{"t":"queue","queue":[]}""")
    }

    @Test fun `a room that knows guest adds says so, and an older one reads as suggestions only`() {
        val text = """{"t":"state","state":{"rev":3,"host":"m1","track":null,"trackKey":0,
            "timeline":{"positionMs":0,"atRoomMs":1000,"playing":false},"queue":[],
            "members":[{"id":"m1","name":"Rawn","joinedAt":1000,"status":"ok","v":2},{"id":"m2","name":null,"joinedAt":1001,"status":"ok"}],
            "suggestions":[],"phase":{"kind":"playing"},"v":2,"guestsAdd":true,"qv":12}}"""
        val state = (RoomProtocol.decode(text) as ServerMessage.StateSync).state
        assertThat(state.v).isEqualTo(2)
        assertThat(state.guestsAdd).isTrue()
        assertThat(state.qv).isEqualTo(12)
        assertThat(state.members.map { it.v }).containsExactly(2, null).inOrder()
        val old = RoomProtocol.decode("""{"t":"state","state":{"rev":1,"host":"m1","trackKey":0,"queue":[],"members":[],"suggestions":[]}}""")
        val oldState = (old as ServerMessage.StateSync).state
        assertThat(oldState.v).isEqualTo(1)
        assertThat(oldState.guestsAdd).isFalse()
        assertThat(oldState.qv).isNull()
    }

    @Test fun `the room's added, settings and versioned queue decode`() {
        assertThat(RoomProtocol.decode("""{"t":"added","by":"m2","next":true,"count":3,"track":{"t":"Xtal","a":"Aphex Twin","by":"m2"}}"""))
            .isEqualTo(ServerMessage.Added("m2", next = true, count = 3, track = SharedTrack("Xtal", "Aphex Twin", addedBy = "m2")))
        assertThat(RoomProtocol.decode("""{"t":"settings","guestsAdd":false}""")).isEqualTo(ServerMessage.SettingsUpdate(false))
        assertThat(RoomProtocol.decode("""{"t":"queue","queue":[],"qv":9}""")).isEqualTo(ServerMessage.QueueUpdate(emptyList(), qv = 9))
        assertThat(RoomProtocol.decode("""{"t":"queue","queue":[]}""")).isEqualTo(ServerMessage.QueueUpdate(emptyList(), qv = null))
    }
}
