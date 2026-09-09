package com.stash.data.download.files

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.dao.TrackExistenceRef
import com.stash.core.data.prefs.LibraryLayout
import com.stash.core.data.prefs.StoragePreference
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Internal-storage coverage of the reorganize pass (#198/#104). The SAF
 * branch needs a real provider and is exercised on-device; everything
 * asserted here is layout/plan behavior shared by both movers.
 */
class ReorganizeLibraryCoordinatorTest {

    @get:Rule val tmp = TemporaryFolder()

    private val context = mockk<Context>(relaxed = true)
    private val trackDao = mockk<TrackDao>(relaxed = true)
    private val storagePreference = mockk<StoragePreference>()
    private val fileOrganizer = mockk<FileOrganizer>()
    private val gate = LibraryRewriteGate()

    private lateinit var musicRoot: File
    private lateinit var coordinator: ReorganizeLibraryCoordinator

    @Before
    fun setUp() {
        musicRoot = tmp.newFolder("music")
        every { storagePreference.externalTreeUri } returns flowOf(null)
        every { fileOrganizer.internalMusicRoot() } returns musicRoot
        coEvery { fileOrganizer.currentLayout() } returns LibraryLayout.SINGLE_FOLDER
        coordinator =
            ReorganizeLibraryCoordinator(context, trackDao, storagePreference, fileOrganizer, gate)
    }

    private fun seed(relativePath: String, content: String): File =
        File(musicRoot, relativePath).apply {
            parentFile!!.mkdirs()
            writeText(content)
        }

    private fun ref(id: Long, file: File, artist: String, album: String, title: String) =
        TrackExistenceRef(id, artist, album, title, file.absolutePath)

    private suspend fun awaitDone(): ReorganizeLibraryState.Done =
        withTimeout(10_000) {
            coordinator.state.first { it is ReorganizeLibraryState.Done }
        } as ReorganizeLibraryState.Done

    /**
     * Two rows, one target name — SINGLE_FOLDER drops the album segment, so
     * a studio and a live cut of the same song collide. The mover overwrites
     * whatever occupies the target, so without a plan-level guard the second
     * claimant deletes the first one's audio and the first row is left
     * pointing at the second one's file.
     */
    @Test fun `a second row claiming an occupied target name is skipped, not overwritten`() = runBlocking {
        val studio = seed("nirvana/nevermind/something-in-the-way.m4a", "STUDIO")
        val live = seed("nirvana/unplugged/something-in-the-way.m4a", "LIVE")
        coEvery { trackDao.getDownloadedTrackRefs() } returns listOf(
            ref(1L, studio, "Nirvana", "Nevermind", "Something In The Way"),
            ref(2L, live, "Nirvana", "Unplugged", "Something In The Way"),
        )

        coordinator.start()
        val done = awaitDone()

        val target = File(musicRoot, "nirvana-something-in-the-way.m4a")
        assertThat(target.readText()).isEqualTo("STUDIO")
        assertThat(live.exists()).isTrue()
        assertThat(live.readText()).isEqualTo("LIVE")
        coVerify(exactly = 1) { trackDao.healFilePath(1L, target.absolutePath) }
        coVerify(exactly = 0) { trackDao.healFilePath(2L, any()) }
        assertThat(done.moved).isEqualTo(1)
        assertThat(done.skipped).isEqualTo(1)
        assertThat(done.failed).isEqualTo(0)
    }

    /** A clash never counts as work the button promises to do. */
    @Test fun `misplaced count excludes rows that would lose a name clash`() = runBlocking {
        val studio = seed("nirvana/nevermind/something-in-the-way.m4a", "STUDIO")
        val live = seed("nirvana/unplugged/something-in-the-way.m4a", "LIVE")
        coEvery { trackDao.getDownloadedTrackRefs() } returns listOf(
            ref(1L, studio, "Nirvana", "Nevermind", "Something In The Way"),
            ref(2L, live, "Nirvana", "Unplugged", "Something In The Way"),
        )

        assertThat(coordinator.countMisplacedTracks()).isEqualTo(1)
    }

    /** The row already sitting on the target keeps it; the other one yields. */
    @Test fun `a row already in place wins the target name over a later claimant`() = runBlocking {
        val inPlace = seed("nirvana-something-in-the-way.m4a", "IN_PLACE")
        val other = seed("nirvana/unplugged/something-in-the-way.m4a", "OTHER")
        coEvery { trackDao.getDownloadedTrackRefs() } returns listOf(
            ref(1L, inPlace, "Nirvana", "Nevermind", "Something In The Way"),
            ref(2L, other, "Nirvana", "Unplugged", "Something In The Way"),
        )

        coordinator.start()
        val done = awaitDone()

        assertThat(inPlace.readText()).isEqualTo("IN_PLACE")
        assertThat(other.exists()).isTrue()
        coVerify(exactly = 0) { trackDao.healFilePath(any(), any()) }
        assertThat(done.moved).isEqualTo(0)
        assertThat(done.skipped).isEqualTo(2)
    }

    /** One unmovable track is counted and does not stop the rest of the pass. */
    @Test fun `a missing source file fails only its own track`() = runBlocking {
        val present = seed("a/x/one.m4a", "ONE")
        val vanished = File(musicRoot, "b/y/two.m4a")
        coEvery { trackDao.getDownloadedTrackRefs() } returns listOf(
            ref(1L, vanished, "Artist B", "Y", "Two"),
            ref(2L, present, "Artist A", "X", "One"),
        )

        coordinator.start()
        val done = awaitDone()

        assertThat(done.failed).isEqualTo(1)
        assertThat(done.moved).isEqualTo(1)
        assertThat(File(musicRoot, "artist-a-one.m4a").readText()).isEqualTo("ONE")
    }

    /**
     * Cancel mid-pass must stop the pass, not merely flip the state: the
     * loop re-throws the CancellationException it would otherwise swallow,
     * so the run ends at Idle instead of overwriting it with a Done that
     * reports a library-wide sweep the user cancelled.
     */
    @Test fun `cancel mid-pass ends at idle instead of reporting done`() = runBlocking {
        val first = seed("a/x/one.m4a", "ONE")
        val second = seed("b/y/two.m4a", "TWO")
        coEvery { trackDao.getDownloadedTrackRefs() } returns listOf(
            ref(1L, first, "Artist A", "X", "One"),
            ref(2L, second, "Artist B", "Y", "Two"),
        )
        val reachedFirstHeal = CountDownLatch(1)
        val releaseFirstHeal = CountDownLatch(1)
        var heals = 0
        coEvery { trackDao.healFilePath(any(), any()) } coAnswers {
            if (heals++ == 0) {
                reachedFirstHeal.countDown()
                releaseFirstHeal.await(10, TimeUnit.SECONDS)
            } else {
                // A real suspension point, so the cancelled job actually
                // observes the cancellation while on the second track.
                delay(10_000)
            }
        }

        coordinator.start()
        assertThat(reachedFirstHeal.await(10, TimeUnit.SECONDS)).isTrue()
        coordinator.cancel()
        releaseFirstHeal.countDown()

        delay(500)
        assertThat(coordinator.state.value).isEqualTo(ReorganizeLibraryState.Idle)
    }

    /**
     * A name clash can only destroy a file when both rows land in the SAME
     * storage root. An internal track and a SAF-tree track that resolve to
     * one layout-relative name are written to different roots and cannot
     * overwrite each other, so neither may be skipped.
     */
    @Test fun `an internal and an external row may share one target name`() = runBlocking {
        val studio = seed("nirvana/nevermind/something-in-the-way.m4a", "STUDIO")
        coEvery { trackDao.getDownloadedTrackRefs() } returns listOf(
            ref(1L, studio, "Nirvana", "Nevermind", "Something In The Way"),
            TrackExistenceRef(
                2L, "Nirvana", "Unplugged", "Something In The Way",
                "content://com.android.externalstorage.documents/document/1A2B%3AMusic%2Fold.m4a",
            ),
        )

        assertThat(coordinator.countMisplacedTracks()).isEqualTo(2)
    }

    /**
     * Both bulk passes rewrite `file_path` for every downloaded track. Run at
     * once, whichever [TrackDao.healFilePath] lands last wins and the other
     * pass's file is orphaned, so the second to start is refused — in EITHER
     * order, which is why the exclusion lives in a shared gate rather than in
     * one coordinator looking at the other.
     */
    @Test fun `refuses to start while another pass holds the gate`() = runBlocking {
        val file = seed("a/x/one.m4a", "ONE")
        coEvery { trackDao.getDownloadedTrackRefs() } returns listOf(
            ref(1L, file, "Artist A", "X", "One"),
        )
        assertThat(gate.tryAcquire("library move")).isTrue()

        coordinator.start()
        delay(300)

        assertThat(coordinator.state.value).isInstanceOf(ReorganizeLibraryState.Error::class.java)
        assertThat(file.readText()).isEqualTo("ONE")
        assertThat(File(musicRoot, "artist-a-one.m4a").exists()).isFalse()
        coVerify(exactly = 0) { trackDao.healFilePath(any(), any()) }
    }

    /** A finished pass hands the gate back, so the next one can run. */
    @Test fun `the gate is released when the pass finishes`() = runBlocking {
        val file = seed("a/x/one.m4a", "ONE")
        coEvery { trackDao.getDownloadedTrackRefs() } returns listOf(
            ref(1L, file, "Artist A", "X", "One"),
        )

        coordinator.start()
        awaitDone()

        assertThat(gate.heldBy).isNull()
    }

    /** Cancel hands the gate back too, or the button stays dead forever. */
    @Test fun `the gate is released when the pass is cancelled`() = runBlocking {
        val first = seed("a/x/one.m4a", "ONE")
        coEvery { trackDao.getDownloadedTrackRefs() } returns listOf(
            ref(1L, first, "Artist A", "X", "One"),
        )
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        coEvery { trackDao.healFilePath(any(), any()) } coAnswers {
            reached.countDown()
            release.await(10, TimeUnit.SECONDS)
        }

        coordinator.start()
        assertThat(reached.await(10, TimeUnit.SECONDS)).isTrue()
        coordinator.cancel()
        release.countDown()
        delay(500)

        assertThat(gate.heldBy).isNull()
    }
}
