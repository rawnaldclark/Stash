package com.stash.core.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AlbumCardSubtitleTest {
    @Test fun `year and artist`() {
        assertThat(albumCardSubtitle("2010", "Massive Attack")).isEqualTo("2010 • Massive Attack")
    }

    @Test fun `the artist page's own albums show the year alone, with no dangling bullet`() {
        assertThat(albumCardSubtitle("2010", "")).isEqualTo("2010")
        assertThat(albumCardSubtitle("2010", "  ")).isEqualTo("2010")
    }

    @Test fun `no year shows the artist alone`() {
        assertThat(albumCardSubtitle(null, "Massive Attack")).isEqualTo("Massive Attack")
        assertThat(albumCardSubtitle("", "Massive Attack")).isEqualTo("Massive Attack")
    }

    @Test fun `neither is an empty line`() {
        assertThat(albumCardSubtitle(null, "")).isEmpty()
    }
}
