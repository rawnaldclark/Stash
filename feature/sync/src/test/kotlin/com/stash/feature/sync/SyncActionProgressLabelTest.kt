package com.stash.feature.sync

import com.stash.core.data.sync.SyncPhase
import com.stash.feature.sync.components.phaseLabel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #421 — the Downloading label must SAY when tracks are deferred waiting for
 * a lossless source; "Downloading 0/5" with no movement read as a hang.
 */
class SyncActionProgressLabelTest {

    @Test
    fun `downloading label names the deferred count when tracks are waiting`() {
        assertEquals(
            "Downloading 2/5 · 3 waiting...",
            phaseLabel(SyncPhase.Downloading(downloaded = 2, total = 5, deferred = 3)),
        )
    }

    @Test
    fun `downloading label is unchanged when nothing is deferred`() {
        assertEquals(
            "Downloading 2/5...",
            phaseLabel(SyncPhase.Downloading(downloaded = 2, total = 5)),
        )
    }

    /** "Sync now" tapped offline used to sit on "Authenticating..." until a network came back. */
    @Test
    fun `a sync queued offline says it is waiting for a network`() {
        assertEquals("Waiting for network...", phaseLabel(SyncPhase.WaitingForNetwork))
    }
}
