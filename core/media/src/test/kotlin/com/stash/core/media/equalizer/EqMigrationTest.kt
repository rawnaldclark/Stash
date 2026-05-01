package com.stash.core.media.equalizer

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test

class EqMigrationTest {
  private val newStore = mockk<EqStore>(relaxed = true)
  private val oldStore = mockk<LegacyEqualizerStore>(relaxed = true)

  @Test fun `migration forces enabled=false even if legacy was true`() = runBlocking {
    coEvery { newStore.read() } returns EqState() // not yet migrated; defaults
    coEvery { oldStore.exists() } returns true
    coEvery { oldStore.readLegacy() } returns LegacySettings(
      enabled = true, presetName = "ROCK",
      gains = listOf(400, 200, -100, 200, 300), bassBoostStrength = 500
    )

    EqMigration(newStore, oldStore).migrateIfNeeded()

    coVerify { newStore.write(match { it.enabled == false }) }
  }

  @Test fun `migration preserves gain bands and preset id`() = runBlocking {
    coEvery { newStore.read() } returns EqState()
    coEvery { oldStore.exists() } returns true
    coEvery { oldStore.readLegacy() } returns LegacySettings(
      enabled = true, presetName = "ROCK",
      gains = listOf(400, 200, -100, 200, 300), bassBoostStrength = 500
    )

    EqMigration(newStore, oldStore).migrateIfNeeded()

    coVerify {
      newStore.write(match {
        it.presetId == "rock" &&
        it.gainsDb.contentEquals(floatArrayOf(4f, 2f, -1f, 2f, 3f)) &&
        it.bassBoostDb in 7.4f..7.6f // 500/1000 * 15 = 7.5
      })
    }
  }

  @Test fun `migration is idempotent — skip if already migrated`() = runBlocking {
    coEvery { newStore.read() } returns EqState(presetId = "vocal") // already migrated
    EqMigration(newStore, oldStore).migrateIfNeeded()
    coVerify(exactly = 0) { newStore.write(any()) }
    coVerify(exactly = 0) { oldStore.readLegacy() }
  }

  @Test fun `migration with no legacy data writes default with enabled=false`() = runBlocking {
    coEvery { newStore.read() } returns EqState()
    coEvery { oldStore.exists() } returns false
    EqMigration(newStore, oldStore).migrateIfNeeded()
    // Even with no legacy, write a marker state so we don't re-run
    coVerify { newStore.write(match { it.enabled == false }) }
  }

  @Test fun `migration deletes legacy store on success`() = runBlocking {
    coEvery { newStore.read() } returns EqState()
    coEvery { oldStore.exists() } returns true
    coEvery { oldStore.readLegacy() } returns LegacySettings(
      enabled = false, presetName = "FLAT", gains = listOf(0,0,0,0,0), bassBoostStrength = 0)
    EqMigration(newStore, oldStore).migrateIfNeeded()
    coVerify { oldStore.deleteLegacy() }
  }

  // Regression test for the v0.8.0 production crash. Empty legacy gains
  // ([]) hit `coerceIn(0, -1)` because `List.lastIndex` is -1 for an
  // empty list. Crashed StashPlaybackService.onCreate via the
  // runBlocking call inside EqController.<init>, killing every play
  // attempt for users with the empty-array legacy state.
  @Test fun `migration with empty legacy gains does not crash`() = runBlocking {
    coEvery { newStore.read() } returns EqState()
    coEvery { oldStore.exists() } returns true
    coEvery { oldStore.readLegacy() } returns LegacySettings(
      enabled = true,
      presetName = "FLAT",
      gains = emptyList(),  // the crashing input
      bassBoostStrength = 0,
    )

    EqMigration(newStore, oldStore).migrateIfNeeded()

    coVerify {
      newStore.write(match {
        it.gainsDb.contentEquals(floatArrayOf(0f, 0f, 0f, 0f, 0f))
      })
    }
  }

  @Test fun `migration handles non-5 legacy gain sizes gracefully`() = runBlocking {
    coEvery { newStore.read() } returns EqState()
    coEvery { oldStore.exists() } returns true
    coEvery { oldStore.readLegacy() } returns LegacySettings(
      enabled = false,
      presetName = "FLAT",
      gains = listOf(300, -300, 100), // 3-band legacy → must resample to 5
      bassBoostStrength = 0,
    )

    // Just verifies no throw — the resample math is best-effort, the
    // contract is "produce 5 bands without crashing."
    EqMigration(newStore, oldStore).migrateIfNeeded()

    coVerify {
      newStore.write(match { it.gainsDb.size == 5 })
    }
  }
}
