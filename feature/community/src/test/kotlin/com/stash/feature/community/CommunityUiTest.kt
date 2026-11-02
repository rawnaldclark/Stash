package com.stash.feature.community

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.community.CommunityRepository
import com.stash.core.data.community.CommunityResult
import com.stash.core.data.community.VoteCounts
import com.stash.core.model.community.CommunityPost
import com.stash.feature.community.CommunityViewModel.Tab
import com.stash.feature.community.CommunityViewModel.UiState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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

/** The redesign's rules: what each list shows (state to UI), the vote pill through a vote's life, and the segments. */
@OptIn(ExperimentalCoroutinesApi::class)
class CommunityUiTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<CommunityRepository>(relaxed = true)

    private fun post(id: String, createdAt: Long = 1, up: Int = 3, down: Int = 1, myVote: Int = 0, mine: Boolean = false) = CommunityPost(
        id = id, kind = "playlist", title = "t$id", name = "Maya", count = 4, createdAt = createdAt,
        up = up, down = down, myVote = myVote, mine = mine,
    )

    // The chart as the Worker ranks it: votes first, so the oldest (B) is on top and the newest (C) last.
    private val a = post("AAAAAAAA", createdAt = 200)
    private val b = post("BBBBBBBB", createdAt = 100)
    private val c = post("CCCCCCCC", createdAt = 300)
    private val chart = listOf(b, a, c)

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { repo.revision } returns MutableStateFlow(0)
        every { repo.lastHome } returns null
        every { repo.lastVoteAt } returns 0L
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    // -- State to UI ------------------------------------------------------------------------------------------

    @Test fun `Home and Top make the number one the hero and rank the rest from 2`() {
        val expected = ListUi.Ready(hero = b, rows = listOf(RankedPost(2, a), RankedPost(3, c)))
        assertThat(listUi(UiState(tab = Tab.HOME, posts = chart), segment = null)).isEqualTo(expected)
        assertThat(listUi(UiState(tab = Tab.ALL, posts = chart), Segment.TOP)).isEqualTo(expected)
    }

    @Test fun `New is the same posts newest first, with no hero and no ranks`() {
        assertThat(listUi(UiState(tab = Tab.ALL, posts = chart), Segment.NEW))
            .isEqualTo(ListUi.Ready(hero = null, rows = listOf(RankedPost(null, c), RankedPost(null, a), RankedPost(null, b))))
    }

    @Test fun `Your posts keep the order they came in, with no hero and no ranks`() {
        val mine = listOf(c.copy(mine = true), b.copy(mine = true))
        assertThat(listUi(UiState(tab = Tab.MINE, posts = mine), Segment.MINE))
            .isEqualTo(ListUi.Ready(hero = null, rows = mine.map { RankedPost(null, it) }))
    }

    @Test fun `a lone post is the hero with nothing after it`() {
        assertThat(listUi(UiState(tab = Tab.HOME, posts = listOf(a)), null)).isEqualTo(ListUi.Ready(hero = a, rows = emptyList()))
    }

    @Test fun `nothing yet is loading, a failed first load is an error, and an empty list is empty`() {
        assertThat(listUi(UiState(tab = Tab.ALL, posts = null), Segment.TOP)).isEqualTo(ListUi.Loading)
        assertThat(listUi(UiState(tab = Tab.ALL, posts = null, failed = true), Segment.TOP)).isEqualTo(ListUi.Failed)
        assertThat(listUi(UiState(tab = Tab.MINE, posts = emptyList()), Segment.MINE)).isEqualTo(ListUi.Empty)
    }

    @Test fun `a list still from another tab reads as loading, so Your posts never flashes the chart`() {
        assertThat(listUi(UiState(tab = Tab.ALL, posts = chart), Segment.MINE)).isEqualTo(ListUi.Loading)
        // See all opens on Home's last list: loading until show(ALL) takes it over (and keeps it on screen).
        assertThat(listUi(UiState(tab = Tab.HOME, posts = chart), Segment.TOP)).isEqualTo(ListUi.Loading)
    }

    @Test fun `Play all and Shuffle show on Top once it has posts, and nowhere else`() {
        val ready = listUi(UiState(tab = Tab.ALL, posts = chart), Segment.TOP)
        assertThat(showsChartPlay(Segment.TOP, ready)).isTrue()
        assertThat(showsChartPlay(Segment.NEW, ready)).isFalse()
        assertThat(showsChartPlay(Segment.TOP, ListUi.Empty)).isFalse()
        assertThat(showsChartPlay(Segment.TOP, ListUi.Loading)).isFalse()
    }

    @Test fun `ages read in the largest whole unit, and a clock behind the post is just now`() {
        val min = 60_000L
        assertThat(ageOf(0, 30_000)).isEqualTo(Age.JustNow)
        assertThat(ageOf(1_000, 0)).isEqualTo(Age.JustNow)
        assertThat(ageOf(0, 5 * min)).isEqualTo(Age.Minutes(5))
        assertThat(ageOf(0, 59 * min)).isEqualTo(Age.Minutes(59))
        assertThat(ageOf(0, 4 * 60 * min)).isEqualTo(Age.Hours(4))
        assertThat(ageOf(0, 3 * 24 * 60 * min + 5 * min)).isEqualTo(Age.Days(3))
    }

    @Test fun `Your posts count whole days left, and nothing when the Worker didn't say`() {
        val day = 86_400_000L
        assertThat(daysLeft(26 * day + 5, 0)).isEqualTo(26)
        assertThat(daysLeft(day / 2, 0)).isEqualTo(0)
        assertThat(daysLeft(0, day)).isEqualTo(0)
        assertThat(daysLeft(null, 0)).isNull()
    }

    // -- The vote pill ----------------------------------------------------------------------------------------

    @Test fun `the pill reads the score, this phone's vote, and your own post as inert`() {
        assertThat(post("AAAAAAAA", up = 12, down = 2).votePill()).isEqualTo(VotePillUi(10, upLit = false, downLit = false, enabled = true))
        assertThat(post("AAAAAAAA", myVote = 1).votePill()).isEqualTo(VotePillUi(2, upLit = true, downLit = false, enabled = true))
        assertThat(post("AAAAAAAA", up = 0, down = 3, myVote = -1).votePill()).isEqualTo(VotePillUi(-3, upLit = false, downLit = true, enabled = true))
        assertThat(post("AAAAAAAA", mine = true).votePill().enabled).isFalse()
    }

    @Test fun `a tap lights the pill at once, and a refusal puts it back`() = runTest(dispatcher) {
        coEvery { repo.feed(5) } returns CommunityResult.Ok(listOf(a))
        coEvery { repo.vote(a.id, 1) } returns CommunityResult.Failed("offline")
        val vm = CommunityViewModel(repo, mockk(relaxed = true))
        vm.show(Tab.HOME); advanceUntilIdle()
        vm.vote(a, 1)
        assertThat(pill(vm)).isEqualTo(VotePillUi(3, upLit = true, downLit = false, enabled = true))
        advanceUntilIdle()
        assertThat(pill(vm)).isEqualTo(VotePillUi(2, upLit = false, downLit = false, enabled = true))
        assertThat(vm.state.value.message).isNotNull()
    }

    @Test fun `tapping the lit arrow takes the vote back, and the other arrow moves it over`() = runTest(dispatcher) {
        val voted = a.copy(up = 4, myVote = 1)
        coEvery { repo.feed(5) } returns CommunityResult.Ok(listOf(voted))
        coEvery { repo.vote(a.id, 0) } returns CommunityResult.Ok(VoteCounts(up = 3, down = 1, myVote = 0))
        coEvery { repo.vote(a.id, -1) } returns CommunityResult.Ok(VoteCounts(up = 3, down = 2, myVote = -1))
        val vm = CommunityViewModel(repo, mockk(relaxed = true))
        vm.show(Tab.HOME); advanceUntilIdle()
        vm.vote(voted, 1); advanceUntilIdle()
        assertThat(pill(vm)).isEqualTo(VotePillUi(2, upLit = false, downLit = false, enabled = true))
        vm.vote(vm.state.value.posts!!.single(), -1)
        assertThat(pill(vm)).isEqualTo(VotePillUi(1, upLit = false, downLit = true, enabled = true))
        advanceUntilIdle()
        assertThat(pill(vm)).isEqualTo(VotePillUi(1, upLit = false, downLit = true, enabled = true))
    }

    @Test fun `the server's count wins once it answers`() = runTest(dispatcher) {
        coEvery { repo.feed(5) } returns CommunityResult.Ok(listOf(a))
        // Others voted meanwhile.
        coEvery { repo.vote(a.id, 1) } returns CommunityResult.Ok(VoteCounts(up = 20, down = 1, myVote = 1))
        val vm = CommunityViewModel(repo, mockk(relaxed = true))
        vm.show(Tab.HOME); advanceUntilIdle()
        vm.vote(a, 1); advanceUntilIdle()
        assertThat(pill(vm)).isEqualTo(VotePillUi(19, upLit = true, downLit = false, enabled = true))
    }

    @Test fun `your own post's pill sends nothing`() = runTest(dispatcher) {
        val own = a.copy(mine = true)
        coEvery { repo.feed(5) } returns CommunityResult.Ok(listOf(own))
        val vm = CommunityViewModel(repo, mockk(relaxed = true))
        vm.show(Tab.HOME); advanceUntilIdle()
        vm.vote(own, 1); advanceUntilIdle()
        assertThat(pill(vm)).isEqualTo(VotePillUi(2, upLit = false, downLit = false, enabled = false))
        coVerify(exactly = 0) { repo.vote(any(), any()) }
    }

    // -- Segments and refresh ---------------------------------------------------------------------------------

    @Test fun `Top and New are one list, Your posts is another`() {
        assertThat(Segment.TOP.tab).isEqualTo(Tab.ALL)
        assertThat(Segment.NEW.tab).isEqualTo(Tab.ALL)
        assertThat(Segment.MINE.tab).isEqualTo(Tab.MINE)
    }

    @Test fun `pull to refresh reloads the list on screen and stops when it answers`() = runTest(dispatcher) {
        coEvery { repo.feed(100) } returnsMany listOf(CommunityResult.Ok(listOf(a)), CommunityResult.Ok(chart))
        val vm = CommunityViewModel(repo, mockk(relaxed = true))
        vm.show(Tab.ALL); advanceUntilIdle()
        vm.refresh()
        assertThat(vm.state.value.refreshing).isTrue()
        // The old list stays while it loads.
        assertThat(vm.state.value.posts).containsExactly(a)
        advanceUntilIdle()
        assertThat(vm.state.value.refreshing).isFalse()
        assertThat(vm.state.value.posts).isEqualTo(chart)
        assertThat(vm.state.value.message).isNull()
    }

    @Test fun `a pulled refresh that fails keeps the list and says why, a quiet reload stays quiet`() = runTest(dispatcher) {
        coEvery { repo.feed(100) } returnsMany listOf(
            CommunityResult.Ok(chart), CommunityResult.Failed("offline"), CommunityResult.Failed("offline"),
        )
        val vm = CommunityViewModel(repo, mockk(relaxed = true))
        vm.show(Tab.ALL); advanceUntilIdle()
        vm.refresh(); advanceUntilIdle()
        assertThat(vm.state.value.refreshing).isFalse()
        assertThat(vm.state.value.posts).isEqualTo(chart)
        assertThat(vm.state.value.message).isEqualTo("Couldn't reach Community. Try again.")
        vm.messageShown()
        vm.show(Tab.ALL); advanceUntilIdle()
        assertThat(vm.state.value.message).isNull()
    }

    @Test fun `a pulled refresh on an error page stays an error page`() = runTest(dispatcher) {
        coEvery { repo.mine() } returns CommunityResult.Failed("offline")
        val vm = CommunityViewModel(repo, mockk(relaxed = true))
        vm.show(Tab.MINE); advanceUntilIdle()
        vm.refresh(); advanceUntilIdle()
        assertThat(vm.state.value.refreshing).isFalse()
        assertThat(listUi(vm.state.value, Segment.MINE)).isEqualTo(ListUi.Failed)
    }

    private fun pill(vm: CommunityViewModel) = vm.state.value.posts!!.single().votePill()
}
