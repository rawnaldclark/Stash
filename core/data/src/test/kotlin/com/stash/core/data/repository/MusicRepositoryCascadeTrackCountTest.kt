package com.stash.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.sync.TrackMatcher
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Deleting a song from a playlist must update the count the Library card shows. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MusicRepositoryCascadeTrackCountTest {

    private lateinit var db: StashDatabase
    private var playlistId = 0L

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.trackDao().insert(track(1L))
        db.trackDao().insert(track(2L))
        playlistId = db.playlistDao().insert(playlist("Road trip", PlaylistType.CUSTOM, "trip").copy(trackCount = 2))
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = playlistId, trackId = 1L, position = 0))
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = playlistId, trackId = 2L, position = 1))
    }

    @After fun tearDown() = db.close()

    @Test fun `removing a song kept by Liked Songs updates the count`() = runTest {
        val liked = db.playlistDao().insert(playlist("Liked Songs", PlaylistType.LIKED_SONGS, "liked"))
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = liked, trackId = 2L, position = 0))

        val summary = repo().removeTrackFromPlaylistAndMaybeDelete(2L, playlistId, alsoBlacklist = false)

        assertEquals(1, summary.keptProtected)
        assertEquals(1, db.playlistDao().getById(playlistId)?.trackCount)
    }

    @Test fun `deleting a song nothing else holds updates the count`() = runTest {
        val summary = repo().removeTrackFromPlaylistAndMaybeDelete(2L, playlistId, alsoBlacklist = false)

        assertEquals(1, summary.deleted)
        assertEquals(1, db.playlistDao().getById(playlistId)?.trackCount)
    }

    @Test fun `blocking a song also updates every other playlist that held it`() = runTest {
        val gym = db.playlistDao().insert(playlist("Gym", PlaylistType.CUSTOM, "gym").copy(trackCount = 2))
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = gym, trackId = 1L, position = 0))
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = gym, trackId = 2L, position = 1))
        val guard = BlocklistGuard(
            database = db,
            blocklistDao = db.trackBlocklistDao(),
            trackDao = db.trackDao(),
            playlistDao = db.playlistDao(),
            downloadQueueDao = db.downloadQueueDao(),
            fileDeleter = mockk(relaxed = true),
            matcher = TrackMatcher(),
        )

        repo(guard).removeTrackFromPlaylistAndMaybeDelete(2L, playlistId, alsoBlacklist = true)

        assertEquals(1, db.playlistDao().getById(playlistId)?.trackCount)
        assertEquals(1, db.playlistDao().getById(gym)?.trackCount)
    }

    private fun track(id: Long) = TrackEntity(
        id = id, title = "T$id", artist = "A$id", source = MusicSource.SPOTIFY,
        canonicalTitle = "t$id", canonicalArtist = "a$id",
    )

    private fun playlist(name: String, type: PlaylistType, sourceId: String) = PlaylistEntity(
        name = name, source = MusicSource.BOTH, sourceId = sourceId, type = type,
    )

    private fun repo(guard: BlocklistGuard = mockk(relaxed = true)) = MusicRepositoryImpl(
        context = mockk(relaxed = true),
        trackDao = db.trackDao(),
        playlistDao = db.playlistDao(),
        syncHistoryDao = mockk(relaxed = true),
        downloadQueueDao = mockk(relaxed = true),
        discoveryQueueDao = mockk(relaxed = true),
        blocklistGuard = guard,
        trackMatcher = mockk(relaxed = true),
        stashMixRecipeDao = mockk(relaxed = true),
        downloadNetworkPreference = mockk(relaxed = true),
        streamingPreference = mockk(relaxed = true),
        localFileOps = mockk(relaxed = true),
        syncPreferencesManager = mockk(relaxed = true),
        singleTrackDownloadEnqueuer = mockk(relaxed = true),
        lastFmRecommendationSource = mockk(relaxed = true),
        sharedMixDao = db.sharedMixDao(),
    )
}
