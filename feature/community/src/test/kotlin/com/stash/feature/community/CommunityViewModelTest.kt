package com.stash.feature.community

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.community.CommunityRepository
import com.stash.core.data.community.CommunityResult
import com.stash.core.data.community.VoteCounts
import com.stash.core.model.community.CommunityPost
import com.stash.feature.community.CommunityViewModel.Tab
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommunityViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val revision = MutableStateFlow(0)
    private val repo = mockk<CommunityRepository>(relaxed = true)

    private fun post(up: Int = 3, down: Int = 1, myVote: Int = 0, mine: Boolean = false) = CommunityPost(
        id = "AAAAAAAA", kind = "song", title = "garden", name = "Sam", count = 1, createdAt = 1,
        up = up, down = down, myVote = myVote, mine = mine,
    )

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { repo.revision } returns revision
        every { repo.lastHome } returns null
        every { repo.lastVoteAt } returns 0L
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `withVote moves the counts by the change`() {
        assertThat(post().withVote(1)).isEqualTo(post(up = 4, myVote = 1))
        assertThat(post(up = 4, myVote = 1).withVote(-1)).isEqualTo(post(up = 3, down = 2, myVote = -1))
        assertThat(post(up = 3, down = 2, myVote = -1).withVote(0)).isEqualTo(post())
    }

    @Test fun `tapping your current vote again takes it back`() {
        assertThat(post(myVote = 1).nextVote(1)).isEqualTo(0)
        assertThat(post(myVote = 1).nextVote(-1)).isEqualTo(-1)
        assertThat(post().nextVote(1)).isEqualTo(1)
    }

    @Test fun `a vote shows at once, then takes the server's counts`() = runTest(dispatcher) {
        coEvery { repo.feed(5) } returns CommunityResult.Ok(listOf(post()))
        coEvery { repo.vote("AAAAAAAA", 1) } returns CommunityResult.Ok(VoteCounts(up = 9, down = 1, myVote = 1))
        val vm = CommunityViewModel(repo)
        vm.show(Tab.HOME); advanceUntilIdle()
        vm.vote(post(), 1)
        assertThat(vm.state.value.posts).containsExactly(post(up = 4, myVote = 1))
        advanceUntilIdle()
        assertThat(vm.state.value.posts).containsExactly(post(up = 9, myVote = 1))
        verify { repo.lastHome = listOf(post(up = 9, myVote = 1)) }
    }

    @Test fun `a refused vote flips back and says why`() = runTest(dispatcher) {
        coEvery { repo.feed(5) } returns CommunityResult.Ok(listOf(post()))
        coEvery { repo.vote(any(), any()) } returns CommunityResult.Failed("offline")
        val vm = CommunityViewModel(repo)
        vm.show(Tab.HOME); advanceUntilIdle()
        vm.vote(post(), -1); advanceUntilIdle()
        assertThat(vm.state.value.posts).containsExactly(post())
        assertThat(vm.state.value.message).isEqualTo("Couldn't reach Community. Try again.")
    }

    @Test fun `your own post can't be voted on`() = runTest(dispatcher) {
        val vm = CommunityViewModel(repo)
        vm.vote(post(mine = true), 1); advanceUntilIdle()
        coVerify(exactly = 0) { repo.vote(any(), any()) }
    }

    @Test fun `Home shows the last list at once and keeps it when a reload fails`() = runTest(dispatcher) {
        every { repo.lastHome } returns listOf(post())
        coEvery { repo.feed(5) } returns CommunityResult.Failed("offline")
        val vm = CommunityViewModel(repo)
        assertThat(vm.state.value.posts).containsExactly(post())
        vm.show(Tab.HOME); advanceUntilIdle()
        assertThat(vm.state.value.posts).containsExactly(post())
        assertThat(vm.state.value.failed).isFalse()
    }

    @Test fun `nothing loaded and no connection is a failure to retry`() = runTest(dispatcher) {
        coEvery { repo.feed(5) } returns CommunityResult.Failed("offline")
        val vm = CommunityViewModel(repo)
        vm.show(Tab.HOME); advanceUntilIdle()
        assertThat(vm.state.value.posts).isNull()
        assertThat(vm.state.value.failed).isTrue()
    }

    @Test fun `Mine replaces the list instead of showing All's while it loads`() = runTest(dispatcher) {
        coEvery { repo.feed(100) } returns CommunityResult.Ok(listOf(post()))
        coEvery { repo.mine() } returns CommunityResult.Ok(emptyList())
        val vm = CommunityViewModel(repo)
        vm.show(Tab.ALL); advanceUntilIdle()
        vm.show(Tab.MINE)
        assertThat(vm.state.value.posts).isNull()
        advanceUntilIdle()
        assertThat(vm.state.value.posts).isEmpty()
    }

    @Test fun `Home reloads when it shows again only after 30 seconds or a vote`() = runTest(dispatcher) {
        coEvery { repo.feed(5) } returnsMany listOf(CommunityResult.Ok(listOf(post())), CommunityResult.Ok(listOf(post(up = 7))))
        val vm = CommunityViewModel(repo)
        vm.onShown(); advanceUntilIdle()
        vm.onShown(); advanceUntilIdle()
        coVerify(exactly = 1) { repo.feed(5) }
        every { repo.lastVoteAt } returns Long.MAX_VALUE
        vm.onShown(); advanceUntilIdle()
        assertThat(vm.state.value.posts).containsExactly(post(up = 7))
    }

    @Test fun `a post or take-down elsewhere reloads the list on screen`() = runTest(dispatcher) {
        coEvery { repo.feed(5) } returnsMany listOf(CommunityResult.Ok(emptyList()), CommunityResult.Ok(listOf(post())))
        val vm = CommunityViewModel(repo)
        vm.show(Tab.HOME); advanceUntilIdle()
        revision.value = 1; advanceUntilIdle()
        assertThat(vm.state.value.posts).containsExactly(post())
    }
}
