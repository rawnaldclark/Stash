package com.stash.feature.sync

import android.content.res.Resources
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The manage-playlists row must say what Home will do. "Shown on Home" was a
 * promise the rail only kept for the 12 freshest mixes (Cup Noodle Radio,
 * 2026-09-07); the three real states get three honest labels.
 */
class HomeVisibilityLabelTest {
    private val res = mockk<Resources> {
        every { getString(R.string.sync_home_visibility_hidden) } returns "Hidden from Home"
        every { getString(R.string.sync_home_visibility_pinned) } returns "Pinned on Home"
        every { getString(R.string.sync_home_visibility_default) } returns "On Home when it updates"
    }

    @Test fun `a hidden mix says so`() {
        assertEquals("Hidden from Home", homeVisibilityLabel(hidden = true, pinned = false, res = res))
        assertEquals(R.string.sync_home_visibility_hidden, homeVisibilityLabelRes(hidden = true, pinned = false))
    }

    @Test fun `a pinned mix is on Home, full stop`() {
        assertEquals("Pinned on Home", homeVisibilityLabel(hidden = false, pinned = true, res = res))
        assertEquals(R.string.sync_home_visibility_pinned, homeVisibilityLabelRes(hidden = false, pinned = true))
    }

    @Test fun `an untouched mix surfaces when it updates`() {
        assertEquals("On Home when it updates", homeVisibilityLabel(hidden = false, pinned = false, res = res))
        assertEquals(R.string.sync_home_visibility_default, homeVisibilityLabelRes(hidden = false, pinned = false))
    }

    @Test fun `hidden wins over a stale pin stamp`() {
        assertEquals("Hidden from Home", homeVisibilityLabel(hidden = true, pinned = true, res = res))
        assertEquals(R.string.sync_home_visibility_hidden, homeVisibilityLabelRes(hidden = true, pinned = true))
    }
}
