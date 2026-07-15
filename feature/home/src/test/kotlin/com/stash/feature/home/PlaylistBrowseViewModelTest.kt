package com.stash.feature.home

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.discovery.HomeDiscoveryRepository
import com.stash.data.ytmusic.model.PlaylistSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyBlocking

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistBrowseViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun pl(id: String) = PlaylistSummary(id, "P$id", "Qobuz", null, 10)
    private fun page(prefix: String, n: Int) = List(n) { pl("$prefix$it") }

    private fun vm(repo: HomeDiscoveryRepository, genre: String = "All") =
        PlaylistBrowseViewModel(SavedStateHandle(mapOf("genre" to genre)), repo)

    @Test fun `loads the first page on init`() = runTest {
        val repo = mock<HomeDiscoveryRepository> {
            onBlocking { browsePlaylists(anyOrNull(), eq(0), any()) } doReturn page("a", 30)
        }
        val vm = vm(repo); advanceUntilIdle()
        assertThat(vm.state.value.playlists).hasSize(30)
        assertThat(vm.state.value.endReached).isFalse()
    }

    @Test fun `loadMore appends the next page and a short page marks the end`() = runTest {
        val repo = mock<HomeDiscoveryRepository> {
            onBlocking { browsePlaylists(anyOrNull(), eq(0), any()) } doReturn page("a", 30)
            onBlocking { browsePlaylists(anyOrNull(), eq(30), any()) } doReturn page("b", 5)
        }
        val vm = vm(repo); advanceUntilIdle()

        vm.loadMore(); advanceUntilIdle()

        assertThat(vm.state.value.playlists).hasSize(35)
        assertThat(vm.state.value.endReached).isTrue()
    }

    @Test fun `does not page past the end`() = runTest {
        val repo = mock<HomeDiscoveryRepository> {
            onBlocking { browsePlaylists(anyOrNull(), eq(0), any()) } doReturn page("a", 3) // short → end
        }
        val vm = vm(repo); advanceUntilIdle()

        vm.loadMore(); advanceUntilIdle()   // no-op, already ended

        verifyBlocking(repo, org.mockito.kotlin.times(1)) { browsePlaylists(anyOrNull(), any(), any()) }
    }

    @Test fun `resolves the genre label to a Qobuz genre id`() = runTest {
        val repo = mock<HomeDiscoveryRepository> {
            onBlocking { browsePlaylists(anyOrNull(), any(), any()) } doReturn emptyList()
        }
        vm(repo, genre = "Metal"); advanceUntilIdle()
        verifyBlocking(repo) { browsePlaylists(eq(116), eq(0), any()) }   // Metal = 116
    }
}
