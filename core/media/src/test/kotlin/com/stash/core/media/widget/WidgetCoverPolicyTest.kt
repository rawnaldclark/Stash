package com.stash.core.media.widget

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** When the widget fetches a cover ([WidgetCoverPolicy]) and how big the wide card's is ([WidgetWideLayout]). */
class WidgetCoverPolicyTest {

    private val url = "https://img/a.jpg"

    @Test
    fun `a new song's cover is wanted`() {
        assertThat(WidgetCoverPolicy.wanted(url, coverKey = "https://img/old.jpg", haveCover = true, failedKey = null)).isEqualTo(url)
        assertThat(WidgetCoverPolicy.wanted(url, coverKey = null, haveCover = false, failedKey = null)).isEqualTo(url)
    }

    @Test
    fun `the cover on hand, no cover, or a lasting failure is not fetched`() {
        assertThat(WidgetCoverPolicy.wanted(url, coverKey = url, haveCover = true, failedKey = null)).isNull()
        assertThat(WidgetCoverPolicy.wanted(null, coverKey = null, haveCover = false, failedKey = null)).isNull()
        assertThat(WidgetCoverPolicy.wanted(" ", coverKey = null, haveCover = false, failedKey = null)).isNull()
        assertThat(WidgetCoverPolicy.wanted(url, coverKey = null, haveCover = false, failedKey = url)).isNull()
    }

    @Test
    fun `a stored key whose picture is gone is fetched again`() {
        assertThat(WidgetCoverPolicy.wanted(url, coverKey = url, haveCover = false, failedKey = null)).isEqualTo(url)
    }

    @Test
    fun `a timeout or a dropped connection is not remembered`() {
        assertThat(WidgetCoverPolicy.isLastingFailure(error = null, timedOut = true)).isFalse()
        assertThat(WidgetCoverPolicy.isLastingFailure(UnknownHostException("offline"), timedOut = false)).isFalse()
        assertThat(WidgetCoverPolicy.isLastingFailure(SocketTimeoutException(), timedOut = false)).isFalse()
    }

    @Test
    fun `an answer that isn't a picture is remembered`() {
        assertThat(WidgetCoverPolicy.isLastingFailure(IllegalStateException("HTTP 404"), timedOut = false)).isTrue()
        assertThat(WidgetCoverPolicy.isLastingFailure(error = null, timedOut = false)).isTrue()
    }

    @Test
    fun `the wide cover is as tall as the card allows, as before`() {
        // 4×1 on a Pixel: about 300 × 90 dp.
        assertThat(WidgetWideLayout.coverDp(widthDp = 300f, heightDp = 90f, hasButtons = true)).isEqualTo(74f)
        assertThat(WidgetWideLayout.coverDp(widthDp = 400f, heightDp = 200f, hasButtons = true)).isEqualTo(88f)
        assertThat(WidgetWideLayout.coverDp(widthDp = 300f, heightDp = 40f, hasButtons = true)).isEqualTo(36f)
    }

    @Test
    fun `a narrow card caps the cover by what the title and buttons leave`() {
        // 250 dp: 250 - 16 - 12 - 92 - 48 = 82, under the height's 88.
        assertThat(WidgetWideLayout.coverDp(widthDp = 250f, heightDp = 120f, hasButtons = true)).isEqualTo(82f)
    }

    @Test
    fun `at 2x1 the cover gives way so Next stays on the card`() {
        assertThat(WidgetWideLayout.coverDp(widthDp = 195f, heightDp = 90f, hasButtons = true)).isNull()
        assertThat(WidgetWideLayout.coverDp(widthDp = 180f, heightDp = 90f, hasButtons = true)).isNull()
        // Nothing playing (no buttons): the cover fits again.
        assertThat(WidgetWideLayout.coverDp(widthDp = 195f, heightDp = 90f, hasButtons = false)).isEqualTo(74f)
    }

    @Test
    fun `whatever the size, the cover plus the rest never overflows the card`() {
        for (w in 150..500 step 5) for (h in 40..200 step 10) for (buttons in listOf(true, false)) {
            val cover = WidgetWideLayout.coverDp(w.toFloat(), h.toFloat(), buttons) ?: continue
            val used = WidgetWideLayout.INSET_DP * 2 + cover + WidgetWideLayout.COVER_GAP_DP +
                (if (buttons) WidgetWideLayout.BUTTONS_DP else 0f) + WidgetWideLayout.MIN_TEXT_DP
            assertThat(used).isAtMost(w.toFloat())
        }
    }
}
