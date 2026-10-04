package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.TrackEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [TrackDao.completeSwap] is the one write that finishes a wrong-match swap
 * (#531). The row before the swap here is the typical bad case: a 24-bit FLAC
 * of the wrong recording, already loudness-measured and flagged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TrackDaoCompleteSwapTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: TrackDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = db.trackDao()
    }

    @After fun tearDown() { db.close() }

    private suspend fun insertFlaggedFlac(id: Long = 1L): Long = dao.insert(
        TrackEntity(
            id = id,
            title = "Lacrymosa",
            artist = "Evanescence",
            album = "Synthesis",
            filePath = "/music/evanescence/synthesis/lacrymosa.flac",
            fileFormat = "flac",
            qualityKbps = 1411,
            isDownloaded = true,
            bitsPerSample = 24,
            sampleRateHz = 96_000,
            loudnessLufs = -9.5f,
            truePeakDbfs = -0.3f,
            loudnessMeasuredAt = 1_000L,
            matchFlagged = true,
            canonicalTitle = "lacrymosa",
            canonicalArtist = "evanescence",
        ),
    )

    private suspend fun swapToOpus(id: Long, youtubeId: String = "synthesis-video") = dao.completeSwap(
        trackId = id,
        youtubeId = youtubeId,
        filePath = "/music/evanescence/synthesis/lacrymosa.opus",
        fileSizeBytes = 4_000_000L,
        fileFormat = "opus",
        qualityKbps = 160,
        sampleRateHz = 48_000,
        bitsPerSample = null,
        durationMs = 231_000L,
        metadataEmbeddedAt = 2_000L,
        pickedAt = 2_000L,
        downloadedAt = 2_000L,
    )

    @Test
    fun `a lossy swap over a 24-bit FLAC leaves no bit depth behind`() = runTest {
        val id = insertFlaggedFlac()

        val updated = swapToOpus(id)

        assertEquals(1, updated)
        val row = dao.getById(id)!!
        assertNull("opus has no bit depth; keeping the FLAC's 24 showed 'OPUS · 24-bit'", row.bitsPerSample)
        assertEquals(48_000, row.sampleRateHz)
        assertEquals("/music/evanescence/synthesis/lacrymosa.opus", row.filePath)
        assertEquals(4_000_000L, row.fileSizeBytes)
        assertTrue(row.isDownloaded)
    }

    @Test
    fun `a swap clears the flag, records the pick and drops the old loudness`() = runTest {
        val id = insertFlaggedFlac()

        swapToOpus(id)

        val row = dao.getById(id)!!
        assertFalse("the row leaves Failed Matches only once the swap is done", row.matchFlagged)
        assertEquals(2_000L, row.matchPickedAt)
        assertNull(row.loudnessLufs)
        assertNull(row.truePeakDbfs)
        assertNull("so the loudness pass measures the new recording", row.loudnessMeasuredAt)
    }

    @Test
    fun `a song the user picked is not an automatic FLAC upgrade candidate`() = runTest {
        val picked = insertFlaggedFlac(id = 1L)
        swapToOpus(picked)
        dao.setFormatAndQuality(picked, "opus", 160)
        val other = dao.insert(
            TrackEntity(
                id = 2L,
                title = "My Immortal",
                artist = "Evanescence",
                filePath = "/music/evanescence/fallen/my-immortal.opus",
                fileFormat = "opus",
                isDownloaded = true,
                canonicalTitle = "my immortal",
                canonicalArtist = "evanescence",
            ),
        )

        // The upgrade would re-run the lossless lookup that most likely chose
        // the wrong recording, then write that FLAC over the user's pick.
        assertEquals(listOf(other), dao.getLosslessUpgradeCandidates(retryBefore = 0L).map { it.id })
    }

    @Test
    fun `completing a swap on a deleted track updates nothing`() = runTest {
        assertEquals(0, swapToOpus(42L))
    }

    /**
     * #531 review: the video id used to be written before the save, so an
     * error or the app dying mid-save left the row on the new video with its
     * old file. It is now written in this same write, with format, quality,
     * length and the tag stamp: a crash can't leave 'flac' on opus audio.
     */
    @Test
    fun `a swap records the new video, format, quality, length and tags in one write`() = runTest {
        val id = insertFlaggedFlac()

        swapToOpus(id)

        val row = dao.getById(id)!!
        assertEquals("synthesis-video", row.youtubeId)
        assertEquals("opus", row.fileFormat)
        assertEquals(160, row.qualityKbps)
        assertEquals(231_000L, row.durationMs)
        assertEquals(2_000L, row.metadataEmbeddedAt)
    }

    @Test
    fun `a swap whose video another track took meanwhile writes nothing`() = runTest {
        val id = insertFlaggedFlac()
        dao.insert(
            TrackEntity(
                id = 8L,
                title = "Lacrymosa",
                artist = "Evanescence",
                album = "The Open Door",
                youtubeId = "synthesis-video",
                canonicalTitle = "lacrymosa",
                canonicalArtist = "evanescence",
            ),
        )

        assertEquals(0, swapToOpus(id, youtubeId = "synthesis-video"))
        val row = dao.getById(id)!!
        assertEquals("/music/evanescence/synthesis/lacrymosa.flac", row.filePath)
        assertTrue("still flagged: the swap didn't happen", row.matchFlagged)
        assertNull(row.matchPickedAt)
    }

    // -- #531 review: flagging a swapped song again ----------------------------

    @Test
    fun `flagging a swapped song again forgets the pick`() = runTest {
        val id = insertFlaggedFlac()
        swapToOpus(id)

        dao.updateMatchFlagged(id, true)

        val row = dao.getById(id)!!
        assertTrue(row.matchFlagged)
        // The user is saying the pick is wrong too: resync must treat its
        // video as the wrong one again instead of offering it back.
        assertNull(row.matchPickedAt)
    }

    @Test
    fun `unflagging keeps the pick`() = runTest {
        val id = insertFlaggedFlac()
        swapToOpus(id)

        dao.updateMatchFlagged(id, false)

        assertEquals(2_000L, dao.getById(id)!!.matchPickedAt)
    }

    @Test
    fun `clearing the pick leaves the rest of the row alone`() = runTest {
        val id = insertFlaggedFlac()
        swapToOpus(id)

        dao.clearMatchPick(id)

        val row = dao.getById(id)!!
        assertNull(row.matchPickedAt)
        assertEquals("synthesis-video", row.youtubeId)
        assertTrue(row.isDownloaded)
    }
}
