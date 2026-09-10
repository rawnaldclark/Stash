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
 * Verifies migration v43 -> v44: every stored Last.fm cover (300x300 PNG on
 * either `lastfm.` or `lastfm-img.` host) is rewritten to the 770-wide JPEG
 * the CDN serves for the same hash. Other hosts are left alone, including
 * inside a playlist's pipe-joined mosaic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV43V44Test {
    private val DB_NAME = "migration-v43v44-test"

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
    fun `lastfm 300px png covers become 770 wide jpegs, everything else is untouched`() {
        helper.createDatabase(DB_NAME, 43).use { db ->
            insertTrack(db, 1, "https://lastfm-img.freetls.fastly.net/i/u/300x300/a1e2a5b1e851d66bc10112a4dbceb750.png")
            insertTrack(db, 2, "https://lastfm.freetls.fastly.net/i/u/300x300/f93cfd7cdcea45b29987f964751aa8bd.png")
            insertTrack(db, 3, "https://i.ytimg.com/vi/abc/hqdefault.jpg")
            insertTrack(db, 4, "https://static.qobuz.com/images/covers/x/300x300.png")
            insertTrack(db, 5, null)
            db.execSQL(
                """
                INSERT INTO playlists (id, name, source, source_id, type, track_count, is_active, art_url)
                VALUES (7, 'Mix', 'STASH', 'mix-7', 'CUSTOM', 2, 1,
                    'https://lastfm-img.freetls.fastly.net/i/u/300x300/aaa.png|https://i.ytimg.com/vi/bbb/hqdefault.jpg')
                """.trimIndent(),
            )
        }
        val migrated = helper.runMigrationsAndValidate(
            DB_NAME, 44, true, StashDatabase.MIGRATION_43_44,
        )
        val expected = mapOf(
            1L to "https://lastfm-img.freetls.fastly.net/i/u/770x0/a1e2a5b1e851d66bc10112a4dbceb750.jpg",
            2L to "https://lastfm.freetls.fastly.net/i/u/770x0/f93cfd7cdcea45b29987f964751aa8bd.jpg",
            3L to "https://i.ytimg.com/vi/abc/hqdefault.jpg",
            4L to "https://static.qobuz.com/images/covers/x/300x300.png",
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
                "https://lastfm-img.freetls.fastly.net/i/u/770x0/aaa.jpg|https://i.ytimg.com/vi/bbb/hqdefault.jpg",
                c.getString(0),
            )
        }
    }
}
