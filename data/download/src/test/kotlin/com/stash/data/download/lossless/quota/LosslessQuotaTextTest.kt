package com.stash.data.download.lossless.quota

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The counter counts Tidal and Qobuz answers, AAC included, so its words name them, not "lossless". */
class LosslessQuotaTextTest {

    @Test fun `running low shows the counter and the supporter option`() {
        assertThat(LosslessQuotaText.notice(LosslessQuotaNotice.RunningLow(50, 500, 0L), "8:00 PM"))
            .isEqualTo("Tidal & Qobuz today: 450 / 500. Unlimited for supporters: any amount on Ko-fi")
    }

    @Test fun `the limit shows the counter, the reset and the supporter option`() {
        assertThat(LosslessQuotaText.notice(LosslessQuotaNotice.Spent(500, 0L), "8:00 PM")).isEqualTo(
            "Tidal & Qobuz today: 500 / 500 — lower quality until 8:00 PM. " +
                "Unlimited for supporters: any amount on Ko-fi",
        )
    }

    @Test fun `the Settings line`() {
        assertThat(LosslessQuotaText.todayLine(LosslessAllowance(120, 500, 0L), "8:00 PM"))
            .isEqualTo("Tidal & Qobuz today: 120 / 500 · resets 8:00 PM")
        assertThat(LosslessQuotaText.todayLine(LosslessAllowance(9_000, null, 0L), "8:00 PM"))
            .isEqualTo("Tidal & Qobuz today: Unlimited (supporter)")
    }
}
