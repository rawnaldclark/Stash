package com.stash.core.media.listen

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.stash.core.media.listen.ListenTogetherController.Command
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListenTogetherControllerTest {
    private val context: Context = mockk(relaxed = true)

    @Test fun `a command sent while no service is running starts the service and waits in line`() {
        val controller = ListenTogetherController(context)
        controller.send(Command.Join("K7QA2PXM"))
        verify(exactly = 1) { context.startService(any()) }
        assertThat(controller.commands.tryReceive().getOrNull()).isEqualTo(Command.Join("K7QA2PXM"))
        controller.serviceAttached = true
        controller.send(Command.Leave)
        verify(exactly = 1) { context.startService(any()) }
    }

    @Test fun `the end of a session is announced once, however fast it was`() = runTest {
        val controller = ListenTogetherController(context)
        val ends = mutableListOf<Unit>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { controller.sessionEnds.collect { ends += it } }
        controller.setActive(true)
        controller.setActive(false) // too fast for a StateFlow collector to have seen `true`
        controller.setActive(false)
        assertThat(ends).hasSize(1)
        assertThat(controller.restorePending).isTrue() // until PlayerRepositoryImpl has the user's queue back
        job.cancel()
    }

    @Test fun `a quiet end (the service shutting down) neither announces nor waits for a restore`() = runTest {
        val controller = ListenTogetherController(context)
        val ends = mutableListOf<Unit>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { controller.sessionEnds.collect { ends += it } }
        controller.setActive(true)
        controller.setActive(false, restore = false)
        assertThat(controller.active.value).isFalse()
        assertThat(ends).isEmpty()
        assertThat(controller.restorePending).isFalse()
        job.cancel()
    }
}
