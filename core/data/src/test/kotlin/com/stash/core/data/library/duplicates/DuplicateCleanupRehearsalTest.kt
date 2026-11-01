package com.stash.core.data.library.duplicates

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.files.LocalFileOps
import com.stash.core.data.library.CanonicalRecompute
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A rehearsal of the one-time cleanup on a real library: a COPY of a phone's `stash.db` (a pulled
 * database or the one inside a backup zip) is migrated to the current schema, the cleanup runs on
 * it, and what it did is printed (the test's standard output, in its result XML). The file named
 * is never opened; it is copied into a temporary folder first.
 *
 * Skipped unless `STASH_DUPLICATES_REHEARSAL_DB` names the file:
 * `STASH_DUPLICATES_REHEARSAL_DB=/path/stash.db ./gradlew :core:data:testDebugUnitTest --tests '*DuplicateCleanupRehearsalTest*'`
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DuplicateCleanupRehearsalTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `rehearse the cleanup on a copy of a real library`() = runBlocking {
        val source = System.getenv("STASH_DUPLICATES_REHEARSAL_DB")?.let(::File)
        assumeTrue("set STASH_DUPLICATES_REHEARSAL_DB to rehearse", source != null && source.isFile)
        val copy = File(tmp.root, "stash.db")
        source!!.copyTo(copy)
        listOf("-wal", "-shm").forEach { ext -> File(source.path + ext).takeIf { it.isFile }?.copyTo(File(copy.path + ext)) }

        val context: Context = ApplicationProvider.getApplicationContext()
        val db = Room.databaseBuilder(context, StashDatabase::class.java, copy.absolutePath)
            .addMigrations(*StashDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            fun count(sql: String) = db.openHelper.readableDatabase.query(sql).use { it.moveToFirst(); it.getInt(0) }
            CanonicalRecompute(db).run()
            val tracks = count("SELECT COUNT(*) FROM tracks")
            val stubs = db.duplicateDao().stubCount()
            val stubsInNoPlaylist = count(
                "SELECT COUNT(*) FROM tracks t WHERE (t.youtube_id IS NULL OR t.youtube_id = '') AND " +
                    "(t.spotify_uri IS NULL OR t.spotify_uri = '') AND t.soundcloud_id IS NULL AND t.source = 'YOUTUBE' AND " +
                    "NOT EXISTS (SELECT 1 FROM playlist_tracks pt WHERE pt.track_id = t.id AND pt.removed_at IS NULL)",
            )
            val before = PossibleDuplicates(db, TrackMerger(db), LocalFileOps(context)).find()
            val started = System.currentTimeMillis()
            val summary = DuplicateCleanup(db, TrackMerger(db), DuplicateCleanupState.forContext(context), mockRunner()).run()
            val tookMs = System.currentTimeMillis() - started
            val after = PossibleDuplicates(db, TrackMerger(db), LocalFileOps(context)).find()
            println("REHEARSAL tracks=$tracks stubs=$stubs stubsInNoPlaylist=$stubsInNoPlaylist")
            println("REHEARSAL summary=$summary tookMs=$tookMs tracksAfter=${count("SELECT COUNT(*) FROM tracks")}")
            println("REHEARSAL listeningEvents=${count("SELECT COUNT(*) FROM listening_events")} aliases=${count("SELECT COUNT(*) FROM track_aliases")}")
            println("REHEARSAL possibleBefore=${before.groupingBy { it.kind }.eachCount()} possibleAfter=${after.groupingBy { it.kind }.eachCount()}")
            println("REHEARSAL stubsLeft=${db.duplicateDao().stubCount()} keptForMixPools=${count(
                "SELECT COUNT(*) FROM tracks t WHERE (t.youtube_id IS NULL OR t.youtube_id = '') AND (t.spotify_uri IS NULL OR " +
                    "t.spotify_uri = '') AND t.source = 'YOUTUBE' AND EXISTS (SELECT 1 FROM discovery_queue d WHERE d.track_id = t.id " +
                    "AND d.status != 'FAILED') AND NOT EXISTS (SELECT 1 FROM playlist_tracks pt WHERE pt.track_id = t.id AND pt.removed_at IS NULL)",
            )}")
            after.take(25).forEach { println("REHEARSAL pair ${it.kind}: ${it.keeper.artist} - ${it.keeper.title} | ${it.other.title}") }
            val second = DuplicateCleanup(db, TrackMerger(db), DuplicateCleanupState.forContext(context), mockRunner()).run()
            println("REHEARSAL secondRun=$second")
        } finally {
            db.close()
        }
    }

    private fun mockRunner() = io.mockk.mockk<com.stash.core.data.library.CanonicalRecomputeRunner>(relaxed = true)
}
