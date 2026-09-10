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
 * Verifies migration v44 -> v45: every stored YouTube thumbnail is rewritten
 * to `maxresdefault` — a clean 1280x720 frame instead of the 480x360
 * `hqdefault`, which pads 16:9 into 4:3 and so spends 24% of the cropped
 * square on black bars. Videos without one are caught at fetch time by
 * `ArtFallbackInterceptor`, which walks back down to `sddefault` then
 * `hqdefault`. Other hosts are untouched, including inside a mosaic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV44V45Test {
    private val DB_NAME = "migration-v44v45-test"

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
    fun `youtube thumbnails become maxresdefault, everything else is untouched`() {
        helper.createDatabase(DB_NAME, 44).use { db ->
            insertTrack(db, 1, "https://i.ytimg.com/vi/_uofQD-N6UI/hqdefault.jpg")
            insertTrack(db, 2, "https://i.ytimg.com/vi_webp/abc/hqdefault.webp")
            insertTrack(db, 3, "https://i.ytimg.com/vi/abc/mqdefault.jpg")
            insertTrack(db, 4, "https://i.ytimg.com/vi/abc/maxresdefault.jpg")
            insertTrack(db, 5, "https://lastfm-img.freetls.fastly.net/i/u/770x0/abc.jpg")
            insertTrack(db, 6, null)
            db.execSQL(
                """
                INSERT INTO playlists (id, name, source, source_id, type, track_count, is_active, art_url)
                VALUES (7, 'Mix', 'STASH', 'mix-7', 'CUSTOM', 2, 1,
                    'https://i.ytimg.com/vi/aaa/hqdefault.jpg|https://lastfm-img.freetls.fastly.net/i/u/770x0/bbb.jpg')
                """.trimIndent(),
            )
        }
        val migrated = helper.runMigrationsAndValidate(
            DB_NAME, 45, true, StashDatabase.MIGRATION_44_45,
        )
        val expected = mapOf(
            1L to "https://i.ytimg.com/vi/_uofQD-N6UI/maxresdefault.jpg",
            2L to "https://i.ytimg.com/vi_webp/abc/maxresdefault.webp",
            3L to "https://i.ytimg.com/vi/abc/maxresdefault.jpg",
            4L to "https://i.ytimg.com/vi/abc/maxresdefault.jpg",
            5L to "https://lastfm-img.freetls.fastly.net/i/u/770x0/abc.jpg",
            6L to null,
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
                "https://i.ytimg.com/vi/aaa/maxresdefault.jpg|" +
                    "https://lastfm-img.freetls.fastly.net/i/u/770x0/bbb.jpg",
                c.getString(0),
            )
        }
    }
}
