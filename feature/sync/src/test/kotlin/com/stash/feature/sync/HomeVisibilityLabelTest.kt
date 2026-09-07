package com.stash.feature.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The manage-playlists row must say what Home will do. "Shown on Home" was a
 * promise the rail only kept for the 12 freshest mixes (Cup Noodle Radio,
 * 2026-09-07); the three real states get three honest labels.
 */
class HomeVisibilityLabelTest {
    @Test fun `a hidden mix says so`() =
        assertEquals("Hidden from Home", homeVisibilityLabel(hidden = true, pinned = false))

    @Test fun `a pinned mix is on Home, full stop`() =
        assertEquals("Pinned on Home", homeVisibilityLabel(hidden = false, pinned = true))

    @Test fun `an untouched mix surfaces when it updates`() =
        assertEquals("On Home when it updates", homeVisibilityLabel(hidden = false, pinned = false))

    @Test fun `hidden wins over a stale pin stamp`() =
        assertEquals("Hidden from Home", homeVisibilityLabel(hidden = true, pinned = true))
}
