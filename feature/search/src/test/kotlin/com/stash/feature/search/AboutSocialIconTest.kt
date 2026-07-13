package com.stash.feature.search

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AboutSocialIconTest {
    @Test fun `known platform has a brand icon, website does not`() {
        assertThat(socialIconFor("instagram")).isNotNull()
        assertThat(socialIconFor("website")).isNull()
    }

    @Test fun `unknown kind falls back like website (globe)`() {
        assertThat(socialIconFor("totally-unknown")).isEqualTo(socialIconFor("website"))
    }

    @Test fun `each platform maps to a distinct brand icon`() {
        val ids = listOf("instagram", "x", "tiktok", "youtube", "facebook", "soundcloud", "bandcamp")
            .map { socialIconFor(it) }
        assertThat(ids).doesNotContain(null) // every platform gets a real logo
        assertThat(ids).containsNoDuplicates() // and they're all different (the bug: all identical)
    }
}
