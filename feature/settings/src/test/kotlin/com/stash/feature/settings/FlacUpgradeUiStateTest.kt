package com.stash.feature.settings

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.workDataOf
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.lossless.FlacSweepResult
import com.stash.core.data.sync.workers.FlacUpgradeWorker
import java.util.UUID
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Library Health's "FLAC upgrades" card reads the batch worker's own state, so it tells
 * the truth after the screen was left, and a stopped run never reads as finished.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlacUpgradeUiStateTest {

    /** The module's own strings: what the app shows, not a copy of it. */
    private val res: Resources = ApplicationProvider.getApplicationContext<Context>().resources

    private fun state(infos: List<WorkInfo>, tap: FlacSweepResult?) = flacUpgradeUiState(infos, tap, res)

    private fun work(state: WorkInfo.State, output: Data = Data.EMPTY) =
        WorkInfo(UUID.randomUUID(), state, emptySet(), output)

    private fun finished(upgraded: Int, noMatch: Int, failed: Int) = work(
        WorkInfo.State.SUCCEEDED,
        workDataOf(
            FlacUpgradeWorker.KEY_UPGRADED to upgraded,
            FlacUpgradeWorker.KEY_NO_MATCH to noMatch,
            FlacUpgradeWorker.KEY_FAILED to failed,
        ),
    )

    @Test fun `nothing to say before any run`() {
        assertThat(state(emptyList(), null)).isEqualTo(FlacUpgradeUiState())
    }

    @Test fun `a running batch shows progress, a waiting one says it will carry on`() {
        val running = state(listOf(work(WorkInfo.State.RUNNING)), null)
        assertThat(running.active).isTrue()
        assertThat(running.message).isEqualTo("Checking for upgrades…")

        // A paced batch sits ENQUEUED between retries; it is still under way.
        val waiting = state(listOf(work(WorkInfo.State.ENQUEUED)), FlacSweepResult.Queued(40))
        assertThat(waiting.active).isTrue()
        assertThat(waiting.message).startsWith("Waiting to run.")
    }

    @Test fun `a finished batch says what it did, from the worker's own counts`() {
        val st = state(listOf(finished(upgraded = 3, noMatch = 1, failed = 2)), null)

        assertThat(st.active).isFalse()
        assertThat(st.message).isEqualTo("Done: 3 tracks upgraded to FLAC.")
        assertThat(st.detail).isEqualTo(
            "No lossless version yet for 1 track. They're checked again in two weeks.\n" +
                "Couldn't check 2 tracks this time. They're tried again next run.",
        )
    }

    @Test fun `a batch that found nothing says so`() {
        val st = state(listOf(finished(upgraded = 0, noMatch = 0, failed = 0)), null)

        assertThat(st.message).isEqualTo("Done: no new FLAC versions found.")
        assertThat(st.detail).isNull()
    }

    @Test fun `a batch cancelled from its notification is not reported as finished`() {
        val st = state(listOf(work(WorkInfo.State.CANCELLED)), FlacSweepResult.Queued(40))

        assertThat(st.message).isEqualTo("Stopped before it finished.")
    }

    @Test fun `a tap that queued nothing says why`() {
        assertThat(state(emptyList(), FlacSweepResult.LosslessDisabled).message)
            .isEqualTo("Set Download quality to Lossless in Audio & Quality first.")
        assertThat(state(emptyList(), FlacSweepResult.StreamingMode).message)
            .isEqualTo("Stash is set to Online, so there are no downloads to upgrade.")
        // ... and is newer news than the last batch's result.
        assertThat(state(listOf(finished(3, 0, 0)), FlacSweepResult.NothingToUpgrade).message)
            .startsWith("Nothing to upgrade.")
    }
}
