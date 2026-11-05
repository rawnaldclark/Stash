package com.stash.feature.nowplaying.ui

import com.stash.core.data.db.dao.CloudDao
import com.stash.core.data.db.entity.CloudAccountEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.media.output.HiResOutputController
import com.stash.core.model.MusicSource
import com.stash.core.model.Track
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Source line of the signal path names a cloud song's account, however the song is playing. */
class SignalPathCloudSourceTest {
    private val cloud: CloudDao = mockk(relaxed = true)
    private val vm = SignalPathViewModel(mockk<HiResOutputController>(relaxed = true), cloud)

    private val samsAddress = "stash-cloud://gdrive/2/1AbCdEf?v=md5"

    private fun givenSam(email: String? = "sam@example.com") {
        coEvery { cloud.getAccount(2) } returns CloudAccountEntity(id = 2, provider = "gdrive", remoteId = "p", displayName = "Sam", email = email, connectedAt = 1)
    }

    @Test fun `a streaming cloud song names its provider and account`() = runTest {
        givenSam()
        val source = vm.cloudSource(Track(id = 5, title = "t", artist = "a", source = MusicSource.CLOUD, cloudUri = samsAddress))
        assertEquals("Google Drive · sam@example.com", source)
    }

    @Test fun `a downloaded cloud song plays its file, and still names the account from its row`() = runTest {
        givenSam()
        coEvery { cloud.getTrack(5) } returns TrackEntity(id = 5, title = "t", artist = "a", source = MusicSource.CLOUD, isDownloaded = true, cloudUri = samsAddress)
        val source = vm.cloudSource(Track(id = 5, title = "t", artist = "a", source = MusicSource.CLOUD, isDownloaded = true))
        assertEquals("Google Drive · sam@example.com", source)
    }

    @Test fun `an account with no email is named as it signed in`() = runTest {
        givenSam(email = null)
        val source = vm.cloudSource(Track(id = 5, title = "t", artist = "a", cloudUri = samsAddress))
        assertEquals("Google Drive · Sam", source)
    }

    @Test fun `an account that's gone still names the provider`() = runTest {
        coEvery { cloud.getAccount(2) } returns null
        val source = vm.cloudSource(Track(id = 5, title = "t", artist = "a", cloudUri = samsAddress))
        assertEquals("Google Drive", source)
    }

    @Test fun `any other song has no cloud source`() = runTest {
        coEvery { cloud.getTrack(7) } returns TrackEntity(id = 7, title = "t", artist = "a", source = MusicSource.SPOTIFY, isDownloaded = true)
        assertNull(vm.cloudSource(Track(id = 7, title = "t", artist = "a", isDownloaded = true)))
    }

    @Test fun `a song with no library row asks nothing of the database`() = runTest {
        assertNull(vm.cloudSource(Track(id = 0, title = "t", artist = "a")))
        coVerify(exactly = 0) { cloud.getTrack(any()) }
    }

    @Test fun `a database hiccup costs the label, not the sheet`() = runTest {
        coEvery { cloud.getTrack(7) } throws IllegalStateException("database is locked")
        assertNull(vm.cloudSource(Track(id = 7, title = "t", artist = "a")))
    }
}
