package com.stash.core.data.sync.workers

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.sync.workers.ArtBackfillSchedule.Companion.MIN_INTERVAL_MS
import com.stash.core.data.sync.workers.ArtBackfillSchedule.State
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A backfill run goes ahead for new rows at once, otherwise at most every few hours. */
class ArtBackfillScheduleTest {

    @get:Rule val tmp = TemporaryFolder()

    private val now = 1_800_000_000_000L

    @Test fun `nothing to look at means no run`() {
        assertThat(ArtBackfillSchedule.shouldRun(State(0, 0), newestCandidateId = null, nowMs = now)).isFalse()
    }

    @Test fun `the first run ever goes ahead`() {
        assertThat(ArtBackfillSchedule.shouldRun(State(0, 0), 10, now)).isTrue()
    }

    @Test fun `a new row goes ahead at once, even right after a run`() {
        assertThat(ArtBackfillSchedule.shouldRun(State(now - 1_000, 10), 11, now)).isTrue()
    }

    @Test fun `no new row and a recent run means no run`() {
        assertThat(ArtBackfillSchedule.shouldRun(State(now - 1_000, 10), 10, now)).isFalse()
        assertThat(ArtBackfillSchedule.shouldRun(State(now - MIN_INTERVAL_MS + 1, 10), 9, now)).isFalse()
    }

    @Test fun `no new row but the interval passed goes ahead`() {
        assertThat(ArtBackfillSchedule.shouldRun(State(now - MIN_INTERVAL_MS, 10), 10, now)).isTrue()
    }

    @Test fun `a run stamped in the future counts as long ago`() {
        assertThat(ArtBackfillSchedule.shouldRun(State(now + 60_000, 10), 10, now)).isTrue()
    }

    @Test fun `the store remembers the run and never lowers the watermark`() = runTest {
        val schedule = ArtBackfillSchedule(PreferenceDataStoreFactory.create { File(tmp.root, "s.preferences_pb") })
        assertThat(schedule.read()).isEqualTo(State(0, 0))

        schedule.recordRun(now, 42)
        assertThat(schedule.read()).isEqualTo(State(now, 42))

        schedule.recordRun(now + 5, 7)
        assertThat(schedule.read()).isEqualTo(State(now + 5, 42))
    }
}
