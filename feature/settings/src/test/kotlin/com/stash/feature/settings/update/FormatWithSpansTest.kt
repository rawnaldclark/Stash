package com.stash.feature.settings.update

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** "82 of 174 MB": the downloaded number in strong ink wherever a translation puts it (review on #814). */
class FormatWithSpansTest {

    private val strong = SpanStyle(fontWeight = FontWeight.SemiBold)

    @Test fun `the first argument is styled, not the first matching digits`() {
        // 1 MB downloaded of 174 MB: indexOf("1") used to bold the 1 of 174 when the template put the size first.
        val text = formatWithSpans("%2\$s, %1\$d so far", listOf("1", "174 MB"), mapOf(0 to strong))
        assertThat(text.text).isEqualTo("174 MB, 1 so far")
        val span = text.spanStyles.single()
        assertThat(text.text.substring(span.start, span.end)).isEqualTo("1")
        assertThat(span.start).isEqualTo(8)
        assertThat(span.item).isEqualTo(strong)
    }

    @Test fun `plain text, a percent sign and an unknown argument stay as written`() {
        val text = formatWithSpans("%1\$d of %2\$s, 50%% done, %3\$s", listOf("82", "174 MB"), mapOf(0 to strong))
        assertThat(text.text).isEqualTo("82 of 174 MB, 50% done, %3\$s")
        assertThat(text.spanStyles.map { text.text.substring(it.start, it.end) }).containsExactly("82")
    }
}
