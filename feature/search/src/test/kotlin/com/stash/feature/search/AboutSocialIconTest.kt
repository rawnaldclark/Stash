package com.stash.feature.search

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AboutSocialIconTest {
    @Test fun `known platform differs from website`() {
        assertThat(socialIconFor("instagram")).isNotEqualTo(socialIconFor("website"))
    }
    @Test fun `unknown kind falls back to the website globe icon`() {
        assertThat(socialIconFor("totally-unknown")).isEqualTo(socialIconFor("website"))
    }
}
