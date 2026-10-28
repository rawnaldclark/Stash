package com.stash.feature.record.mixes

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.RecordTasteDao
import com.stash.feature.record.archive.RecordArchiveSource
import com.stash.feature.record.art.Found
import com.stash.feature.record.art.MemoryArtStore
import com.stash.feature.record.art.RecordArt
import com.stash.feature.record.bridge.RecordPlayback
import com.stash.feature.record.imports.MemoryPrefs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.EmptyCoroutineContext

/** A mix's page: a record that can't be read says so (never a blank page, never "gone"), and a mix saved before opens its playlist. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RecordMixViewModelTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun reset() = Dispatchers.resetMain()

    private fun viewModel(dao: RecordTasteDao, saves: RecordMixSaves, names: Map<Long, String> = emptyMap()) = RecordMixViewModel(
        savedState = SavedStateHandle(mapOf(RecordMixViewModel.ID to "constants")),
        mixes = RecordMixes(
            tastes = dao,
            between = { _, _ -> emptyList() },
            own = RecordArchiveSource { _, emit -> emit(emptyList(), true) },
            library = { emptyList() },
            libraryStamp = flowOf(Unit),
            io = EmptyCoroutineContext,
        ),
        art = RecordArt(lookUp = { Found.Cover(null) }, store = MemoryArtStore(), now = { 0L }, work = EmptyCoroutineContext),
        playback = RecordPlayback(
            match = { songs -> songs.map { null } },
            setQueue = { _, _, _ -> },
            online = { true },
            toast = {},
            scope = CoroutineScope(SupervisorJob()),
        ),
        saver = RecordMixSaver(rows = { songs -> songs.map { null } }, create = { _, _ -> 0L }, nameOf = { names[it] }),
        saves = saves,
    ).also { it.useWords("en-US", ResourceMixWords(context)) }

    private fun dao(read: () -> Long?): RecordTasteDao = mockk(relaxed = true) {
        coEvery { computedAt() } answers { read() }
        every { computedAtFlow() } returns flowOf(1L)
        every { keepKeysFlow() } returns flowOf(emptyList())
    }

    @Test fun `a record that can't be read says so, and Try again reads it again`() = runTest {
        var broken = true
        val vm = viewModel(dao { if (broken) error("database locked") else null }, RecordMixSaves(MemoryPrefs()))
        assertThat(vm.state.value).isEqualTo(RecordMixViewModel.State.Failed)
        broken = false
        vm.retry()
        // read now: no taste, so the mix is gone (the record says so, with the way back)
        assertThat(vm.state.value).isEqualTo(RecordMixViewModel.State.Gone)
    }

    @Test fun `a mix saved before opens its playlist while it is in the library, never a second save`() = runTest {
        val saves = RecordMixSaves(MemoryPrefs()).apply { remember("constants", 7L) }
        assertThat(viewModel(dao { null }, saves, mapOf(7L to "The ones that stayed")).saved.value)
            .isEqualTo(RecordMixViewModel.Saved(7L, "The ones that stayed"))
        // the playlist deleted since: Save as playlist again
        assertThat(viewModel(dao { null }, saves, emptyMap()).saved.value).isNull()
        // another mix: nothing remembered
        assertThat(saves.playlistOf("year:2019")).isNull()
    }
}
