package com.stash.feature.search

import com.stash.data.download.preview.PreviewUrlExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Unit tests for [PreviewPrefetcher].
 *
 * The production default uses `Dispatchers.IO`; for determinism these tests
 * inject a [CoroutineScope] backed by [StandardTestDispatcher] tied to the
 * current `runTest` scheduler so `advanceUntilIdle()` can drain every launched
 * prefetch job.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PreviewPrefetcherTest {

    @Test
    fun `prefetch calls extractStreamUrl once per id and populates cache`() = runTest {
        val ex = mock<PreviewUrlExtractor> {
            onBlocking { extractStreamUrl(any()) }
                .thenAnswer { inv -> "u/${inv.arguments[0]}" }
        }
        val cache = mutableMapOf<String, String>()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val pf = PreviewPrefetcher(ex, cache, scope)

        pf.prefetch(listOf("a", "b", "c"))
        advanceUntilIdle()

        verify(ex, times(3)).extractStreamUrl(any())
        assertEquals("u/a", cache["a"])
        assertEquals("u/b", cache["b"])
        assertEquals("u/c", cache["c"])
    }

    @Test
    fun `prefetch skips ids already in cache`() = runTest {
        val ex = mock<PreviewUrlExtractor> {
            onBlocking { extractStreamUrl(any()) }
                .thenAnswer { inv -> "u/${inv.arguments[0]}" }
        }
        val cache = mutableMapOf("a" to "u/a")
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val pf = PreviewPrefetcher(ex, cache, scope)

        pf.prefetch(listOf("a", "b"))
        advanceUntilIdle()

        verify(ex, never()).extractStreamUrl(eq("a"))
        verify(ex).extractStreamUrl(eq("b"))
    }
}
