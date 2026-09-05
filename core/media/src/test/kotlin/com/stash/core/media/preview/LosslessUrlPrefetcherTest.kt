package com.stash.core.media.preview

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.TrackItem
import com.stash.data.download.lossless.LosslessAvailability
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.LosslessSourceRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The foreground (tap) [LosslessUrlPrefetcher.lookup] must resolve with
 * `bypassRateLimit = true` so a preview isn't throttled behind speculative
 * background prefetches, while the background [LosslessUrlPrefetcher.warmUp]
 * stays rate-limited (`bypassRateLimit = false`).
 *
 * warmUp is speculative: a row the user may never tap. It runs only when the
 * user's OWN Qobuz account is live, because every other lossless path is a
 * shared, capped budget (the relay's per-account caps, ARCOD's daily quota)
 * that browsing must not spend. A tap still resolves either way.
 */
class LosslessUrlPrefetcherTest {
    private val registry: LosslessSourceRegistry = mockk(relaxed = true)
    private val availability: LosslessAvailability = mockk()
    private val losslessPrefs: LosslessSourcePreferences = mockk {
        coEvery { enabledNow() } returns true // the Lossless switch, on by default
    }
    private val item = TrackItem(
        videoId = "v1", title = "t", artist = "a", durationSeconds = 180.0, thumbnailUrl = null,
    )

    @Test fun `cold lookup resolves with bypassRateLimit true`() = runTest {
        coEvery { availability.ownAccountLiveNow() } returns false
        coEvery { registry.resolve(any(), any()) } returns null

        LosslessUrlPrefetcher(registry, availability, losslessPrefs).lookup(item)

        // lookup awaits its own resolve, so the call has definitely landed here.
        coVerify { registry.resolve(any(), bypassRateLimit = true) }
    }

    @Test fun `warmUp resolves with bypassRateLimit false when the user's own account is live`() = runTest {
        coEvery { availability.ownAccountLiveNow() } returns true
        coEvery { registry.resolve(any(), any()) } returns null

        LosslessUrlPrefetcher(registry, availability, losslessPrefs).warmUp(item)

        // warmUp dispatches onto the prefetcher's own Dispatchers.IO scope (NOT the
        // runTest scheduler), so poll with a timeout rather than racing a bare verify.
        coVerify(timeout = 1000) { registry.resolve(any(), bypassRateLimit = false) }
    }

    @Test fun `warmUp spends nothing when the user's own account is not live`() = runTest {
        coEvery { availability.ownAccountLiveNow() } returns false
        coEvery { registry.resolve(any(), any()) } returns null

        LosslessUrlPrefetcher(registry, availability, losslessPrefs).warmUp(item)

        delay(300) // give the IO-dispatched warmUp every chance to (wrongly) resolve
        coVerify(exactly = 0) { registry.resolve(any(), any()) }
    }

    @Test fun `a skipped warmUp does not poison the next tap`() = runTest {
        coEvery { availability.ownAccountLiveNow() } returns false
        coEvery { registry.resolve(any(), any()) } returns null
        val prefetcher = LosslessUrlPrefetcher(registry, availability, losslessPrefs)

        prefetcher.warmUp(item)
        delay(300)
        prefetcher.lookup(item)

        // The tap must still reach the registry — a cached "skipped" result would
        // otherwise answer null instantly and the row would fall to YouTube.
        coVerify(exactly = 1) { registry.resolve(any(), bypassRateLimit = true) }
    }

    /** The Lossless switch governs the search preview too: off means the preview is YouTube, never FLAC. */
    @Test fun `lookup resolves nothing when lossless is off`() = runTest {
        coEvery { losslessPrefs.enabledNow() } returns false
        coEvery { availability.ownAccountLiveNow() } returns true
        assertThat(LosslessUrlPrefetcher(registry, availability, losslessPrefs).lookup(item)).isNull()
        coVerify(exactly = 0) { registry.resolve(any(), any()) }
    }

    @Test fun `warmUp spends nothing when lossless is off`() = runTest {
        coEvery { losslessPrefs.enabledNow() } returns false
        coEvery { availability.ownAccountLiveNow() } returns true
        LosslessUrlPrefetcher(registry, availability, losslessPrefs).warmUp(item)
        delay(300)
        coVerify(exactly = 0) { registry.resolve(any(), any()) }
    }
}
