package com.stash.core.media.actions

import com.google.common.truth.Truth.assertThat
import com.stash.core.media.PlayerRepository
import com.stash.core.media.StreamRoutingResult
import com.stash.core.media.preview.PreviewPlayer
import com.stash.core.media.preview.PreviewState
import com.stash.core.model.TrackItem
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Pins the row-level loading spinner for the STREAMING tap path of
 * [TrackActionsDelegate.previewTrack].
 *
 * Bug: when Online/streaming mode is on (the common case), a tap on a Popular /
 * search row routed to full-track playback via `playFromStream` WITHOUT ever
 * setting `previewLoadingId`, so the row showed no spinner during the
 * multi-second resolve (a cold yt-dlp extraction is the worst case) — it
 * looked broken / silent. The preview path already drove the spinner; the
 * streaming path must too.
 *
 * A [CompletableDeferred] gates `playFromStream` so the spinner state is checked
 * deterministically while the resolve is in flight, then after it completes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrackActionsDelegateStreamSpinnerTest {

    private val previewPlayer: PreviewPlayer = mockk(relaxed = true) {
        every { previewState } returns MutableStateFlow(PreviewState.Idle)
        every { playerErrors } returns MutableSharedFlow()
    }
    private val streamingPreference: com.stash.core.data.prefs.StreamingPreference = mockk()
    private val playerRepository: PlayerRepository = mockk()

    private fun delegate() = TrackActionsDelegate(
        previewPlayer = previewPlayer,
        searchPreviewMediaSource = mockk(relaxed = true),
        previewUrlExtractor = mockk(relaxed = true),
        previewUrlCache = mockk(relaxed = true),
        trackDao = mockk(relaxed = true) { io.mockk.coEvery { findByAlias(any(), any()) } returns null },
        searchDownloadCoordinator = mockk(relaxed = true),
        playerRepository = playerRepository,
        streamingPreference = streamingPreference,
        musicRepository = mockk(relaxed = true),
        blocklistGuard = mockk(relaxed = true),
    )

    private fun track(id: String): TrackItem = mockk { every { videoId } returns id }

    @Test
    fun `streaming tap drives the row spinner during resolve then clears it`() = runTest {
        val gate = CompletableDeferred<StreamRoutingResult>()
        coEvery { streamingPreference.current() } returns true
        coEvery { playerRepository.playFromStream(any()) } coAnswers { gate.await() }
        val d = delegate()
        d.bindToScope(backgroundScope)

        d.previewTrack(track("vid123"))
        runCurrent() // launched work sets the loading id, then suspends on the gate

        // Spinner ON for this row while the stream resolves.
        assertThat(d.previewLoadingId.value).isEqualTo("vid123")

        gate.complete(StreamRoutingResult.Item(mockk()))
        runCurrent() // resolve completes -> finally clears the id

        assertThat(d.previewLoadingId.value).isNull()
    }

    @Test
    fun `streaming tap clears the spinner even when routing fails`() = runTest {
        val gate = CompletableDeferred<StreamRoutingResult>()
        coEvery { streamingPreference.current() } returns true
        coEvery { playerRepository.playFromStream(any()) } coAnswers { gate.await() }
        val d = delegate()
        d.bindToScope(backgroundScope)

        d.previewTrack(track("vid999"))
        runCurrent()
        assertThat(d.previewLoadingId.value).isEqualTo("vid999")

        gate.complete(StreamRoutingResult.NotAvailable)
        runCurrent()
        assertThat(d.previewLoadingId.value).isNull()
    }
}
