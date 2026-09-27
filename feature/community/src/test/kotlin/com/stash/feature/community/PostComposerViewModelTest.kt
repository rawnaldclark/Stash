package com.stash.feature.community

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.community.CommunityRepository
import com.stash.core.data.community.CommunityResult
import com.stash.core.data.community.Draft
import com.stash.core.model.community.CommunityMe
import com.stash.core.model.community.PostTarget
import com.stash.core.model.share.SharedTrack
import com.stash.feature.community.PostComposerViewModel.UiState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PostComposerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<CommunityRepository>(relaxed = true)
    private val target = PostTarget.Playlist(1L)
    private val draft = Draft.Ready("playlist", "sad boy hours", emptyList(), List(42) { SharedTrack("T$it", "A") })

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.draft(target) } returns draft
        coEvery { repo.displayName() } returns "Maya"
        coEvery { repo.me() } returns CommunityResult.Ok(CommunityMe(postsLeftToday = 2, spotsFree = 4, blocked = false))
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun TestScope.opened() = PostComposerViewModel(repo).also { it.open(target); advanceUntilIdle() }

    @Test fun `opening reads the draft, your saved name and your limits`() = runTest(dispatcher) {
        assertThat(opened().state.value).isEqualTo(UiState.Ready(draft, "Maya", CommunityMe(2, 4, false)))
    }

    @Test fun `an empty playlist can't be posted`() = runTest(dispatcher) {
        coEvery { repo.draft(target) } returns Draft.Problem("This playlist is empty.")
        assertThat(opened().state.value).isEqualTo(UiState.Problem("This playlist is empty."))
    }

    @Test fun `a blank name asks for one and sends nothing`() = runTest(dispatcher) {
        val vm = opened()
        vm.post("  ") {}
        advanceUntilIdle()
        assertThat((vm.state.value as UiState.Ready).error).isEqualTo("Add the name to show on your post.")
        coVerify(exactly = 0) { repo.post(any(), any()) }
    }

    @Test fun `a post goes out under the given name and closes the sheet`() = runTest(dispatcher) {
        coEvery { repo.post(draft, "Maya") } returns CommunityResult.Ok("AAAAAAAA")
        val vm = opened()
        var closed = false
        vm.post("Maya") { closed = true }
        advanceUntilIdle()
        assertThat(closed).isTrue()
    }

    @Test fun `a refusal stays on the sheet with its reason, and live_limit offers your posts`() = runTest(dispatcher) {
        val vm = opened()
        coEvery { repo.post(any(), any()) } returns CommunityResult.Rejected("live_limit")
        vm.post("Maya") {}; advanceUntilIdle()
        (vm.state.value as UiState.Ready).let {
            assertThat(it.error).isEqualTo("You have 5 posts up. Take one down to post again.")
            assertThat(it.seeMyPosts).isTrue()
            assertThat(it.posting).isFalse()
        }
        coEvery { repo.post(any(), any()) } returns CommunityResult.Rejected("daily_limit")
        vm.post("Maya") {}; advanceUntilIdle()
        (vm.state.value as UiState.Ready).let {
            assertThat(it.error).isEqualTo("You've posted twice today. Try again tomorrow.")
            assertThat(it.seeMyPosts).isFalse()
        }
    }

    @Test fun `the frozen-copy note is the spec's sentence, and one song reads as one`() {
        assertThat(frozenNote(draft)).isEqualTo("Everyone with Community turned on will see these 42 songs exactly as they are now. Later changes to your playlist won't show. It stays up for 30 days, and you can take it down any time.")
        assertThat(frozenNote(draft.copy(kind = "mix", tracks = draft.tracks.take(1)))).startsWith("Everyone with Community turned on will see this song exactly as it is now. Later changes to your mix won't show.")
    }

    @Test fun `a second tap while posting sends one post`() = runTest(dispatcher) {
        coEvery { repo.post(any(), any()) } coAnswers { delay(1_000); CommunityResult.Ok("AAAAAAAA") }
        val vm = opened(); vm.post("Maya") {}; vm.post("Maya") {}; advanceUntilIdle()
        coVerify(exactly = 1) { repo.post(any(), any()) }
    }

    @Test fun `closing drops the opening, so a late reply can't fill the next one`() = runTest(dispatcher) {
        coEvery { repo.me() } coAnswers { delay(1_000); CommunityResult.Ok(CommunityMe(2, 4, false)) }
        val vm = PostComposerViewModel(repo).also { it.open(target); runCurrent() }
        vm.reset(); advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(UiState.Loading)
    }

    @Test fun `closing cancels a draft still loading`() = runTest(dispatcher) {
        coEvery { repo.draft(target) } coAnswers { delay(1_000); draft }
        val vm = PostComposerViewModel(repo).also { it.open(target); runCurrent() }
        vm.reset(); advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(UiState.Loading)
    }

    @Test fun `limits that can't be read leave the sheet usable`() = runTest(dispatcher) {
        coEvery { repo.me() } returns CommunityResult.Failed(null)
        assertThat((opened().state.value as UiState.Ready).me).isNull()
    }
}
