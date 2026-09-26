package com.stash.core.media.listen

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ClockSyncTest {
    @Test fun `offset is the room time minus the moment halfway through the round trip`() {
        val sync = ClockSync()
        sync.onPong(clientSentMs = 1_000, roomMs = 5_060, nowMs = 1_100) // rtt 100, halfway at 1_050
        assertThat(sync.offsetMs).isEqualTo(4_010)
        assertThat(sync.roomNow(1_200)).isEqualTo(5_210)
    }

    @Test fun `the fastest round trip wins`() {
        val sync = ClockSync()
        sync.onPong(1_000, 5_060, 1_100) // rtt 100 → 4_010
        sync.onPong(2_000, 6_030, 2_020) // rtt 20 → 4_020
        sync.onPong(3_000, 7_300, 3_400) // rtt 400 → 4_100
        assertThat(sync.offsetMs).isEqualTo(4_020)
    }

    @Test fun `samples older than five minutes are forgotten`() {
        val sync = ClockSync()
        sync.onPong(0, 4_010, 20) // rtt 20 → 4_000
        sync.onPong(399_900, 404_150, 400_000) // rtt 100 → 4_200, and the first sample is now too old
        assertThat(sync.offsetMs).isEqualTo(4_200)
    }

    @Test fun `no samples means no room time, and a negative round trip is ignored`() {
        val sync = ClockSync()
        assertThat(sync.roomNow(5)).isNull()
        sync.onPong(clientSentMs = 500, roomMs = 1, nowMs = 400)
        assertThat(sync.offsetMs).isNull()
    }
}
