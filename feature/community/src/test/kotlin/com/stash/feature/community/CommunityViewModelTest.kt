package com.stash.feature.community

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.community.CommunityRepository
import com.stash.core.data.community.CommunityResult
import com.stash.core.data.community.VoteCounts
import com.stash.core.model.community.CommunityPost
import com.stash.feature.community.CommunityViewModel.Tab
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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

    @Test fun `a vote on a post that's gone reloads the list`() = runTest(dispatcher) {
        coEvery { repo.feed(5) } returnsMany listOf(CommunityResult.Ok(listOf(post())), CommunityResult.Ok(emptyList()))
        coEvery { repo.vote(any(), any()) } returns CommunityResult.Rejected("gone")
        val vm = CommunityViewModel(repo)
        vm.show(Tab.HOME); advanceUntilIdle()
        vm.vote(post(), 1); advanceUntilIdle()
        coVerify(exactly = 2) { repo.feed(5) }
        assertThat(vm.state.value.posts).isEmpty()
    }

    @Test fun `a tap while a vote is out shows at once and is sent after it`() = runTest(dispatcher) {
        coEvery { repo.feed(5) } returns CommunityResult.Ok(listOf(post()))
        coEvery { repo.vote("AAAAAAAA", 1) } coAnswers { delay(1_000); CommunityResult.Ok(VoteCounts(up = 4, down = 1, myVote = 1)) }
        coEvery { repo.vote("AAAAAAAA", -1) } returns CommunityResult.Ok(VoteCounts(up = 3, down = 5, myVote = -1))
        val vm = CommunityViewModel(repo)
        vm.show(Tab.HOME); advanceUntilIdle()
        vm.vote(post(), 1); runCurrent()
        vm.vote(post(up = 4, myVote = 1), -1)
        assertThat(vm.state.value.posts).containsExactly(post(up = 3, down = 2, myVote = -1))
        advanceUntilIdle()
        coVerifyOrder {
            repo.vote("AAAAAAAA", 1)
            repo.vote("AAAAAAAA", -1)
        }
        assertThat(vm.state.value.posts).containsExactly(post(up = 3, down = 5, myVote = -1))
    }

    @Test fun `a reload while a vote is out keeps the vote on its row`() = runTest(dispatcher) {
        // The reload's row is from before the vote, with someone else's upvote since.
        coEvery { repo.feed(5) } returnsMany listOf(CommunityResult.Ok(listOf(post())), CommunityResult.Ok(listOf(post(up = 5))))
        coEvery { repo.vote("AAAAAAAA", 1) } coAnswers { delay(1_000); CommunityResult.Ok(VoteCounts(up = 9, down = 1, myVote = 1)) }
        val vm = CommunityViewModel(repo)
        vm.show(Tab.HOME); advanceUntilIdle()
        vm.vote(post(), 1); runCurrent()
        vm.show(Tab.HOME); runCurrent()
        assertThat(vm.state.value.posts).containsExactly(post(up = 6, myVote = 1))
        verify { repo.lastHome = listOf(post(up = 6, myVote = 1)) }
        advanceUntilIdle()
        assertThat(vm.state.value.posts).containsExactly(post(up = 9, myVote = 1))
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
        coEvery { repo.feed(5) } returnsMany listOf(
            CommunityResult.Ok(listOf(post())), CommunityResult.Ok(listOf(post(up = 7))), CommunityResult.Ok(listOf(post(up = 8))),
        )
        var now = 1_000_000L // well past 30 s, since a loadedAt of 0 means never loaded
        val vm = CommunityViewModel(repo).apply { clock = { now } }
        vm.onShown(); advanceUntilIdle()
        now += 29_000
        vm.onShown(); advanceUntilIdle()
        coVerify(exactly = 1) { repo.feed(5) }
        now += 2_000
        vm.onShown(); advanceUntilIdle()
        assertThat(vm.state.value.posts).containsExactly(post(up = 7))
        every { repo.lastVoteAt } returns Long.MAX_VALUE
        vm.onShown(); advanceUntilIdle()
        assertThat(vm.state.value.posts).containsExactly(post(up = 8))
    }

    @Test fun `Home reloads after the clock is set back`() = runTest(dispatcher) {
        coEvery { repo.feed(5) } returnsMany listOf(CommunityResult.Ok(listOf(post())), CommunityResult.Ok(listOf(post(up = 7))))
        var now = 1_000_000L
        val vm = CommunityViewModel(repo).apply { clock = { now } }
        vm.onShown(); advanceUntilIdle()
        now -= 60_000 // the last load now looks like it's from the future
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
