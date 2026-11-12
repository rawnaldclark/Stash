package com.stash.core.media.preview

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.TrackItem
import com.stash.data.download.lossless.LosslessAvailability
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.LosslessSourceRegistry
import com.stash.data.download.lossless.addon.NoAddonSources
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The foreground (tap) [LosslessUrlPrefetcher.lookup] must resolve with
 * `bypassRateLimit = true` so a preview isn't throttled.
 *
 * [LosslessUrlPrefetcher.warmUp] spends nothing at all: its only reader is the
 * unreachable preview path, so a warm-up was a search and a signed file-URL
 * request against the user's own Qobuz account that nothing ever read.
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

    /**
     * review-882 B1: the preview plays its match on a plain cached HTTP source, so its lookup must
     * reach no add-on. The marker is what every add-on source obeys (`AddonLosslessSource`, proven
     * in `:data:download`'s `AddonNoSourcesTest`).
     */
    @Test fun `a preview's lookup is marked to ask no add-on`() = runTest {
        var marked: Boolean? = null
        coEvery { registry.resolve(any(), any()) } coAnswers {
            marked = kotlinx.coroutines.currentCoroutineContext()[NoAddonSources.Key] != null
            null
        }

        LosslessUrlPrefetcher(registry, availability, losslessPrefs).lookup(item)

        assertThat(marked).isTrue()
    }

    @Test fun `the preview query carries the album like a download does`() = runTest {
        coEvery { availability.ownAccountLiveNow() } returns false
        coEvery { registry.resolve(any(), any()) } returns null

        LosslessUrlPrefetcher(registry, availability, losslessPrefs).lookup(item.copy(album = "Alive!"))

        coVerify { registry.resolve(match { it.album == "Alive!" }, any()) }
    }

    @Test fun `warmUp spends nothing even when the user's own account is live`() = runBlocking {
        coEvery { availability.ownAccountLiveNow() } returns true
        coEvery { registry.resolve(any(), any()) } returns null

        // Fifteen rows of an album page entering composition.
        val prefetcher = LosslessUrlPrefetcher(registry, availability, losslessPrefs)
        repeat(15) { prefetcher.warmUp(item.copy(videoId = "v$it")) }

        delay(300) // real time: any warm-up would run on the prefetcher's own IO scope
        coVerify(exactly = 0) { registry.resolve(any(), any()) }
    }

    @Test fun `warmUp spends nothing when the user's own account is not live`() = runBlocking {
        coEvery { availability.ownAccountLiveNow() } returns false
        coEvery { registry.resolve(any(), any()) } returns null

        LosslessUrlPrefetcher(registry, availability, losslessPrefs).warmUp(item)

        // runBlocking, not runTest: delay() must spend REAL time here, because
        // warmUp runs on the prefetcher's own IO scope, outside any test scheduler.
        // Under runTest the delay skips instantly and the race is decided by the
        // runner's speed (the CI-only failures of this class).
        delay(300) // give the IO-dispatched warmUp every chance to (wrongly) resolve
        coVerify(exactly = 0) { registry.resolve(any(), any()) }
    }

    @Test fun `a skipped warmUp does not poison the next tap`() = runBlocking {
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

    /**
     * The CI flake, made deterministic. Unconfined runs the warm-up body the
     * moment it is launched, BEFORE warmUp has stored its cache entry: the skip
     * path's remove-by-key finds nothing, the entry lands afterwards as a
     * completed null, and the next tap reads it instead of resolving. That is
     * the "poison" the real-time test only caught on a slow runner.
     */
    @Test fun `a warm-up that skips before its entry is stored still leaves the tap free`() = runTest {
        coEvery { availability.ownAccountLiveNow() } returns false
        coEvery { registry.resolve(any(), any()) } returns null
        val prefetcher = LosslessUrlPrefetcher(registry, availability, losslessPrefs, Dispatchers.Unconfined)

        prefetcher.warmUp(item)
        prefetcher.lookup(item)

        coVerify(exactly = 1) { registry.resolve(any(), bypassRateLimit = true) }
    }
}

