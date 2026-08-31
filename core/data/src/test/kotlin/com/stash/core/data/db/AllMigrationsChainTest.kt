package com.stash.core.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards [StashDatabase.ALL_MIGRATIONS] against the one mistake its KDoc can
 * only ask for in prose: adding `MIGRATION_N_N+1` and bumping `version` but
 * forgetting to append the val to the array.
 *
 * That omission has no compile-time symptom. It surfaces at runtime as
 * "A migration from N to N+1 was required but not found" — on the DI
 * singleton for anyone upgrading, and on the throwaway instance
 * DatabaseBackupManager opens over a staged backup to roll it forward before
 * a library-merge import (#235), where it takes the import down with it.
 *
 * The current version is read back from a real Room-built database rather
 * than restated here, so there is still exactly ONE place to declare it.
 *
 * Note the array captures its entries by reference in declaration order: a
 * `MIGRATION_*` val declared *below* `ALL_MIGRATIONS` enters it as null and
 * fails this test with an NPE rather than an assertion. That is still a red
 * test, which is the point — the array must stay the companion's last member.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AllMigrationsChainTest {

    private fun currentSchemaVersion(): Int {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        ).allowMainThreadQueries().build()
        return try {
            db.openHelper.readableDatabase.version
        } finally {
            db.close()
        }
    }

    @Test
    fun `ALL_MIGRATIONS is an unbroken chain ending at the current schema version`() {
        val edges = StashDatabase.ALL_MIGRATIONS
            .map { it.startVersion to it.endVersion }
            .sortedBy { it.first }

        // Every migration advances exactly one version, and each one picks up
        // where the previous left off — a gap here is a version no upgrading
        // install can cross.
        edges.forEach { (start, end) ->
            assertEquals("migration $start -> $end must advance exactly one version", start + 1, end)
        }
        edges.zipWithNext { (_, end), (nextStart, _) ->
            assertEquals("gap in the migration chain at version $end", end, nextStart)
        }

        // The one that catches the forgotten append: the chain must reach the
        // version the @Database annotation actually declares.
        assertEquals(
            "ALL_MIGRATIONS stops short of the current schema version — " +
                "a MIGRATION_* val was added without being appended to the array",
            currentSchemaVersion(),
            edges.last().second,
        )
    }
}
