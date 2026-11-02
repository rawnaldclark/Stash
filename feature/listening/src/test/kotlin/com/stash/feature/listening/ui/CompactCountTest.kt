package com.stash.feature.listening.ui

import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Test

/** The stats tiles' numbers: in full to 9,999, then three significant digits ([CompactCount]). */
class CompactCountTest {
    private fun us(n: Long) = CompactCount.format(n, Locale.US)

    @Test fun `counts under ten thousand are written out in full`() {
        assertThat(us(0)).isEqualTo("0")
        assertThat(us(7)).isEqualTo("7")
        assertThat(us(999)).isEqualTo("999")
        assertThat(us(1_234)).isEqualTo("1,234")
        assertThat(us(9_999)).isEqualTo("9,999")
    }

    @Test fun `from ten thousand they keep three significant digits`() {
        assertThat(us(10_000)).isEqualTo("10K")
        assertThat(us(12_345)).isEqualTo("12.3K")
        assertThat(us(99_949)).isEqualTo("99.9K")
        assertThat(us(123_456)).isEqualTo("123K")
        assertThat(us(330_827)).isEqualTo("331K")
        assertThat(us(1_234_567)).isEqualTo("1.23M")
        assertThat(us(45_600_000)).isEqualTo("45.6M")
        assertThat(us(2_500_000_000)).isEqualTo("2.5B")
    }

    @Test fun `trailing zeros go`() {
        assertThat(us(20_000)).isEqualTo("20K")
        assertThat(us(1_500_000)).isEqualTo("1.5M")
        assertThat(us(1_000_000)).isEqualTo("1M")
    }

    @Test fun `a count that rounds up to a thousand of a unit is the next unit`() {
        assertThat(us(999_499)).isEqualTo("999K")
        assertThat(us(999_950)).isEqualTo("1M")
        assertThat(us(999_999_999)).isEqualTo("1B")
    }

    @Test fun `halves round to even, as ICU does`() {
        assertThat(us(12_250)).isEqualTo("12.2K")
        assertThat(us(12_350)).isEqualTo("12.4K")
    }

    @Test fun `no count is ever longer than five characters`() {
        var n = 1L
        while (n < 1_000_000_000_000L) {
            for (m in listOf(n, n * 2 - 1, n * 5 - 1, n * 10 - 1)) {
                assertThat(us(m).length).isAtMost(5)
            }
            n *= 10
        }
    }

    @Test fun `the digits follow the locale`() {
        assertThat(CompactCount.format(9_999, Locale.GERMANY)).isEqualTo("9.999")
        assertThat(CompactCount.format(12_345, Locale.GERMANY)).isEqualTo("12,3K")
        assertThat(CompactCount.format(1_234_567, Locale.FRANCE)).isEqualTo("1,23M")
    }

    @Test fun `TalkBack gets the count in full`() {
        assertThat(CompactCount.full(12_345, Locale.US)).isEqualTo("12,345")
        assertThat(CompactCount.full(330_827, Locale.US)).isEqualTo("330,827")
        assertThat(CompactCount.full(5, Locale.US)).isEqualTo("5")
    }
}
