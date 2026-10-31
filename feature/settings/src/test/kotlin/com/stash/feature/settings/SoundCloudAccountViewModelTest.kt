package com.stash.feature.settings

import com.stash.core.auth.TokenManager
import com.stash.core.data.repository.MusicRepository
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SoundCloudAccountViewModelTest {
    @Before fun setup() {
        val main = UnconfinedTestDispatcher()
        Dispatchers.setMain(main)
        SoundCloudAccountViewModel.io = main
    }
    @After fun cleanup() {
        SoundCloudAccountViewModel.io = Dispatchers.IO
        Dispatchers.resetMain()
    }

    @Test fun `a second tap is ignored while busy and cancel closes validation`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val tokens = mockk<TokenManager> {
            every { soundCloudSession } returns flowOf(null)
            coEvery { connectSoundCloudPublicProfile(any()) } coAnswers { started.complete(Unit); release.await(); true }
        }
        val vm = SoundCloudAccountViewModel(tokens, mockk<MusicRepository>(relaxed = true))
        vm.open()
        vm.connectProfile("https://soundcloud.com/artist")
        started.await()
        vm.connectProfile("https://soundcloud.com/another")
        coVerify(exactly = 1) { tokens.connectSoundCloudPublicProfile(any()) }
        vm.close()
        assertFalse(vm.ui.value.open)
        assertFalse(vm.ui.value.busy)
        release.complete(Unit)
    }
}
