package com.stash.feature.settings

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.lossless.quota.LosslessAllowance
import org.junit.Test

class LosslessTodayLineTest {

    private val now = 1_792_238_400_000L
    private val reset = 1_792_281_600L
    private val time: (Long) -> String = { ms -> if (ms == reset * 1000) "8:00 PM" else "next day" }

    @Test fun `shows used of limit and the local reset time`() {
        assertThat(losslessTodayLine(LosslessAllowance(412, 500, reset), true, now, time))
            .isEqualTo("Tidal & Qobuz today: 412 / 500 · resets 8:00 PM")
    }

    @Test fun `a supporter reads unlimited`() {
        assertThat(losslessTodayLine(LosslessAllowance(9_000, null, reset), true, now, time))
            .isEqualTo("Tidal & Qobuz today: Unlimited (supporter)")
    }

    @Test fun `hidden when nothing is known or no relay is configured`() {
        assertThat(losslessTodayLine(null, true, now, time)).isNull()
        assertThat(losslessTodayLine(LosslessAllowance(412, 500, reset), false, now, time)).isNull()
    }

    @Test fun `a day that has reset reads nothing used, resetting the next midnight`() {
        assertThat(losslessTodayLine(LosslessAllowance(500, 500, reset), true, reset * 1000, time))
            .isEqualTo("Tidal & Qobuz today: 0 / 500 · resets next day")
    }
}
