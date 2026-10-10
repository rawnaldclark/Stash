package com.stash.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v53 -> v54 adds `listening_events.origin_device` (link-sync spec §7.2): the device a play came from when it wasn't played
 * here. Every existing play is this phone's own, so it reads as NULL and keeps feeding mixes and scrobbles as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV53V54Test {

    private val DB_NAME = "migration-v53v54-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun `53 to 54 adds origin_device and keeps the plays as this phone's own`() {
        helper.createDatabase(DB_NAME, 53).use { db ->
            db.execSQL(
                "INSERT INTO tracks (id, title, artist, album, duration_ms, file_format, quality_kbps, " +
                    "file_size_bytes, source, date_added, play_count, is_downloaded, canonical_title, " +
                    "canonical_artist, match_confidence, match_dismissed, match_flagged, match_picked_at) " +
                    "VALUES (1, 'Lacrymosa', 'Evanescence', 'Synthesis', 230000, 'opus', 160, 0, 'SPOTIFY', " +
                    "0, 3, 1, 'lacrymosa', 'evanescence', 0.0, 0, 0, 5000)",
            )
            db.execSQL("INSERT INTO listening_events (id, track_id, started_at, scrobbled, yt_scrobbled, completed_at) VALUES (7, 1, 1000, 1, 0, 31000)")
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 54, true, StashDatabase.MIGRATION_53_54)

        db.query("SELECT track_id, started_at, scrobbled, completed_at, origin_device FROM listening_events").use { c ->
            assertTrue(c.moveToNext())
            assertEquals(1L, c.getLong(0))
            assertEquals(1000L, c.getLong(1))
            assertEquals(1, c.getInt(2))
            assertEquals(31000L, c.getLong(3))
            assertTrue("an existing play is this phone's own", c.isNull(4))
        }
    }
}
