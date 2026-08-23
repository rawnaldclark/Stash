package com.stash.core.data.sync.workers

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.sync.workers.UpdateCheckWorker.Companion.OUTCOME_UPDATE_AVAILABLE
import com.stash.core.data.sync.workers.UpdateCheckWorker.Companion.OUTCOME_UP_TO_DATE
import com.stash.core.data.sync.workers.UpdateCheckWorker.Companion.isNewerVersion
import com.stash.core.data.sync.workers.UpdateCheckWorker.Companion.outcomeFor
import org.junit.Test

/**
 * Contract tests for the update check's two pure decisions.
 *
 * Issue #377: three users on the latest release tapped "Check for updates",
 * saw "Checking for updates…" and then nothing. Every no-update exit returned
 * `Result.success()` with no output, so the UI had nothing to report — the
 * check worked and stayed invisible. Each terminal path now names its outcome.
 */
class UpdateCheckWorkerTest {

    // ── Outcome reporting: no terminal state may be silent ───────────────────

    @Test fun `up to date is reported, not swallowed`() {
        assertThat(outcomeFor(isNewer = false)).isEqualTo(OUTCOME_UP_TO_DATE)
    }

    @Test fun `a newer release is reported`() {
        assertThat(outcomeFor(isNewer = true)).isEqualTo(OUTCOME_UPDATE_AVAILABLE)
    }

    /** The two outcomes must be distinguishable — that IS the fix. */
    @Test fun `outcomes are distinct`() {
        assertThat(OUTCOME_UP_TO_DATE).isNotEqualTo(OUTCOME_UPDATE_AVAILABLE)
    }

    // ── Version comparison ───────────────────────────────────────────────────

    @Test fun `newer patch wins`() {
        assertThat(isNewerVersion("v0.9.99", "0.9.84")).isTrue()
    }

    @Test fun `same version is not newer`() {
        assertThat(isNewerVersion("v0.9.99", "0.9.99")).isFalse()
    }

    @Test fun `older remote is not newer`() {
        assertThat(isNewerVersion("v0.9.84", "0.9.99")).isFalse()
    }

    /** Segments compare as integers, so 10 beats 2 despite sorting lower as text. */
    @Test fun `segments compare numerically`() {
        assertThat(isNewerVersion("v0.10.0", "0.2.0")).isTrue()
        assertThat(isNewerVersion("v0.9.9", "0.9.84")).isFalse()
    }

    @Test fun `prerelease suffix is stripped`() {
        assertThat(isNewerVersion("v1.0.0-beta.2", "1.0.0")).isFalse()
        assertThat(isNewerVersion("v1.0.1-rc.1", "1.0.0")).isTrue()
    }

    @Test fun `missing components count as zero`() {
        assertThat(isNewerVersion("v1.1", "1.0.9")).isTrue()
        assertThat(isNewerVersion("v1.0", "1.0.0")).isFalse()
    }

    /**
     * An UPPERCASE "V" used to survive the prefix strip, so "V1.0.0" parsed as
     * [0, 0, 0] and any remote tag looked newer than it.
     */
    @Test fun `uppercase V prefix is stripped too`() {
        assertThat(isNewerVersion("v0.9.99", "V1.0.0")).isFalse()
        assertThat(isNewerVersion("V0.9.99", "0.9.84")).isTrue()
    }
}
