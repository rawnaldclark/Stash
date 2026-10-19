package com.stash.core.ui.share

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneOffset

/** Share image file names (readable, timestamped) and the share folder's cleanup (by age). */
class ShareImageTest {

    @get:Rule val tmp = TemporaryFolder()

    /** 2026-10-19 00:12:05 UTC. */
    private val now = 1_792_368_725_000L

    @Test fun `names are lower case words joined by hyphens, with the time`() {
        assertThat(ShareImage.fileName("Stash · Your October 2026 — Top songs!", now, ZoneOffset.UTC))
            .isEqualTo("stash-your-october-2026-top-songs-20261019-001205.png")
        assertThat(ShareImage.fileName("  ", now, ZoneOffset.UTC)).isEqualTo("stash-20261019-001205.png")
        assertThat(ShareImage.fileName("Ünïcode ✨", now, ZoneOffset.UTC)).isEqualTo("n-code-20261019-001205.png")
    }

    @Test fun `two shares a second apart are two files`() {
        assertThat(ShareImage.fileName("a", now, ZoneOffset.UTC)).isNotEqualTo(ShareImage.fileName("a", now + 1_000, ZoneOffset.UTC))
    }

    @Test fun `long names are cut without a trailing hyphen`() {
        val name = ShareImage.fileName("a".repeat(47) + " bbbb", now, ZoneOffset.UTC)
        assertThat(name).doesNotContain("--")
        assertThat(name.removeSuffix("-20261019-001205.png").length).isAtMost(48)
    }

    @Test fun `only files older than ten minutes are deleted`() {
        val dir = tmp.newFolder("share")
        val old = File(dir, "old.png").apply { writeText("x"); setLastModified(now - ShareImage.MAX_AGE_MS - 1_000) }
        val recent = File(dir, "recent.png").apply { writeText("x"); setLastModified(now - 60_000) }
        assertThat(ShareImage.clearOld(dir, now)).isEqualTo(1)
        assertThat(old.exists()).isFalse()
        assertThat(recent.exists()).isTrue()
        assertThat(ShareImage.clearOld(File(dir, "missing"), now)).isEqualTo(0)
    }
}
