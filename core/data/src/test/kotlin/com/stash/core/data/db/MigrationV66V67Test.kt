package com.stash.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.stash.core.data.lastfm.lastFmCacheKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v66 -> v67: an album and album artist that are exactly Last.fm's cached answer for the song were
 * the 0.9.120 guess, and are cleared; an album a source gave, or a file's, stays. Validated against
 * the exported 67.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV66V67Test {

    private val DB_NAME = "migration-v66v67-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private fun insertTrack(
        db: SupportSQLiteDatabase,
        id: Long,
        artist: String,
        title: String,
        album: String,
        albumArtist: String,
        filePath: String? = null,
    ) {
        db.execSQL(
            "INSERT INTO tracks (id, title, artist, album, album_artist, duration_ms, file_path, file_format, " +
                "quality_kbps, file_size_bytes, source, date_added, play_count, is_downloaded, canonical_title, " +
                "canonical_artist, match_confidence, match_dismissed) VALUES " +
                "(?, ?, ?, ?, ?, 200000, ?, 'opus', 0, 0, 'YOUTUBE', 1, 0, ?, ?, ?, 0, 0)",
            arrayOf<Any?>(
                id, title, artist, album, albumArtist, filePath, if (filePath != null) 1 else 0,
                title.lowercase(), artist.lowercase(),
            ),
        )
    }

    /** Last.fm's `track.getInfo` answer for [artist]/[title], cached under the client's key. */
    private fun cacheAnswer(db: SupportSQLiteDatabase, artist: String, title: String, json: String) {
        val key = lastFmCacheKey(
            mapOf("method" to "track.getInfo", "artist" to artist, "track" to title, "autocorrect" to "1"),
        )
        db.execSQL(
            "INSERT INTO lastfm_response_cache (cache_key, json, fetched_at) VALUES (?, ?, 1)",
            arrayOf<Any?>(key, json),
        )
    }

    private fun onAlbum(title: String, album: String, albumArtist: String) =
        """{"track":{"name":"$title","album":{"artist":"$albumArtist","title":"$album","image":[]}}}"""

    @Test fun `66 to 67 clears the albums Last fm guessed and keeps the rest`() {
        helper.createDatabase(DB_NAME, 66).use { db ->
            // A Search play: the guess put it on a compilation.
            insertTrack(db, 1, "The Weeknd", "Blinding Lights", "Now 35", "Various Artists")
            cacheAnswer(db, "The Weeknd", "Blinding Lights", onAlbum("Blinding Lights", "Now 35", "Various Artists"))
            // A topic channel's name: the client asked under the cleaned one.
            insertTrack(db, 2, "Mobb Deep - Topic", "Shook Ones, Pt. II", "Reminisce Hip Hop Classics", "Various Artists")
            cacheAnswer(
                db, "Mobb Deep", "Shook Ones, Pt. II",
                onAlbum("Shook Ones, Pt. II", "Reminisce Hip Hop Classics", "Various Artists"),
            )
            // A sync's album with no album artist: the guess always wrote both.
            insertTrack(db, 3, "Björk", "Army of Me", "Post", "")
            cacheAnswer(db, "Björk", "Army of Me", onAlbum("Army of Me", "Post", "Björk"))
            // An album Last.fm names another.
            insertTrack(db, 4, "Radiohead", "Reckoner", "In Rainbows", "Radiohead")
            cacheAnswer(db, "Radiohead", "Reckoner", onAlbum("Reckoner", "Reckoner (Remixes)", "Radiohead"))
            // A file's album, even one Last.fm agrees with.
            insertTrack(db, 5, "Eddie Floyd", "Knock on Wood", "Knock On Wood", "Eddie Floyd", filePath = "/music/k.opus")
            cacheAnswer(db, "Eddie Floyd", "Knock on Wood", onAlbum("Knock on Wood", "Knock On Wood", "Eddie Floyd"))
            // Never looked up, and a remembered miss.
            insertTrack(db, 6, "Ataxia", "Dust", "Automatic Writing", "Ataxia")
            insertTrack(db, 7, "Nobody", "Missing", "Somewhere", "Nobody")
            cacheAnswer(db, "Nobody", "Missing", """{"error":6,"message":"Track not found"}""")
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 67, true, StashDatabase.MIGRATION_66_67)

        db.query("SELECT id, album, album_artist FROM tracks ORDER BY id").use { c ->
            val rows = mutableListOf<Triple<Long, String, String>>()
            while (c.moveToNext()) rows += Triple(c.getLong(0), c.getString(1), c.getString(2))
            assertEquals(
                listOf(
                    Triple(1L, "", ""),
                    Triple(2L, "", ""),
                    Triple(3L, "Post", ""),
                    Triple(4L, "In Rainbows", "Radiohead"),
                    Triple(5L, "Knock On Wood", "Eddie Floyd"),
                    Triple(6L, "Automatic Writing", "Ataxia"),
                    Triple(7L, "Somewhere", "Nobody"),
                ),
                rows,
            )
        }
    }
}
