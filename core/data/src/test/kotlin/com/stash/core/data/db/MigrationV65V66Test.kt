package com.stash.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v65 -> v66: every stored "not explicit" becomes unknown, since Spotify syncs stored it for every
 * song whatever its rating; a true stays, and every stored video stays. Validated against the
 * exported 66.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV65V66Test {

    private val DB_NAME = "migration-v65v66-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private fun insertTrack(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        id: Long,
        source: String,
        youtubeId: String?,
        explicit: Int?,
        downloaded: Boolean = false,
        pickedAt: Long? = null,
    ) {
        db.execSQL(
            "INSERT INTO tracks (id, title, artist, album, duration_ms, file_path, file_format, quality_kbps, " +
                "file_size_bytes, source, youtube_id, date_added, play_count, is_downloaded, canonical_title, " +
                "canonical_artist, match_confidence, match_dismissed, match_picked_at, explicit) VALUES " +
                "(?, 'Song $id', 'Artist', 'Album', 200000, ?, 'opus', 0, 0, ?, ?, 1, 0, ?, 'song $id', 'artist', 0, 0, ?, ?)",
            arrayOf<Any?>(
                id, if (downloaded) "/music/song$id.opus" else null, source, youtubeId,
                if (downloaded) 1 else 0, pickedAt, explicit,
            ),
        )
    }

    @Test fun `65 to 66 turns every false flag into unknown and keeps the videos`() {
        helper.createDatabase(DB_NAME, 65).use { db ->
            insertTrack(db, 1, "SPOTIFY", "found1", explicit = 0)
            insertTrack(db, 2, "SPOTIFY", "found2", explicit = 1)
            insertTrack(db, 3, "SPOTIFY", "kept3", explicit = 0, downloaded = true)
            insertTrack(db, 4, "SPOTIFY", "picked4", explicit = 0, pickedAt = 5L)
            insertTrack(db, 5, "YOUTUBE", "own5", explicit = null)
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 66, true, StashDatabase.MIGRATION_65_66)

        db.query("SELECT id, explicit, youtube_id FROM tracks ORDER BY id").use { c ->
            val rows = mutableListOf<Triple<Long, Int?, String?>>()
            while (c.moveToNext()) {
                rows += Triple(c.getLong(0), if (c.isNull(1)) null else c.getInt(1), if (c.isNull(2)) null else c.getString(2))
            }
            assertEquals(
                listOf(
                    Triple(1L, null, "found1"), // false is unknown now
                    Triple(2L, 1, "found2"), // explicit stays
                    Triple(3L, null, "kept3"),
                    Triple(4L, null, "picked4"),
                    Triple(5L, null, "own5"), // unknown stays unknown
                ),
                rows,
            )
        }
    }
}
