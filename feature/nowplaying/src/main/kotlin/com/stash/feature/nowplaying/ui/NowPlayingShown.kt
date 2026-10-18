package com.stash.feature.nowplaying.ui

import androidx.compose.runtime.compositionLocalOf

/**
 * Whether Now Playing is on screen. False while the player sheet keeps it composed but parked
 * under the mini player (the 30 s after it closes, or after a touch on the mini player): every
 * per-frame clock inside it (the title's marquee, the lyrics' smooth position clock, the syllable
 * springs) runs only while this is true, so a parked Now Playing costs no frames. True outside the
 * sheet. The sheet changes it only as the player leaves or reaches MINI, so only its readers
 * recompose, and only then.
 */
internal val LocalNowPlayingShown = compositionLocalOf { true }
