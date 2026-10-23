package com.stash.data.download.preview

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test

class FastClientBenchTest {
    private var now = 1_000_000L

    @Before fun setUp() {
        FastClientBench.reset()
        FastClientBench.clock = { now }
    }

    @After fun tearDown() = FastClientBench.reset()

    @Test fun `three strikes in a row bench a client, then it gets another try`() {
        repeat(FastClientBench.FAILS_TO_BENCH - 1) { FastClientBench.failed("visionos") }
        assertThat(FastClientBench.usable("visionos")).isTrue()
        FastClientBench.failed("visionos")
        assertThat(FastClientBench.usable("visionos")).isFalse()
        assertThat(PreviewUrlExtractor.fastClientsNow()).doesNotContain("visionos")

        now += FastClientBench.BENCH_MS
        assertThat(FastClientBench.usable("visionos")).isTrue()
        assertThat(PreviewUrlExtractor.fastClientsNow()).contains("visionos")
    }

    @Test fun `a success wipes the strikes`() {
        repeat(FastClientBench.FAILS_TO_BENCH - 1) { FastClientBench.failed("visionos") }
        FastClientBench.succeeded("visionos")
        FastClientBench.failed("visionos")
        assertThat(FastClientBench.usable("visionos")).isTrue()
    }

    @Test fun `visionos leads the fast clients and never gets a cookie file`() {
        // yt-dlp skips a client that doesn't take cookies when handed --cookies, rather than
        // dropping them: visionos with cookies would be a wasted run on every song.
        assertThat(PreviewUrlExtractor.FAST_PLAYER_CLIENTS.first()).isEqualTo("visionos")
        assertThat(PreviewUrlExtractor.supportsCookies("visionos")).isFalse()
    }
}
