package com.stash.core.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Guards the restore safety gate: importDatabase must verify a staged backup
 * before it ever replaces the live database, so a truncated/corrupt backup can't
 * destroy the user's only library.
 *
 * ⚠️ READ THIS BEFORE TRUSTING THIS CLASS. Robolectric's SQLite is far more
 * permissive than a device's, and these tests cover much less than they look
 * like they do. Measured directly on 2026-07-26:
 *   - it does NOT enforce `OPEN_READONLY` — a read-only handle accepts `INSERT`;
 *   - on a READ-WRITE handle it does NOT reject a corrupt file — a valid 16-byte
 *     SQLite header followed by pure junk opens fine and reports
 *     `integrity_check` = ok.
 *
 * So the ONLY part of the gate these unit tests genuinely verify is the explicit
 * header check in [DatabaseBackupManager.verifyDatabaseIntegrity]. The
 * `PRAGMA integrity_check` arm and the read-only/FTS interaction behind #370 are
 * device-only behaviours — verify those by exporting and re-importing on real
 * hardware, and do not let a green run here stand in for that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DatabaseBackupIntegrityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var manager: DatabaseBackupManager
    private lateinit var tmpDir: File

    @Before fun setUp() {
        // The DB dependency is only touched on commit; integrity verification
        // never uses it, so a mock is fine here.
        manager = DatabaseBackupManager(context, mockk(relaxed = true))
        tmpDir = File(context.cacheDir, "backup-test").apply { mkdirs() }
    }

    @After fun tearDown() { tmpDir.deleteRecursively() }

    @Test fun `a valid sqlite database passes verification`() {
        val good = File(tmpDir, "good.db")
        SQLiteDatabase.openOrCreateDatabase(good, null).use {
            it.execSQL("CREATE TABLE t(x INTEGER)")
            it.execSQL("INSERT INTO t VALUES (1)")
        }

        manager.verifyDatabaseIntegrity(good) // must not throw
    }

    /**
     * The fixture above is a PLAIN table, and that hole shipped a 100%-broken
     * restore in v0.9.83 (#370).
     *
     * A real Stash backup contains `tracks_fts`, an FTS4 virtual table. On such a
     * database `PRAGMA integrity_check` doesn't just read pages — it dispatches
     * the FTS module's own check as `INSERT INTO tracks_fts(tracks_fts)
     * VALUES('integrity-check')`. That statement is write-SHAPED (it persists
     * nothing), so on a connection opened `OPEN_READONLY` SQLite throws
     * "attempt to write a readonly database" and EVERY import was rejected —
     * valid backups included — while this suite stayed green, because a plain
     * table never takes the FTS path.
     *
     * ⚠️ HONEST LIMIT: this test does NOT reproduce that bug, and adding an FTS
     * table is not enough to make it. **Robolectric does not enforce
     * `OPEN_READONLY`** — probed directly here on 2026-07-26: opening a database
     * READONLY and then running `INSERT` SUCCEEDS under Robolectric, where a real
     * device throws. So the read-only-vs-FTS interaction is invisible to every
     * JVM unit test in this repo, and no test here can guard it. The #370 fix was
     * verified the only way it can be: export→import on a physical device.
     *
     * What this test IS worth: it pins that a backup shaped like a REAL one (an
     * FTS4 virtual table present, matching TrackFts) is accepted by the gate, so
     * a future change that rejects FTS databases for some other reason gets
     * caught. Don't mistake it for a guard on the read-only bug.
     */
    @Test fun `a backup containing an FTS4 table passes verification`() {
        val withFts = File(tmpDir, "fts.db")
        SQLiteDatabase.openOrCreateDatabase(withFts, null).use {
            it.execSQL("CREATE TABLE tracks(id INTEGER PRIMARY KEY, title TEXT, artist TEXT)")
            // Mirrors TrackFts: FTS4, unicode61 tokenizer (contentless-external
            // content isn't needed to exercise the integrity_check path).
            it.execSQL(
                "CREATE VIRTUAL TABLE tracks_fts USING fts4(title, artist, tokenize=unicode61)",
            )
            it.execSQL("INSERT INTO tracks VALUES (1, 'Everything In Its Right Place', 'Radiohead')")
            it.execSQL("INSERT INTO tracks_fts(title, artist) VALUES ('Everything In Its Right Place', 'Radiohead')")
        }

        manager.verifyDatabaseIntegrity(withFts) // must not throw
    }

    @Test fun `a truncated or garbage file is rejected`() {
        val garbage = File(tmpDir, "corrupt.db").apply {
            writeBytes(byteArrayOf(0x53, 0x51, 0x4C, 0x69, 0x74, 0x65, 0x00, 0x01)) // partial header
        }

        assertThrows(Exception::class.java) { manager.verifyDatabaseIntegrity(garbage) }
    }

    /** The Settings dialog shows the message as is: SQLite's words go to the log through `detail` (#838). */
    @Test fun `a rejected file is told in plain words and SQLite's stay in the detail`() {
        val notADatabase = File(tmpDir, "notes.db").apply { writeText("not a database") }

        val e = assertThrows(DatabaseIntegrityException::class.java) {
            manager.verifyDatabaseIntegrity(notADatabase, repairSearchIndex = true)
        }

        assertEquals("The backup database is corrupt and was not restored.", e.message)
        assertTrue(e.detail, e.detail.contains("header"))
    }
}
