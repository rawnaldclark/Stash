package com.stash.feature.settings

import android.content.res.Resources
import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.workDataOf
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.lossless.FlacSweepResult
import com.stash.core.data.sync.workers.FlacUpgradeWorker
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import org.junit.Test

/**
 * Library Health's "FLAC upgrades" card reads the batch worker's own state, so it tells
 * the truth after the screen was left, and a stopped run never reads as finished.
 */
class FlacUpgradeUiStateTest {

    private val res: Resources = mockk {
        every { getString(R.string.settings_flac_upgrade_checking) } returns "Checking for upgrades…"
        every { getString(R.string.settings_flac_upgrade_waiting) } returns "Waiting to run. It starts by itself when it can, on Wi-Fi if sync is set to Wi-Fi only."
        every { getString(R.string.settings_flac_upgrade_stopped) } returns "Stopped before it finished."
        every { getString(R.string.settings_flac_upgrade_streaming_mode) } returns "Stash is set to Online, so there are no downloads to upgrade."
        every { getString(R.string.settings_flac_upgrade_lossless_disabled) } returns "Set Download quality to Lossless in Audio & Quality first."
        every { getString(R.string.settings_flac_upgrade_nothing) } returns "Nothing to upgrade. Every downloaded track is FLAC already or was checked in the last two weeks."
        every { getString(R.string.settings_flac_upgrade_done_none) } returns "Done: no new FLAC versions found."
        every { getString(R.string.settings_flac_upgrade_done_upgraded, any()) } answers { "Done: ${(args[1] as Array<*>)[0]} upgraded to FLAC." }
        every { getString(R.string.settings_flac_upgrade_detail_no_match, any()) } answers { "No lossless version yet for ${(args[1] as Array<*>)[0]}. They're checked again in two weeks." }
        every { getString(R.string.settings_flac_upgrade_detail_failed, any()) } answers { "Couldn't check ${(args[1] as Array<*>)[0]} this time. They're tried again next run." }
        every { getQuantityString(R.plurals.settings_flac_upgrade_tracks, any(), any()) } answers {
            val count = (args[2] as Array<*>)[0]
            val fmt = if (count is Number) "%,d".format(count) else count.toString()
            if (args[1] == 1) "1 track" else "$fmt tracks"
        }
    }

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
