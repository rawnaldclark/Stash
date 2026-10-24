package com.stash.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.stash.core.data.db.AllSongsMigrationFixtures.assertLibraryUnchanged
import com.stash.core.data.db.AllSongsMigrationFixtures.count
import com.stash.core.data.db.AllSongsMigrationFixtures.seedLibrary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v62 -> v63 (Now Playing's back cover flip): the empty `album_back_covers` table. Nothing existing
 * changes. Walked from 62 and from 61; each run validates every table and index against the
 * exported 63.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV62V63Test {

    private val DB_NAME = "migration-v62v63-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val from62 = arrayOf(StashDatabase.MIGRATION_62_63)
    private val from61 = arrayOf(StashDatabase.MIGRATION_61_62, *from62)

    @Test fun `62 to 63 adds the back cover table and keeps the library`() {
        helper.createDatabase(DB_NAME, 62).use { seedLibrary(it) }

        val db = helper.runMigrationsAndValidate(DB_NAME, 63, true, *from62)

        assertEquals(0, count(db, "SELECT COUNT(*) FROM album_back_covers"))
        assertLibraryUnchanged(db)
    }

    @Test fun `61 reaches 63`() {
        helper.createDatabase(DB_NAME, 61).use { seedLibrary(it) }
        val db = helper.runMigrationsAndValidate(DB_NAME, 63, true, *from61)
        assertEquals(0, count(db, "SELECT COUNT(*) FROM album_back_covers"))
        assertLibraryUnchanged(db)
    }

    @Test fun `an album key matches whatever its case`() {
        helper.createDatabase(DB_NAME, 62).use { seedLibrary(it) }
        val db = helper.runMigrationsAndValidate(DB_NAME, 63, true, *from62)
        db.execSQL("INSERT INTO album_back_covers (album_key, image_url, release_mbid, attempted_at) VALUES ('nirvana\u001Fnevermind', 'u', 'r', 1)")
        assertEquals(1, count(db, "SELECT COUNT(*) FROM album_back_covers WHERE album_key = 'NIRVANA\u001FNEVERMIND'"))
    }
}
