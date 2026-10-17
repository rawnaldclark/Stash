package com.stash.data.download.lossless.quota

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LosslessQuotaTextTest {

    @Test fun `running low names what is left`() {
        assertThat(LosslessQuotaText.notice(LosslessQuotaNotice.RunningLow(50, 0L), "8:00 PM"))
            .isEqualTo("50 lossless songs left today")
        assertThat(LosslessQuotaText.notice(LosslessQuotaNotice.RunningLow(1, 0L), "8:00 PM"))
            .isEqualTo("1 lossless song left today")
    }

    @Test fun `the limit names the reset and the supporter option`() {
        assertThat(LosslessQuotaText.notice(LosslessQuotaNotice.Spent(0L), "8:00 PM")).isEqualTo(
            "Daily lossless limit reached — playing in high quality until 8:00 PM. " +
                "Unlimited lossless for supporters: any amount on Ko-fi",
        )
    }
}
