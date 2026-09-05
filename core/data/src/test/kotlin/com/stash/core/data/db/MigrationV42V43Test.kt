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
 * Verifies migration v42 -> v43: every stored YouTube thumbnail that the art
 * upgrader rewrote to `sddefault.jpg` (a variant YouTube only generates for
 * some videos — 404 for about 1 in 12, drawn as black art) is rewritten to
 * `hqdefault.jpg`, the variant that exists for every video. Other hosts and
 * other filenames are left alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV42V43Test {

    private val DB_NAME = "migration-v42v43-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private fun insertTrack(db: androidx.sqlite.db.SupportSQLiteDatabase, id: Long, art: String?) {
        val artSql = if (art == null) "NULL" else "'$art'"
        db.execSQL(
            """
            INSERT INTO tracks (id, title, artist, album, duration_ms, file_format, quality_kbps,
                file_size_bytes, source, date_added, play_count, is_downloaded,
                canonical_title, canonical_artist, match_confidence, match_dismissed, album_art_url)
            VALUES ($id, 't$id', 'a', 'al', 1000, 'opus', 160, 0, 'YOUTUBE', 0, 0, 0,
                't$id', 'a', 0.0, 0, $artSql)
            """.trimIndent(),
        )
    }

    @Test
    fun `sddefault thumbnails become hqdefault, everything else is untouched`() {
        helper.createDatabase(DB_NAME, 42).use { db ->
            insertTrack(db, 1, "https://i.ytimg.com/vi/_uofQD-N6UI/sddefault.jpg")
            insertTrack(db, 2, "https://i.ytimg.com/vi_webp/abc/sddefault.webp")
            insertTrack(db, 3, "https://i.ytimg.com/vi/abc/hqdefault.jpg")
            insertTrack(db, 4, "https://static.qobuz.com/images/covers/x/sddefault.jpg")
            insertTrack(db, 5, null)
            db.execSQL(
                """
                INSERT INTO playlists (id, name, source, source_id, type, track_count, is_active, art_url)
                VALUES (7, 'Mix', 'STASH', 'mix-7', 'CUSTOM', 2, 1,
                    'https://i.ytimg.com/vi/aaa/sddefault.jpg|https://i.ytimg.com/vi/bbb/sddefault.jpg')
                """.trimIndent(),
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            DB_NAME, 43, true, StashDatabase.MIGRATION_42_43,
        )

        val expected = mapOf(
            1L to "https://i.ytimg.com/vi/_uofQD-N6UI/hqdefault.jpg",
            2L to "https://i.ytimg.com/vi_webp/abc/hqdefault.webp",
            3L to "https://i.ytimg.com/vi/abc/hqdefault.jpg",
            4L to "https://static.qobuz.com/images/covers/x/sddefault.jpg",
            5L to null,
        )
        migrated.query("SELECT id, album_art_url FROM tracks ORDER BY id").use { c ->
            var rows = 0
            while (c.moveToNext()) {
                rows++
                val id = c.getLong(0)
                val art = if (c.isNull(1)) null else c.getString(1)
                assertEquals("row $id", expected.getValue(id), art)
            }
            assertTrue(rows == expected.size)
        }
        migrated.query("SELECT art_url FROM playlists WHERE id = 7").use { c ->
            assertTrue(c.moveToNext())
            assertEquals(
                "https://i.ytimg.com/vi/aaa/hqdefault.jpg|https://i.ytimg.com/vi/bbb/hqdefault.jpg",
                c.getString(0),
            )
        }
    }
}
