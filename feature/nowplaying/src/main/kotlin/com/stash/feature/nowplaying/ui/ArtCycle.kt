package com.stash.feature.nowplaying.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay

/** How long the front holds before the first page, and then each page: slow enough to look at. */
private const val FRONT_HOLD_MS = 12_000L
private const val PAGE_HOLD_MS = 7_000L

/** The crossfade from one page to the next. */
private const val CYCLE_FADE_MS = 1_400

/**
 * Now Playing's art turning slowly through the album's own pages (Settings > Appearance > Cycle
 * album art): the front, then the back and the pages inside, and round to the front again, with a
 * slow crossfade between each, never a slide or a flip. A touch on the art holds the page shown for
 * another full hold. The next page is loaded before it is faded to; one that can't load is skipped.
 *
 * @param pageCount The pages after the front (the back and the inside).
 * @param index The page shown: 0 is the front.
 * @param running Whether it turns: not while the back is turned up by hand, a page is held up full
 *   screen, or Now Playing is out of sight.
 * @param snapKey A change shows [index] at once, without the fade (the art turned over by hand).
 * @param load Loads page n (1 and up) before it is shown; false when it can't.
 * @param page Draws page n.
 */
@Composable
internal fun CyclingArt(
    pageCount: Int,
    index: Int,
    onIndexChange: (Int) -> Unit,
    running: Boolean,
    snapKey: Any?,
    load: suspend (Int) -> Boolean,
    modifier: Modifier = Modifier,
    page: @Composable (Int) -> Unit,
) {
    var touches by remember { mutableIntStateOf(0) }
    val currentIndex by rememberUpdatedState(index)
    val currentOnIndexChange by rememberUpdatedState(onIndexChange)
    val currentLoad by rememberUpdatedState(load)
    LaunchedEffect(running, pageCount, touches) {
        if (!running || pageCount == 0) return@LaunchedEffect
        while (true) {
            delay(if (currentIndex == 0) FRONT_HOLD_MS else PAGE_HOLD_MS)
            // The next page that loads; the front always does, so a round of failures ends there.
            var next = currentIndex
            for (step in 0..pageCount) {
                next = (next + 1) % (pageCount + 1)
                if (next == 0 || currentLoad(next)) break
            }
            currentOnIndexChange(next)
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            // Watches touches without taking them: the art's own gestures still get each one.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    touches++
                }
            },
    ) {
        key(snapKey) {
            Crossfade(
                targetState = index.coerceIn(0, pageCount),
                animationSpec = tween(CYCLE_FADE_MS, easing = FastOutSlowInEasing),
                label = "artCycle",
            ) { shown -> page(shown) }
        }
    }
}
