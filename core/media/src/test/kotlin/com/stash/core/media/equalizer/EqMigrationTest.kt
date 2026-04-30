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
}
