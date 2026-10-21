package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StreamServiceTest {

    @Test fun `every Qobuz resolver is named Qobuz`() {
        listOf("qbdlx", "kennyy", "squid").forEach {
            assertThat(StreamService.displayName(it)).isEqualTo("Qobuz")
        }
    }

    @Test fun `tidal, jiosaavn and youtube are named for their service`() {
        assertThat(StreamService.displayName("tidal")).isEqualTo("Tidal")
        assertThat(StreamService.displayName("jiosaavn")).isEqualTo("JioSaavn")
        assertThat(StreamService.displayName("youtube")).isEqualTo("YouTube")
    }

    @Test fun `no origin or an unknown one names no service`() {
        assertThat(StreamService.displayName(null)).isNull()
        assertThat(StreamService.displayName("antra")).isNull()
        assertThat(StreamService.displayName("")).isNull()
    }
}
