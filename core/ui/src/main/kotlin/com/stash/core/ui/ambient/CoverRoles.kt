package com.stash.core.ui.ambient

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * The colour roles of the cover at [model] (a file path or URL) on the current ground, for a card
 * that wears its own cover's colour (the Discover hero) rather than the playing song's
 * ([LocalAlbumChrome]). The brand's roles until the cover has been read, and whenever it can't be;
 * a cover read before (it's in [PaletteCache]) starts in its own colour, so swiping back to a card
 * never flashes purple.
 */
@Composable
fun rememberCoverRoles(model: String?, ground: Ground = currentGround()): AccentRoles {
    val context = LocalContext.current
    var roles by remember(model, ground) {
        mutableStateOf(model?.let(PaletteCache::peek)?.roles(ground) ?: brandRoles(ground))
    }
    LaunchedEffect(model, ground) {
        val palette = model?.let { PaletteCache.load(context, it) }
        roles = palette?.roles(ground) ?: brandRoles(ground)
    }
    return roles
}
