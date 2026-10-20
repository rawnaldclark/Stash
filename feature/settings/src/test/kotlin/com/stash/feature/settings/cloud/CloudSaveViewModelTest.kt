package com.stash.feature.settings.cloud

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.CloudProvider
import com.stash.core.model.MusicSource
import com.stash.core.model.Track
import com.stash.core.ui.components.CloudSaveTarget
import com.stash.core.ui.components.canSaveToCloud
import com.stash.data.cloud.CloudAccount
import com.stash.data.cloud.CloudLibrary
import com.stash.data.cloud.upload.CloudUploads
import com.stash.data.cloud.upload.SaveRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The "Save to Google Drive" menu entries: when they show, and what a tap on each queues and says. */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudSaveViewModelTest {
    private val accounts = MutableStateFlow<List<CloudAccount>>(emptyList())
    private val cloud: CloudLibrary = mockk { every { this@mockk.accounts } returns this@CloudSaveViewModelTest.accounts }
    private val uploads: CloudUploads = mockk { every { available } returns true }
    private val music: MusicRepository = mockk()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = CloudSaveViewModel(uploads, music, cloud)
    private fun drive() = CloudAccount(1, CloudProvider.GOOGLE_DRIVE, "Ron", "ron@example.com", 0)
    private fun track(id: Long, downloaded: Boolean = true, source: MusicSource = MusicSource.SPOTIFY) =
        Track(id = id, title = "t$id", artist = "a", isDownloaded = downloaded, filePath = if (downloaded) "/m/$id.opus" else null, source = source)

    @Test fun `the entries show only while Google Drive is connected`() {
        val vm = vm()
        assertThat(vm.connected.value).isFalse()
        accounts.value = listOf(CloudAccount(2, CloudProvider.DROPBOX, "Ron", null, 0))
        assertThat(vm.connected.value).isFalse()
        accounts.value = listOf(drive())
        assertThat(vm.connected.value).isTrue()
    }

    @Test fun `hidden in a build without Google Drive`() {
        every { uploads.available } returns false
        accounts.value = listOf(drive())
        assertThat(vm().connected.value).isFalse()
    }

    @Test fun `saving an album sends every song and says how many weren't downloaded`() = runTest {
        every { music.getTracksByAlbum("Blue", "Joni") } returns flowOf(listOf(track(1), track(2), track(3, downloaded = false)))
        coEvery { uploads.save(listOf(1L, 2L, 3L), null) } returns SaveRequest(queued = 2, alreadyQueued = 0, notDownloaded = 1)
        val vm = vm()
        val messages = mutableListOf<String>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.messages.collect { messages += it } }

        vm.save(CloudSaveTarget.Album("Blue", "Joni"))

        assertThat(messages).containsExactly("Saving 2 songs to Google Drive. 1 song isn't downloaded yet.")
        job.cancel()
    }

    @Test fun `saving a playlist sends its songs`() = runTest {
        every { music.getTracksByPlaylist(9) } returns flowOf(listOf(track(4), track(5, downloaded = false), track(6, downloaded = false), track(7, downloaded = false)))
        coEvery { uploads.save(any(), null) } returns SaveRequest(1, 0, 3)
        vm().save(CloudSaveTarget.Playlist(9))
        coVerify { uploads.save(listOf(4L, 5L, 6L, 7L), null) }
        assertThat(CloudUploads.message(SaveRequest(1, 0, 3))).isEqualTo("Saving 1 song to Google Drive. 3 songs aren't downloaded yet.")
    }

    @Test fun `a failed save says so instead of crashing`() = runTest {
        coEvery { uploads.save(any(), null) } throws IllegalStateException("db")
        val vm = vm()
        val messages = mutableListOf<String>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.messages.collect { messages += it } }
        vm.save(CloudSaveTarget.Songs(listOf(1)))
        assertThat(messages).containsExactly("Couldn't save to Google Drive. Try again.")
        job.cancel()
    }

    @Test fun `a song's menu offers it only for a download that isn't a cloud song`() {
        assertThat(canSaveToCloud(track(1))).isTrue()
        assertThat(canSaveToCloud(track(1, downloaded = false))).isFalse()
        assertThat(canSaveToCloud(track(1, source = MusicSource.CLOUD))).isFalse()
    }
}
