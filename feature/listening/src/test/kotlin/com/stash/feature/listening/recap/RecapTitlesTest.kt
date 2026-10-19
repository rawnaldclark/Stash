package com.stash.feature.listening.recap

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Recap titles drop remaster tags only. */
class RecapTitlesTest {

    @Test fun `remaster tags go`() {
        assertThat(RecapTitles.display("Dreams (2004 Remaster)")).isEqualTo("Dreams")
        assertThat(RecapTitles.display("Nights - Remastered 2016")).isEqualTo("Nights")
        assertThat(RecapTitles.display("Song – 2011 Remaster")).isEqualTo("Song")
        assertThat(RecapTitles.display("Song [Remastered]")).isEqualTo("Song")
        assertThat(RecapTitles.display("Song (Remastered Version)")).isEqualTo("Song")
        assertThat(RecapTitles.display("Song (Digitally Remastered 2009)")).isEqualTo("Song")
        assertThat(RecapTitles.display("Song - Remastered 2011 (2011 Remaster)")).isEqualTo("Song")
    }

    @Test fun `live, remix, version and other tags stay`() {
        assertThat(RecapTitles.display("Song (Live)")).isEqualTo("Song (Live)")
        assertThat(RecapTitles.display("Song - Fcukers Remix")).isEqualTo("Song - Fcukers Remix")
        assertThat(RecapTitles.display("Song (Acoustic Version)")).isEqualTo("Song (Acoustic Version)")
        assertThat(RecapTitles.display("Song (2011 Remaster) [Live]")).isEqualTo("Song (2011 Remaster) [Live]")
        assertThat(RecapTitles.display("Remastered")).isEqualTo("Remastered")
        assertThat(RecapTitles.display("Remaster (2004 Remaster)")).isEqualTo("Remaster")
    }
}
