package com.stash.feature.community

import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The redesign's rules: what each list shows (state to UI), the hero's ink, the vote pill through a vote's life, the segments. */
@OptIn(ExperimentalCoroutinesApi::class)
class CommunityUiTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<CommunityRepository>(relaxed = true)

    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = 40 * day

    /** Heard unless a test says otherwise: the pill as it was before the listen rule. */
    private fun post(id: String, age: Long = day, up: Int = 3, down: Int = 1, myVote: Int = 0, mine: Boolean = false, heard: Boolean = true) =
        CommunityPost(
            id = id, kind = "playlist", title = "t$id", name = "Maya", count = 4, createdAt = now - age,
            up = up, down = down, myVote = myVote, mine = mine, heard = heard,
        )

    // The list in the Worker's "hot" order (votes plus age, 3.6 h a vote), which is neither a top nor a newest:
    // the phone orders it. Scores: a 2, b 2 (newer than a), c 5 but 10 days old, d -1, e 0, f 0 (newer than e).
    private val a = post("AAAAAAAA", age = 2 * day)
    private val b = post("BBBBBBBB", age = day)
    private val c = post("CCCCCCCC", age = 10 * day, up = 6)
    private val d = post("DDDDDDDD", age = hour, up = 0)
    private val e = post("EEEEEEEE", age = 3 * day, up = 1)
    private val f = post("FFFFFFFF", age = 2 * hour, up = 1)
    private val hot = listOf(d, f, b, a, e, c)

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { repo.revision } returns MutableStateFlow(0)
        every { repo.lastHome } returns null
        every { repo.lastVoteAt } returns 0L
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    // -- The chart --------------------------------------------------------------------------------------------

    @Test fun `Top this week is the week's posts by votes, the newer first between equals`() {
        // c has the most votes but is 10 days old: not this week.
        assertThat(topChart(hot, now)).isEqualTo(Chart(listOf(b, a, f, e, d), thisWeek = true))
    }

    @Test fun `with fewer than five posts this week Top is every live post by votes and isn't this week's`() {
        assertThat(topChart(listOf(d, b, a, c), now)).isEqualTo(Chart(listOf(c, b, a, d), thisWeek = false))
    }

    @Test fun `a post dated ahead of this phone's clock counts as this week`() {
        val ahead = post("GGGGGGGG", age = -hour)
        assertThat(topChart(listOf(ahead, a, b, d, e), now).thisWeek).isTrue()
    }

    @Test fun `Top makes the chart's number one the hero and ranks the rest from 2`() {
        assertThat(listUi(UiState(tab = Tab.ALL, posts = hot), Segment.TOP, now)).isEqualTo(
            ListUi.Ready(hero = b, rows = listOf(RankedPost(2, a), RankedPost(3, f), RankedPost(4, e), RankedPost(5, d))),
        )
    }

    @Test fun `Home takes the same hero and at most #2 to #5`() {
        val more = hot + post("GGGGGGGG", age = 4 * day, up = 0)
        val home = listUi(UiState(tab = Tab.HOME, posts = more), segment = null, now = now) as ListUi.Ready
        assertThat(home.hero).isEqualTo(b)
        assertThat(home.rows.map { it.rank }).containsExactly(2, 3, 4, 5).inOrder()
    }

    @Test fun `a chart that reaches past the week says so`() {
        val ui = listUi(UiState(tab = Tab.HOME, posts = listOf(d, c)), segment = null, now = now) as ListUi.Ready
        assertThat(ui.hero).isEqualTo(c)
        assertThat(ui.thisWeek).isFalse()
    }

    @Test fun `New is every post newest first, with no hero and no ranks`() {
        assertThat(listUi(UiState(tab = Tab.ALL, posts = hot), Segment.NEW, now))
            .isEqualTo(ListUi.Ready(hero = null, rows = listOf(d, f, b, a, e, c).map { RankedPost(null, it) }))
    }

    @Test fun `Your posts keep the order they came in, with no hero and no ranks`() {
        val mine = listOf(c.copy(mine = true), b.copy(mine = true))
        assertThat(listUi(UiState(tab = Tab.MINE, posts = mine), Segment.MINE, now))
            .isEqualTo(ListUi.Ready(hero = null, rows = mine.map { RankedPost(null, it) }))
    }

    @Test fun `nothing yet is loading, a failed first load is an error, and an empty list is empty`() {
        assertThat(listUi(UiState(tab = Tab.ALL, posts = null), Segment.TOP, now)).isEqualTo(ListUi.Loading)
        assertThat(listUi(UiState(tab = Tab.ALL, posts = null, failed = true), Segment.TOP, now)).isEqualTo(ListUi.Failed)
        assertThat(listUi(UiState(tab = Tab.MINE, posts = emptyList()), Segment.MINE, now)).isEqualTo(ListUi.Empty)
    }

    @Test fun `a list still from another tab reads as loading, so Your posts never flashes the chart`() {
        assertThat(listUi(UiState(tab = Tab.ALL, posts = hot), Segment.MINE, now)).isEqualTo(ListUi.Loading)
        // See all opens on Home's last list: loading until show(ALL) takes it over (and keeps it on screen).
        assertThat(listUi(UiState(tab = Tab.HOME, posts = hot), Segment.TOP, now)).isEqualTo(ListUi.Loading)
    }

    @Test fun `Play all and Shuffle show on Top once it has posts, and play it in the order shown`() {
        val ready = listUi(UiState(tab = Tab.ALL, posts = hot), Segment.TOP, now) as ListUi.Ready
        assertThat(showsChartPlay(Segment.TOP, ready)).isTrue()
        assertThat(showsChartPlay(Segment.NEW, ready)).isFalse()
        assertThat(showsChartPlay(Segment.TOP, ListUi.Empty)).isFalse()
        assertThat(showsChartPlay(Segment.TOP, ListUi.Loading)).isFalse()
        assertThat(ready.chartPosts()).containsExactly(b, a, f, e, d).inOrder()
    }

    @Test fun `Play all hands the chart player the posts it's given`() = runTest(dispatcher) {
        val player = mockk<CommunityChartPlayer>(relaxed = true)
        coEvery { player.play(any(), any(), any()) } returns true
        val vm = CommunityViewModel(repo, player)
        vm.playChart(shuffle = false, posts = listOf(b, a))
        advanceUntilIdle()
        coVerify { player.play(listOf(b, a), false, any()) }
        assertThat(vm.state.value.message).isNull()
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
        assertThat(daysLeft(26 * day + 5, 0)).isEqualTo(26)
        assertThat(daysLeft(day / 2, 0)).isEqualTo(0)
        assertThat(daysLeft(0, day)).isEqualTo(0)
        assertThat(daysLeft(null, 0)).isNull()
    }

    // -- The hero's ink ---------------------------------------------------------------------------------------

    @Test fun `the hero's every line holds AA contrast over white and over pale covers sampled on the phone`() {
        // A white cover blurs to white: the worst case. The others were sampled from the P5 (2026-11-01): the KFC
        // cover's average and its cream corner, and THE RIVER's grey.
        val backdrops = listOf(Color.White, Color(0xFFBD957F), Color(0xFFD7C3B0), Color(0xFFB9B8B9))
        for (backdrop in backdrops) {
            for (alpha in listOf(1f, HERO_FACTS_ALPHA, HERO_POSTER_ALPHA)) {
                assertThat(heroTextContrast(backdrop, alpha)).isAtLeast(4.5)
            }
        }
    }

    // -- The vote pill ----------------------------------------------------------------------------------------

    @Test fun `the pill reads the score, this phone's vote, and your own post as inert`() {
        assertThat(post("AAAAAAAA", up = 12, down = 2).votePill()).isEqualTo(VotePillUi(10, upLit = false, downLit = false, enabled = true))
        assertThat(post("AAAAAAAA", myVote = 1).votePill()).isEqualTo(VotePillUi(2, upLit = true, downLit = false, enabled = true))
        assertThat(post("AAAAAAAA", up = 0, down = 3, myVote = -1).votePill()).isEqualTo(VotePillUi(-3, upLit = false, downLit = true, enabled = true))
        assertThat(post("AAAAAAAA", mine = true).votePill().enabled).isFalse()
    }

    @Test fun `a tap lights the pill at once, and a refusal puts it back`() = runTest(dispatcher) {
        coEvery { repo.feed(100) } returns CommunityResult.Ok(listOf(a))
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
        coEvery { repo.feed(100) } returns CommunityResult.Ok(listOf(voted))
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
        coEvery { repo.feed(100) } returns CommunityResult.Ok(listOf(a))
        // Others voted meanwhile.
        coEvery { repo.vote(a.id, 1) } returns CommunityResult.Ok(VoteCounts(up = 20, down = 1, myVote = 1))
        val vm = CommunityViewModel(repo, mockk(relaxed = true))
        vm.show(Tab.HOME); advanceUntilIdle()
        vm.vote(a, 1); advanceUntilIdle()
        assertThat(pill(vm)).isEqualTo(VotePillUi(19, upLit = true, downLit = false, enabled = true))
    }

    @Test fun `your own post's pill sends nothing`() = runTest(dispatcher) {
        val own = a.copy(mine = true)
        coEvery { repo.feed(100) } returns CommunityResult.Ok(listOf(own))
        val vm = CommunityViewModel(repo, mockk(relaxed = true))
        vm.show(Tab.HOME); advanceUntilIdle()
        vm.vote(own, 1); advanceUntilIdle()
        assertThat(pill(vm)).isEqualTo(VotePillUi(2, upLit = false, downLit = false, enabled = false))
        coVerify(exactly = 0) { repo.vote(any(), any()) }
    }

    // -- Segments and refresh ---------------------------------------------------------------------------------

    @Test fun `Top and New are one list, Your posts is another, and Home reads the whole list too`() = runTest(dispatcher) {
        assertThat(Segment.TOP.tab).isEqualTo(Tab.ALL)
        assertThat(Segment.NEW.tab).isEqualTo(Tab.ALL)
        assertThat(Segment.MINE.tab).isEqualTo(Tab.MINE)
        // Home's #1 is the week's top by votes, which the Worker's first five ("hot") needn't hold.
        coEvery { repo.feed(100) } returns CommunityResult.Ok(hot)
        val vm = CommunityViewModel(repo, mockk(relaxed = true))
        vm.show(Tab.HOME); advanceUntilIdle()
        coVerify(exactly = 1) { repo.feed(100) }
        coVerify(exactly = 0) { repo.feed(5) }
    }

    @Test fun `pull to refresh reloads the list on screen and stops when it answers`() = runTest(dispatcher) {
        coEvery { repo.feed(100) } returnsMany listOf(CommunityResult.Ok(listOf(a)), CommunityResult.Ok(hot))
        val vm = CommunityViewModel(repo, mockk(relaxed = true))
        vm.show(Tab.ALL); advanceUntilIdle()
        vm.refresh()
        assertThat(vm.state.value.refreshing).isTrue()
        // The old list stays while it loads.
        assertThat(vm.state.value.posts).containsExactly(a)
        advanceUntilIdle()
        assertThat(vm.state.value.refreshing).isFalse()
        assertThat(vm.state.value.posts).isEqualTo(hot)
        assertThat(vm.state.value.message).isNull()
    }

    @Test fun `a pulled refresh that fails keeps the list and says why, a quiet reload stays quiet`() = runTest(dispatcher) {
        coEvery { repo.feed(100) } returnsMany listOf(
            CommunityResult.Ok(hot), CommunityResult.Failed("offline"), CommunityResult.Failed("offline"),
        )
        val vm = CommunityViewModel(repo, mockk(relaxed = true))
        vm.show(Tab.ALL); advanceUntilIdle()
        vm.refresh(); advanceUntilIdle()
        assertThat(vm.state.value.refreshing).isFalse()
        assertThat(vm.state.value.posts).isEqualTo(hot)
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
        assertThat(listUi(vm.state.value, Segment.MINE, now)).isEqualTo(ListUi.Failed)
    }

    private fun pill(vm: CommunityViewModel) = vm.state.value.posts!!.single().votePill()

    // -- Listen first (contract 2026-11-03) ---------------------------------------------------------------------

    @Test fun `until the post is heard its pill is dimmed with the hint, and an earlier vote stays lit to take back`() {
        val waiting = VotePillUi(2, upLit = false, downLit = false, enabled = true, listenFirst = true, upDimmed = true, downDimmed = true)
        assertThat(post("AAAAAAAA", heard = false).votePill()).isEqualTo(waiting)
        // A vote from before the rule: lit, and it can be taken back; the other arrow waits.
        assertThat(post("AAAAAAAA", up = 4, myVote = 1, heard = false).votePill())
            .isEqualTo(VotePillUi(3, upLit = true, downLit = false, enabled = true, listenFirst = true, upDimmed = false, downDimmed = true))
        assertThat(post("AAAAAAAA", down = 2, myVote = -1, heard = false).votePill())
            .isEqualTo(VotePillUi(1, upLit = false, downLit = true, enabled = true, listenFirst = true, upDimmed = true, downDimmed = false))
        // Your own post: no hint, inert as before.
        assertThat(post("AAAAAAAA", mine = true, heard = false).votePill()).isEqualTo(VotePillUi(2, upLit = false, downLit = false, enabled = false))
    }

    @Test fun `this phone's mark or the server's flag makes a post heard`() {
        val waiting = post("AAAAAAAA", heard = false)
        assertThat(waiting.heardFrom(setOf("AAAAAAAA")).votePill()).isEqualTo(VotePillUi(2, upLit = false, downLit = false, enabled = true))
        assertThat(waiting.heardFrom(setOf("BBBBBBBB")).votePill().listenFirst).isTrue()
        // An older Worker sends no flag: the post reads as not heard (the field's default), the mark still counts.
        assertThat(post("AAAAAAAA").heardFrom(emptySet()).votePill().listenFirst).isFalse()
        assertThat(waiting.needsListen(1)).isTrue()
        assertThat(waiting.copy(myVote = 1).needsListen(1)).isFalse() // taking it back
        assertThat(waiting.copy(myVote = 1).needsListen(-1)).isTrue()
    }

    @Test fun `a dimmed arrow sends no vote and shows the note, while taking a vote back still goes`() = runTest(dispatcher) {
        val earlier = a.copy(up = 4, myVote = 1, heard = false)
        coEvery { repo.feed(100) } returns CommunityResult.Ok(listOf(earlier))
        coEvery { repo.vote(a.id, 0) } returns CommunityResult.Ok(VoteCounts(up = 3, down = 1, myVote = 0))
        val vm = CommunityViewModel(repo, mockk(relaxed = true))
        vm.show(Tab.HOME); advanceUntilIdle()
        vm.vote(earlier, -1); advanceUntilIdle()
        assertThat(vm.state.value.listenFirst).isTrue()
        assertThat(pill(vm)).isEqualTo(earlier.votePill())
        coVerify(exactly = 0) { repo.vote(any(), -1) }
        vm.listenFirstShown()
        assertThat(vm.state.value.listenFirst).isFalse()
        vm.vote(earlier, 1); advanceUntilIdle()
        coVerify { repo.vote(a.id, 0) }
        assertThat(pill(vm)).isEqualTo(VotePillUi(2, upLit = false, downLit = false, enabled = true, listenFirst = true, upDimmed = true, downDimmed = true))
        assertThat(vm.state.value.listenFirst).isFalse()
    }

    @Test fun `the pill wakes when the post is heard while it's on screen, and stays awake on a reload`() = runTest(dispatcher) {
        val heard = MutableStateFlow(emptySet<String>())
        every { repo.heardIds } returns heard
        coEvery { repo.feed(100) } returns CommunityResult.Ok(listOf(a.copy(heard = false)))
        val vm = CommunityViewModel(repo, mockk(relaxed = true))
        vm.show(Tab.HOME); advanceUntilIdle()
        assertThat(pill(vm).listenFirst).isTrue()
        heard.value = setOf(a.id); advanceUntilIdle()
        assertThat(pill(vm)).isEqualTo(VotePillUi(2, upLit = false, downLit = false, enabled = true))
        // The Worker doesn't know yet (or isn't deployed yet): this phone's mark still counts.
        vm.show(Tab.HOME); advanceUntilIdle()
        assertThat(pill(vm).listenFirst).isFalse()
    }

    @Test fun `a vote answered listen_first dims the pill again and shows the note`() = runTest(dispatcher) {
        every { repo.heardIds } returns emptyFlow()
        coEvery { repo.feed(100) } returns CommunityResult.Ok(listOf(a))
        coEvery { repo.vote(a.id, 1) } returns CommunityResult.Rejected(CommunityRepository.LISTEN_FIRST)
        val vm = CommunityViewModel(repo, mockk(relaxed = true))
        vm.show(Tab.HOME); advanceUntilIdle()
        vm.vote(a, 1); advanceUntilIdle()
        assertThat(pill(vm)).isEqualTo(VotePillUi(2, upLit = false, downLit = false, enabled = true, listenFirst = true, upDimmed = true, downDimmed = true))
        assertThat(vm.state.value.listenFirst).isTrue()
        assertThat(vm.state.value.message).isNull()
    }
}
