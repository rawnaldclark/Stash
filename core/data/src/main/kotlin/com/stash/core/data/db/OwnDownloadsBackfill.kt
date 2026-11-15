package com.stash.core.data.db

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.repository.YourDownloadsPlaylist
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-time pass (#761, 2026-11-13): hand downloads made before every download the user chose
 * joined Your Downloads are put in it, so Refresh cleanup, which deletes a downloaded song in no
 * playlist, never takes one.
 *
 * Until this build only Search downloads joined Your Downloads. A song downloaded from Now
 * Playing, a ⋮ menu, an album or artist page, a multi-select or a radio was spared by the sweep
 * only through its queue row's `user_requested` mark (#752), and Downloads › Clear history erases
 * that row. This pass turns the mark into the link while it is still there. Which songs:
 * [TrackDao.ownDownloadsOutsideYourDownloads] (the marked ones, and, unclear but kept, an
 * unmarked tap from before taps were marked in v0.9.110; a song with no trace left can't be told
 * from one a mix rotated out and is left as it is).
 *
 * Safe by construction: it only adds links, never deletes or un-links anything, and never brings
 * back a song the user took out of Your Downloads (a song with any link to it is left out). One
 * query over the library and one transaction for the links, so a 10k-song library costs one
 * indexed pass.
 *
 * Runs once per install: a done flag in [prefs], written only after the links are in. A run cut
 * short is simply run again (the query leaves out what it already linked). The app starts it at
 * launch ([com.stash.core.data.repository.MusicRepositoryImpl.runMigrations]), in either mode, and
 * every orphan sweep waits for it ([ensureRan]): a sync's sweep can come first on the first
 * launch. A restored backup (a swap) brings its old, unlinked library back with the flag still
 * set, so [DatabaseBackupManager] calls [rearm].
 */
@Singleton
class OwnDownloadsBackfill(
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
    private val prefs: SharedPreferences,
) {

    @Inject constructor(
        @ApplicationContext context: Context,
        trackDao: TrackDao,
        playlistDao: PlaylistDao,
    ) : this(trackDao, playlistDao, context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    /** One run at a time: the launch's own call and a sync's sweep that comes in meanwhile. */
    private val running = Mutex()

    /**
     * [runOnce], awaited: whoever comes first runs the pass, anyone else waits for it, and once it
     * has run this is one prefs read. Off the main thread. Returns true once the pass has run (now
     * or before); false when it failed (logged), so the caller, the orphan sweep, holds off rather
     * than delete a song this pass would have kept. The next launch tries again.
     */
    suspend fun ensureRan(): Boolean = withContext(Dispatchers.IO) {
        if (prefs.getBoolean(DONE_KEY, false)) return@withContext true
        try {
            running.withLock { runOnce() }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "the one-time Your Downloads pass failed; the next launch tries again", e)
            false
        }
    }

    /** Links the hand downloads outside Your Downloads, unless this has run (then null). Returns how many. */
    suspend fun runOnce(): Int? {
        if (prefs.getBoolean(DONE_KEY, false)) return null
        val existing = playlistDao.findBySourceId(YourDownloadsPlaylist.SOURCE_ID)?.id ?: NO_PLAYLIST
        val ids = trackDao.ownDownloadsOutsideYourDownloads(existing, UNMARKED_BEFORE_MS)
        if (ids.isNotEmpty()) {
            val playlistId = playlistDao.ensurePlaylistAndAddAbsent(YourDownloadsPlaylist.entity(), ids)
            playlistDao.updateTrackCount(playlistId, trackDao.countByPlaylist(playlistId, includeStreamable = true))
        }
        // Written at once: the sweep that waits for this must not see it unset after a crash.
        prefs.edit().putBoolean(DONE_KEY, true).commit()
        Log.i(TAG, "Your Downloads: linked ${ids.size} song(s) downloaded by hand before #761")
        return ids.size
    }

    /** Asks for another run (a restored backup). Written at once: a restore restarts the app. */
    fun rearm() {
        prefs.edit().remove(DONE_KEY).commit()
    }

    companion object {
        /**
         * When v0.9.110 was released (381a83d2, 2026-09-29T01:38:56Z), the first release whose
         * taps mark their queue row `user_requested` (#518). An unmarked discovery-partition row
         * from before it can be a tap; after it, it is a playlist's or a followed mix's own.
         */
        const val UNMARKED_BEFORE_MS = 1_790_645_936_000L

        /** The app's one-time-migration flags file ([LoneCancelBackfill.PREFS_NAME]). */
        const val PREFS_NAME = LoneCancelBackfill.PREFS_NAME

        /** The done flag, in [PREFS_NAME]. */
        const val DONE_KEY = "own_downloads_in_your_downloads"

        /** No Your Downloads yet: no playlist has this id, so the query leaves nothing out for it. */
        private const val NO_PLAYLIST = -1L

        private const val TAG = "StashMigration"

        /** [rearm] over the app's flags file, for code that has a Context but no injection. */
        fun rearm(context: Context) {
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(DONE_KEY).commit()
        }
    }
}
