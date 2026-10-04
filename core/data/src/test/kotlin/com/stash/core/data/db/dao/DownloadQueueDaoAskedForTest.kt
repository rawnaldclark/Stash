package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.SharedMixEntity
import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.DownloadFailureType
import com.stash.core.model.DownloadStatus
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Background downloads take only what the user asked for (#532). A row outside any
 * sync is asked for when the user tapped it (user_requested), or while its song is in
 * a playlist kept on the phone or in a followed mix with "Download this mix" on. Any
 * other such row is a leftover, which v0.9.110 downloaded at every app start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DownloadQueueDaoAskedForTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: DownloadQueueDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.downloadQueueDao()
    }

    @After fun tearDown() { db.close() }

    // The playlists every test can use.
    private var kept = 0L
    private var followedOn = 0L
    private var followedOff = 0L
    private var stoppedSharing = 0L
    private var synced = 0L
    private var stashMix = 0L
    private var hiddenKept = 0L

    private suspend fun playlists() {
        kept = playlist("Kept", "custom_kept", keepOffline = true)
        followedOn = playlist(
            "Followed, Download on", "share:on", syncEnabled = true, follow = SharedMixEntity.STATUS_ACTIVE,
        )
        followedOff = playlist("Followed, Download off", "share:off", follow = SharedMixEntity.STATUS_ACTIVE)
        // The owner stopped sharing: an ordinary playlist now, with the page's own Download button.
        stoppedSharing = playlist(
            "Owner stopped sharing", "share:gone", syncEnabled = true, follow = SharedMixEntity.STATUS_REMOVED,
        )
        // A user's own playlist is created with sync on; that is not a download switch.
        synced = playlist("Synced, not kept", "custom_synced", syncEnabled = true)
        stashMix = playlist("Stash Mix", "stash_mix_1", syncEnabled = true, type = PlaylistType.STASH_MIX)
        hiddenKept = playlist("Kept, but hidden", "custom_hidden", keepOffline = true, isActive = false)
    }

    @Test fun `the drain takes only downloads the user asked for`() = runTest {
        playlists()
        val tapped = queued("Tapped", DownloadStatus.PENDING, userRequested = true)
        val keptSong = queued("Kept", DownloadStatus.PENDING, kept)
        val followedSong = queued("Followed", DownloadStatus.PENDING, followedOn)
        val keptRetry = queued("Kept, failed once", DownloadStatus.FAILED, kept, retryCount = 1)
        queued("Kept, out of retries", DownloadStatus.FAILED, kept, retryCount = 3)
        // Leftovers.
        queued("Old discovery", DownloadStatus.PENDING)
        queued("Verify's", DownloadStatus.PENDING, synced, searchQuery = "")
        queued("Stash Mix's", DownloadStatus.PENDING, stashMix)
        queued("Followed, Download off", DownloadStatus.PENDING, followedOff)
        queued("Owner stopped sharing", DownloadStatus.PENDING, stoppedSharing)
        queued("Kept, but hidden", DownloadStatus.PENDING, hiddenKept)
        queued("Taken out of Kept", DownloadStatus.PENDING, kept, removedFromFirst = true)
        queued("Tapped before v0.9.110, failed", DownloadStatus.FAILED, retryCount = 1)
        queued("Sync's", DownloadStatus.PENDING, syncId = syncRun())

        assertThat(dao.pendingDiscoveryDownloads().map { it.trackId })
            .containsExactly(tapped, keptSong, followedSong, keptRetry)
    }

    /** The worker shows its notification before it reads the queue, so leftovers must start nothing. */
    @Test fun `leftovers alone start no drain`() = runTest {
        playlists()
        queued("Old discovery", DownloadStatus.PENDING)
        queued("Verify's", DownloadStatus.PENDING, synced, searchQuery = "")
        queued("Taken out of Kept", DownloadStatus.PENDING, kept, removedFromFirst = true)
        // Failed songs retry when a drain runs for real work, never start one (#474).
        queued("Kept, failed once", DownloadStatus.FAILED, kept, retryCount = 1)

        assertThat(dao.hasPendingDiscoveryDownload()).isFalse()

        queued("Kept", DownloadStatus.PENDING, kept)
        assertThat(dao.hasPendingDiscoveryDownload()).isTrue()
    }

    /** The lossless retry downloads what it resolves, so it takes the same rows the drain would. */
    @Test fun `the lossless retry takes only waits the user asked for`() = runTest {
        playlists()
        val keptWait = queued("Kept", DownloadStatus.WAITING_FOR_LOSSLESS, kept)
        val tappedWait = queued("Tapped", DownloadStatus.WAITING_FOR_LOSSLESS, userRequested = true)
        val syncWait = queued("Sync's", DownloadStatus.WAITING_FOR_LOSSLESS, synced, syncId = syncRun())
        queued("Old playlist download", DownloadStatus.WAITING_FOR_LOSSLESS)
        queued("Verify's", DownloadStatus.WAITING_FOR_LOSSLESS, synced, searchQuery = "")
        queued("Owner stopped sharing", DownloadStatus.WAITING_FOR_LOSSLESS, stoppedSharing)

        assertThat(dao.waitingForLosslessTracks(streamOnly = false).map { it.trackId })
            .containsExactly(keptWait, tappedWait, syncWait)
    }

    /**
     * Stream-only mode downloads no sync's songs (#474), and the lossless retry downloads every
     * row it resolves: there it takes a sync's row only when the user asked for the song. The
     * others keep waiting, for Download mode.
     */
    @Test fun `in Stream-only the lossless retry takes a sync's wait only if the user asked for the song`() = runTest {
        playlists()
        val keptSyncWait = queued("Kept, a sync's", DownloadStatus.WAITING_FOR_LOSSLESS, kept, syncId = syncRun())
        val followedSyncWait = queued(
            "Followed, a sync's", DownloadStatus.WAITING_FOR_LOSSLESS, followedOn, syncId = syncRun(),
        )
        val tappedSyncWait = queued(
            "Tapped, a sync's", DownloadStatus.WAITING_FOR_LOSSLESS, synced, syncId = syncRun(), userRequested = true,
        )
        val keptWait = queued("Kept", DownloadStatus.WAITING_FOR_LOSSLESS, kept)
        val syncedOnly = queued("Synced only", DownloadStatus.WAITING_FOR_LOSSLESS, synced, syncId = syncRun())

        assertThat(dao.waitingForLosslessTracks(streamOnly = true).map { it.trackId })
            .containsExactly(keptSyncWait, followedSyncWait, tappedSyncWait, keptWait)
        assertThat(dao.waitingForLosslessTracks(streamOnly = false).map { it.trackId })
            .containsExactly(keptSyncWait, followedSyncWait, tappedSyncWait, keptWait, syncedOnly)
    }

    /**
     * Download mode: a sync's wait comes back only while a playlist the sync downloads still holds
     * the song, the rule the sync's own pickup uses. Switching a playlist off (the Sync tab, or the
     * page's Download) drops only its queued songs, so its waits stayed, and the lossless retry
     * downloaded them anyway.
     */
    @Test fun `in Download mode the lossless retry skips a sync's wait whose playlist was switched off`() = runTest {
        playlists()
        val goingOff = playlist("Synced, then switched off", "custom_going_off", syncEnabled = true)
        val hiddenSynced = playlist("Synced, but hidden", "custom_hidden_synced", syncEnabled = true, isActive = false)
        val queuedSong = queued("Queued", DownloadStatus.PENDING, goingOff, syncId = syncRun())
        val waiting = queued("Waiting", DownloadStatus.WAITING_FOR_LOSSLESS, goingOff, syncId = syncRun())
        val alsoSynced = queued(
            "Waiting, also in Synced", DownloadStatus.WAITING_FOR_LOSSLESS, goingOff, synced, syncId = syncRun(),
        )
        val alsoKept = queued("Waiting, also kept", DownloadStatus.WAITING_FOR_LOSSLESS, goingOff, kept, syncId = syncRun())
        val tapped = queued(
            "Waiting, tapped", DownloadStatus.WAITING_FOR_LOSSLESS, goingOff, syncId = syncRun(), userRequested = true,
        )
        // In no playlist the sync downloads.
        queued(
            "Taken out of Synced", DownloadStatus.WAITING_FOR_LOSSLESS, synced, syncId = syncRun(), removedFromFirst = true,
        )
        queued("Stash Mix's", DownloadStatus.WAITING_FOR_LOSSLESS, stashMix, syncId = syncRun())
        queued("Hidden playlist's", DownloadStatus.WAITING_FOR_LOSSLESS, hiddenSynced, syncId = syncRun())
        queued("In no playlist", DownloadStatus.WAITING_FOR_LOSSLESS, syncId = syncRun())

        assertThat(dao.waitingForLosslessTracks(streamOnly = false).map { it.trackId })
            .containsExactly(waiting, alsoSynced, alsoKept, tapped)

        // The Sync tab's switch: sync off, then drop the sync's queued songs no synced playlist wants.
        db.playlistDao().setSyncEnabled(goingOff, false)
        assertThat(dao.cancelDownloadsWithNoEnabledPlaylist()).isEqualTo(1)
        assertThat(dao.getByTrackId(queuedSong)).isNull()
        // Its waits stay, and come back only while something else still asks for the song.
        assertThat(dao.waitingForLosslessTracks(streamOnly = false).map { it.trackId })
            .containsExactly(alsoSynced, alsoKept, tapped)
    }

    /**
     * Retry, on the Downloads or Failed downloads screen, is a tap (#532), on a sync's row too. The
     * retry runs in either mode, and in Stream-only mode only an ask brings a sync's waiting song
     * back: a retried sync row whose run waited for lossless sat there for good.
     */
    @Test fun `a retry marks a download as asked for, a sync's too`() = runTest {
        playlists()
        val cancelled = queued("Cancelled by mistake", DownloadStatus.SKIPPED)
        val failed = queued("Failed", DownloadStatus.FAILED, failureType = DownloadFailureType.NETWORK)
        val syncCancelled = queued("Sync's, cancelled", DownloadStatus.SKIPPED, synced, syncId = syncRun())
        val syncFailed = queued(
            "Sync's, failed", DownloadStatus.FAILED, synced, syncId = syncRun(), failureType = DownloadFailureType.NETWORK,
        )

        assertThat(dao.atomicallyClaimForRetry(rowOf(cancelled).id)).isEqualTo(1)
        assertThat(dao.atomicallyClaimForRetry(rowOf(syncCancelled).id)).isEqualTo(1)
        assertThat(dao.atomicallyClaimAllForRetry()).containsExactly(rowOf(failed).id, rowOf(syncFailed).id)
        // The sync rows' runs wait for lossless (relay pacing, strict FLAC), and come back.
        runWaitsForLossless(syncCancelled)
        runWaitsForLossless(syncFailed)
        assertThat(dao.waitingForLosslessTracks(streamOnly = true).map { it.trackId })
            .containsExactly(syncCancelled, syncFailed)

        assertThat(rowOf(cancelled).userRequested).isTrue()
        assertThat(rowOf(failed).userRequested).isTrue()
        assertThat(rowOf(syncCancelled).userRequested).isTrue()
        assertThat(rowOf(syncFailed).userRequested).isTrue()
        // The drain still takes no sync's row.
        assertThat(dao.pendingDiscoveryDownloads().map { it.trackId }).containsExactly(cancelled, failed)
    }

    /**
     * A sync's row keeps the playlist exception: a retried song a kept playlist downloads stays the
     * playlist's. Stream-only mode takes its wait while the playlist keeps the song, and turning the
     * playlist's Download off stops it there, as it stops the playlist's other songs (#474).
     */
    @Test fun `a retried sync row of a song a playlist downloads stays the playlist's`() = runTest {
        playlists()
        val keptSync = queued(
            "Kept, a sync's", DownloadStatus.FAILED, kept, syncId = syncRun(), failureType = DownloadFailureType.NETWORK,
        )

        assertThat(dao.atomicallyClaimForRetry(rowOf(keptSync).id)).isEqualTo(1)
        runWaitsForLossless(keptSync)

        assertThat(rowOf(keptSync).userRequested).isFalse()
        assertThat(dao.waitingForLosslessTracks(streamOnly = true).map { it.trackId }).containsExactly(keptSync)
        db.playlistDao().setKeepOffline(kept, false)
        assertThat(dao.waitingForLosslessTracks(streamOnly = true)).isEmpty()
    }

    /**
     * A Retry of a song a kept playlist or a followed mix downloads stays that playlist's (#474):
     * turning its Download off still stops it, like a song that never failed. Marked as a tap, it
     * kept downloading, and retrying, after the page said "Stopped".
     */
    @Test fun `a retried song a playlist downloads still stops when the playlist's Download goes off`() = runTest {
        playlists()
        val keptRetried = queued("Kept, retried", DownloadStatus.FAILED, kept, failureType = DownloadFailureType.NETWORK)
        val followedRetried = queued(
            "Followed, retried", DownloadStatus.FAILED, followedOn, failureType = DownloadFailureType.NETWORK,
        )
        val keptRetryAll = queued("Kept, Retry all", DownloadStatus.FAILED, kept, failureType = DownloadFailureType.NETWORK)
        val loose = queued("In no playlist", DownloadStatus.FAILED, failureType = DownloadFailureType.NETWORK)

        assertThat(dao.atomicallyClaimForRetry(rowOf(keptRetried).id)).isEqualTo(1)
        assertThat(dao.atomicallyClaimForRetry(rowOf(followedRetried).id)).isEqualTo(1)
        assertThat(dao.atomicallyClaimAllForRetry()).containsExactly(rowOf(keptRetryAll).id, rowOf(loose).id)

        // Asked for through their playlists, so the cleanup keeps them and the drain takes them...
        assertThat(dao.cancelLeftoverDiscoveryDownloads()).isEqualTo(0)
        assertThat(dao.pendingDiscoveryDownloads().map { it.trackId })
            .containsExactly(keptRetried, followedRetried, keptRetryAll, loose)
        // ...until the switch goes off. The song in no playlist is the user's own ask, and stays.
        db.playlistDao().setKeepOffline(kept, false)
        assertThat(dao.cancelWaitingForPlaylist(kept)).isEqualTo(2)
        db.playlistDao().setSyncEnabled(followedOn, false)
        assertThat(dao.cancelWaitingForPlaylist(followedOn)).isEqualTo(1)
        assertThat(dao.pendingDiscoveryDownloads().map { it.trackId }).containsExactly(loose)
    }

    @Test fun `the cleanup cancels leftovers and nothing the user asked for`() = runTest {
        playlists()
        val askedFor = listOf(
            queued("Tapped", DownloadStatus.PENDING, userRequested = true),
            queued("Kept", DownloadStatus.PENDING, kept),
            queued("Followed", DownloadStatus.PENDING, followedOn),
            queued("Kept, failed once", DownloadStatus.FAILED, kept, retryCount = 1),
            queued("Kept, out of retries", DownloadStatus.FAILED, kept, retryCount = 3),
            queued("Kept, waiting for lossless", DownloadStatus.WAITING_FOR_LOSSLESS, kept),
            // "Download N again" files no search query either; the user asked for it.
            queued("Library Health's", DownloadStatus.FAILED, searchQuery = "", userRequested = true),
        )
        val leftovers = listOf(
            queued("Old discovery", DownloadStatus.PENDING),
            queued("Verify's", DownloadStatus.PENDING, synced, searchQuery = ""),
            queued("Stash Mix's", DownloadStatus.PENDING, stashMix),
            queued("Followed, Download off", DownloadStatus.PENDING, followedOff),
            queued("Owner stopped sharing", DownloadStatus.PENDING, stoppedSharing),
            queued("Kept, but hidden", DownloadStatus.PENDING, hiddenKept),
            queued("Taken out of Kept", DownloadStatus.PENDING, kept, removedFromFirst = true),
            // The lossless retry would download it later (relay pacing defers whole batches).
            queued("Old playlist download, waiting", DownloadStatus.WAITING_FOR_LOSSLESS),
            // Verify's rows are never a tap, so even failed ones go, and stop hiding the song from a sync's requeue.
            queued("Verify's, failed", DownloadStatus.FAILED, synced, searchQuery = "", retryCount = 1),
        )
        val stays = listOf(
            // Before v0.9.110 a tap left no mark. A failed one may be the user's: nothing
            // retries it unasked, so it stays with its Retry button on Failed downloads.
            queued("Tapped before v0.9.110, failed", DownloadStatus.FAILED, retryCount = 1),
            queued(
                "Tapped before v0.9.110, no match", DownloadStatus.FAILED,
                failureType = DownloadFailureType.NO_MATCH, retryCount = 3,
            ),
            queued("Running", DownloadStatus.IN_PROGRESS), // a song running now finishes
            queued("Cancelled", DownloadStatus.SKIPPED),
            queued("Done", DownloadStatus.COMPLETED),
            queued("Sync's", DownloadStatus.PENDING, syncId = syncRun()),
            queued("Sync's, waiting for lossless", DownloadStatus.WAITING_FOR_LOSSLESS, syncId = syncRun()),
        )

        assertThat(dao.cancelLeftoverDiscoveryDownloads()).isEqualTo(leftovers.size)

        val left = (askedFor + leftovers + stays).filter { dao.getByTrackId(it) != null }
        assertThat(left).containsExactlyElementsIn(askedFor + stays)
        assertThat(dao.cancelLeftoverDiscoveryDownloads()).isEqualTo(0)
    }

    @Test fun `a retried download survives the leftover cleanup`() = runTest {
        val cancelled = queued("Cancelled by mistake", DownloadStatus.SKIPPED)
        dao.atomicallyClaimForRetry(rowOf(cancelled).id)

        assertThat(dao.cancelLeftoverDiscoveryDownloads()).isEqualTo(0)
        assertThat(dao.pendingDiscoveryDownloads().map { it.trackId }).containsExactly(cancelled)
    }

    /** queueDownload reuses a song's old row; one write, so the cleanup can't land between two. */
    @Test fun `a tap requeues an old row as asked for in one write, or reports it gone`() = runTest {
        val song = queued("Failed long ago", DownloadStatus.FAILED, failureType = DownloadFailureType.NETWORK)
        val id = rowOf(song).id

        assertThat(dao.requeueForTap(id)).isEqualTo(1)

        val row = rowOf(song)
        assertThat(row.status).isEqualTo(DownloadStatus.PENDING)
        assertThat(row.userRequested).isTrue()
        assertThat(row.failureType).isEqualTo(DownloadFailureType.NONE)
        assertThat(dao.cancelLeftoverDiscoveryDownloads()).isEqualTo(0)

        dao.deleteByTrackId(song)
        assertThat(dao.requeueForTap(id)).isEqualTo(0)
    }

    /**
     * A search download with no lossless source waits on a row it found: that row is now the user's
     * ask, a sync's too, as a tap on the song's own Download marks it ([DownloadQueueDao.requeueForTap]).
     * The row it finds is usually one a sync deferred; left unmarked, it never came back in Stream-only.
     */
    @Test fun `a tap that waits for lossless counts as asked for, a sync's row too`() = runTest {
        val oldRow = queued("Old leftover", DownloadStatus.COMPLETED)
        val syncRow = queued("Sync's", DownloadStatus.FAILED, syncId = syncRun(), failureType = DownloadFailureType.NETWORK)

        dao.deferForTap(rowOf(oldRow).id)
        dao.deferForTap(rowOf(syncRow).id)

        assertThat(dao.waitingForLosslessTracks(streamOnly = true).map { it.trackId }).containsExactly(oldRow, syncRow)
        assertThat(dao.waitingForLosslessTracks(streamOnly = false).map { it.trackId }).containsExactly(oldRow, syncRow)
        assertThat(rowOf(oldRow).status).isEqualTo(DownloadStatus.WAITING_FOR_LOSSLESS)
        assertThat(rowOf(oldRow).userRequested).isTrue()
        assertThat(rowOf(syncRow).status).isEqualTo(DownloadStatus.WAITING_FOR_LOSSLESS)
        assertThat(rowOf(syncRow).userRequested).isTrue()
        assertThat(rowOf(syncRow).failureType).isEqualTo(DownloadFailureType.NONE)
        assertThat(dao.cancelLeftoverDiscoveryDownloads()).isEqualTo(0)
    }

    private suspend fun rowOf(trackId: Long): DownloadQueueEntity = dao.getByTrackId(trackId)!!

    /** The song's run claims its row, finds no lossless source and leaves it waiting (TrackDownloaderImpl). */
    private suspend fun runWaitsForLossless(trackId: Long) {
        val id = rowOf(trackId).id
        assertThat(dao.claimForDownload(id)).isEqualTo(1)
        assertThat(dao.deferIfInProgress(id)).isEqualTo(1)
    }

    private suspend fun syncRun(): Long = db.syncHistoryDao().insert(SyncHistoryEntity())

    /** [follow]: a followed mix's status. follow() writes its row with the playlist, in one transaction. */
    private suspend fun playlist(
        name: String,
        sourceId: String,
        keepOffline: Boolean = false,
        syncEnabled: Boolean = false,
        isActive: Boolean = true,
        type: PlaylistType = PlaylistType.CUSTOM,
        follow: String? = null,
    ): Long = db.playlistDao().insert(
        PlaylistEntity(
            name = name, source = MusicSource.BOTH, sourceId = sourceId, type = type,
            keepOffline = keepOffline, syncEnabled = syncEnabled, isActive = isActive,
        )
    ).also { id ->
        if (follow != null) {
            db.sharedMixDao().insert(
                SharedMixEntity(
                    playlistId = id, shareId = sourceId.removePrefix("share:"), role = SharedMixEntity.ROLE_FOLLOWER,
                    name = name, status = follow,
                )
            )
        }
    }

    /**
     * A song in [playlistIds] with one download row, returning the song's id.
     * [removedFromFirst] soft-removes it from the first playlist. [searchQuery]
     * defaults to the "artist - title" every inserter but Verify writes.
     */
    private suspend fun queued(
        title: String,
        status: DownloadStatus,
        vararg playlistIds: Long,
        userRequested: Boolean = false,
        syncId: Long? = null,
        retryCount: Int = 0,
        searchQuery: String = "A - $title",
        failureType: DownloadFailureType = DownloadFailureType.NONE,
        removedFromFirst: Boolean = false,
    ): Long {
        val id = db.trackDao().insert(
            TrackEntity(
                title = title, artist = "A", durationMs = 1000L, source = MusicSource.SPOTIFY,
                canonicalTitle = title.lowercase(), canonicalArtist = "a",
            )
        )
        playlistIds.forEachIndexed { i, pid ->
            db.playlistDao().insertCrossRef(
                PlaylistTrackCrossRef(
                    playlistId = pid, trackId = id, position = 0,
                    removedAt = if (removedFromFirst && i == 0) Instant.now() else null,
                )
            )
        }
        dao.insert(
            DownloadQueueEntity(
                trackId = id, syncId = syncId, status = status, searchQuery = searchQuery,
                retryCount = retryCount, failureType = failureType, userRequested = userRequested,
            )
        )
        return id
    }
}
