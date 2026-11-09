package com.stash.core.data.community

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.common.TrackCanonical
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.ListeningEventEntity
import com.stash.core.data.db.entity.TrackAliasEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.share.SharedTrack
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A post's songs found in this phone's listening history: by YouTube id, Spotify id, or title and artist; since it went up. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CommunityListenHistoryTest {
    private lateinit var db: StashDatabase
    private lateinit var history: CommunityListenHistory

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries().build()
        history = CommunityListenHistory(db.trackDao(), db.listeningEventDao())
    }

    @After fun tearDown() = db.close()

    /** A library row, its canonical words as the app writes them. */
    private suspend fun row(title: String, artist: String, youtubeId: String? = null, spotifyUri: String? = null): Long =
        db.trackDao().insert(
            TrackEntity(
                title = title, artist = artist, youtubeId = youtubeId, spotifyUri = spotifyUri,
                canonicalTitle = TrackCanonical.title(title), canonicalArtist = TrackCanonical.artist(artist),
            ),
        )

    /** A listen this phone recorded: it started at [at] and counted. */
    private suspend fun listen(trackId: Long, at: Long, origin: String? = null) {
        db.listeningEventDao().insert(ListeningEventEntity(trackId = trackId, startedAt = at, completedAt = at + 120_000, originDevice = origin))
    }

    private val garden = SharedTrack("garden", "Death Plus", youtubeId = "9Vz", spotifyId = "4sp")

    @Test fun `found by YouTube id, even where the row's words differ`() = runBlocking {
        listen(row("Gardens of Stone", "DP", youtubeId = "9Vz"), at = POSTED + HOUR)
        assertThat(history.heardSince(listOf(garden), POSTED)).isTrue()
    }

    @Test fun `found by Spotify id`() = runBlocking {
        listen(row("Jardin", "Mort Plus", spotifyUri = "spotify:track:4sp"), at = POSTED + HOUR)
        assertThat(history.heardSince(listOf(garden), POSTED)).isTrue()
    }

    @Test fun `found by title and artist, a library row of the song with no ids or another upload's`() = runBlocking {
        listen(row("Garden", "Death Plus", youtubeId = "otherUpload"), at = POSTED + HOUR)
        assertThat(history.heardSince(listOf(garden), POSTED)).isTrue()
        // A list's song post carries no ids at all.
        assertThat(history.heardSince(listOf(SharedTrack("garden", "Death Plus")), POSTED)).isTrue()
    }

    @Test fun `found where a duplicate merge moved the song's YouTube id`() = runBlocking {
        val survivor = row("Something else entirely", "Nobody")
        db.duplicateDao().putAliases(listOf(TrackAliasEntity(TrackAliasEntity.KIND_YOUTUBE_ID, "9Vz", survivor)))
        listen(survivor, at = POSTED + HOUR)
        assertThat(history.heardSince(listOf(garden), POSTED)).isTrue()
    }

    @Test fun `a live version or another artist's song doesn't count`() = runBlocking {
        listen(row("Garden (Live)", "Death Plus"), at = POSTED + HOUR)
        listen(row("Garden", "Someone Else"), at = POSTED + HOUR)
        assertThat(history.heardSince(listOf(SharedTrack("garden", "Death Plus")), POSTED)).isFalse()
    }

    @Test fun `a listen from before the post went up, or another device's, doesn't count`() = runBlocking {
        val id = row("garden", "Death Plus", youtubeId = "9Vz")
        listen(id, at = POSTED - 1)
        listen(id, at = POSTED + HOUR, origin = "d_web00000000000")
        assertThat(history.heardSince(listOf(garden), POSTED)).isFalse()
        listen(id, at = POSTED)
        assertThat(history.heardSince(listOf(garden), POSTED)).isTrue()
    }

    @Test fun `any of a playlist's songs counts`() = runBlocking {
        listen(row("T2", "B", youtubeId = "y2"), at = POSTED + HOUR)
        val songs = listOf(SharedTrack("T1", "A", youtubeId = "y1"), SharedTrack("T2", "B", youtubeId = "y2"))
        assertThat(history.heardSince(songs, POSTED)).isTrue()
        assertThat(history.heardSince(songs.take(1), POSTED)).isFalse()
    }

    @Test fun `a song with no row here is not heard, and nothing is added`() = runBlocking {
        row("Unrelated", "Someone", youtubeId = "zz")
        assertThat(history.heardSince(listOf(garden), POSTED)).isFalse()
        assertThat(history.heardSince(emptyList(), POSTED)).isFalse()
        val rows = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM tracks").use { it.moveToFirst(); it.getInt(0) }
        assertThat(rows).isEqualTo(1)
    }

    private companion object {
        const val POSTED = 1_762_000_000_000L
        const val HOUR = 3_600_000L
    }
}
