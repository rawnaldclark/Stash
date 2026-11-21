package com.stash.core.model.community

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.share.SharedTrack
import kotlinx.serialization.json.Json
import org.junit.Test

/** The shapes in infra/share-worker/README.md "Contract for the app", as the Worker sends them. */
class StationsTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test fun `a station without a key has no vote, follow or heard of this phone's`() {
        val s = json.decodeFromString(Station.serializer(),
            """{"id":"Ab3dEf9h","name":"Rawn's Basement","hostName":"Rawn","vibe":"Late-night jungle","up":12,"down":2,"followers":31}""")
        assertThat(s).isEqualTo(Station("Ab3dEf9h", "Rawn's Basement", "Rawn", "Late-night jungle", up = 12, down = 2, followers = 31))
        assertThat(s.score).isEqualTo(10)
        assertThat(s.myVote).isEqualTo(0)
        assertThat(s.following).isFalse()
    }

    @Test fun `with a key the station says what this phone has done`() {
        val s = json.decodeFromString(Station.serializer(),
            """{"id":"Ab3dEf9h","name":"B","hostName":null,"vibe":null,"up":1,"down":0,"followers":0,"myVote":1,"mine":false,"heard":true,"following":true}""")
        assertThat(s.myVote).isEqualTo(1)
        assertThat(s.heard).isTrue()
        assertThat(s.following).isTrue()
        assertThat(s.hostName).isNull()
    }

    @Test fun `your own station has its schedule, deletion time and moderation marks`() {
        val s = json.decodeFromString(Station.serializer(),
            """{"id":"Ab3dEf9h","name":"B","hostName":"Rawn","vibe":null,"up":0,"down":4,"followers":2,"myVote":0,"mine":true,"heard":true,
            "following":false,"autoLive":"schedule","schedule":{"tz":"America/New_York","windows":[{"day":1,"start":"09:00","end":"17:00"}]},
            "hidden":true,"removed":false,"lastLiveAt":null,"deleteAt":1700000000000}""")
        assertThat(s.mine).isTrue()
        assertThat(s.autoLive).isEqualTo("schedule")
        assertThat(s.schedule).isEqualTo(StationSchedule("America/New_York", listOf(ScheduleWindow(1, "09:00", "17:00"))))
        assertThat(s.hidden).isTrue()
        assertThat(s.lastLiveAt).isNull()
        assertThat(s.deleteAt).isEqualTo(1_700_000_000_000)
    }

    @Test fun `a followed station carries its session and its next time on the air`() {
        val s = json.decodeFromString(Station.serializer(),
            """{"id":"Ab3dEf9h","name":"B","up":0,"down":0,"followers":1,"following":true,
            "live":{"room":"K7QA2PXM","track":{"t":"Xtal","a":"Aphex Twin"},"listeners":3,"paused":false,"full":false,"crowd":[{"i":"R","id":"m1"},{"i":null,"id":"m2"}]},
            "next":{"start":1,"end":2}}""")
        assertThat(s.live).isEqualTo(StationLive("K7QA2PXM", SharedTrack("Xtal", "Aphex Twin"), 3, crowd = listOf(CrowdFace("R", "m1"), CrowdFace(null, "m2"))))
        assertThat(s.next).isEqualTo(OnAirWindow(1, 2))
    }

    @Test fun `Live now's sessions and a station's page decode`() {
        val live = json.decodeFromString(LiveSession.serializer(),
            """{"station":{"id":"Ab3dEf9h","name":"B","hostName":"Rawn","vibe":"v","up":3,"down":0,"followers":0},"room":"K7QA2PXM",
            "track":{"t":"Xtal","a":"Aphex Twin","d":288000},"listeners":12,"paused":false,"full":false,"crowd":[{"i":"R","id":"m1"}],"startedAt":5}""")
        assertThat(live.station.name).isEqualTo("B")
        assertThat(live.listeners).isEqualTo(12)
        assertThat(live.track?.durationMs).isEqualTo(288_000)
        assertThat(live.crowd.single().i).isEqualTo("R")
        val page = json.decodeFromString(StationPage.serializer(),
            """{"station":{"id":"Ab3dEf9h","name":"B","up":0,"down":0,"followers":0},"live":{"room":"K7QA2PXM","track":null,"listeners":0,"paused":true,"full":false}}""")
        assertThat(page.live?.room).isEqualTo("K7QA2PXM")
        assertThat(page.live?.paused).isTrue()
        assertThat(json.decodeFromString(StationPage.serializer(), """{"station":{"id":"Ab3dEf9h","name":"B"}}""").live).isNull()
        assertThat(json.decodeFromString(FollowState.serializer(), """{"following":true,"followers":32}""")).isEqualTo(FollowState(true, 32))
    }
}
