package com.stash.core.ui.share

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Share image file names and the one-image-at-a-time share folder. */
class ShareImageTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `names are lower case words joined by hyphens`() {
        assertThat(ShareImage.fileName("Stash · Your October 2026 — Top songs!")).isEqualTo("stash-your-october-2026-top-songs.png")
        assertThat(ShareImage.fileName("  ")).isEqualTo("stash.png")
        assertThat(ShareImage.fileName("Ünïcode ✨")).isEqualTo("n-code.png")
    }

    @Test fun `long names are cut without a trailing hyphen`() {
        val name = ShareImage.fileName("a".repeat(47) + " bbbb")
        assertThat(name.length).isAtMost(48 + 4)
        assertThat(name).doesNotContain("-.png")
    }

    @Test fun `clearing the folder keeps only the file asked for`() {
        val dir = tmp.newFolder("share")
        val old1 = File(dir, "a.png").apply { writeText("x") }
        val old2 = File(dir, "b.png").apply { writeText("x") }
        val keep = File(dir, "c.png").apply { writeText("x") }
        assertThat(ShareImage.clearOld(dir, keep)).isEqualTo(2)
        assertThat(old1.exists()).isFalse()
        assertThat(old2.exists()).isFalse()
        assertThat(keep.exists()).isTrue()
        assertThat(ShareImage.clearOld(File(dir, "missing"))).isEqualTo(0)
    }
}
