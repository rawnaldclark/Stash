package com.stash.core.ui.icons

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.PathParser

/*
 * The tab glyphs' shapes: the prototype's SVG path data, verbatim, in its 24-unit viewBox
 * (stash-tab-bar-v2.html, `GLYPHS`). Circles and rounded rects are geometry in the draw code
 * (StashTabGlyphs.kt).
 */

/** The SVG viewBox size every glyph is drawn in. */
internal const val GLYPH_GRID = 24f

/** The glyphs' SVG path data (24-unit grid). */
internal object TabGlyphPathData {
    const val HOUSE =
        "M4.6 10 11 4.7a1.6 1.6 0 0 1 2 0l6.4 5.3c.4.3.6.8.6 1.3v7.9a1.6 1.6 0 0 1-1.6 1.6H5.6A1.6 1.6 0 0 1 4 19.2v-7.9c0-.5.2-1 .6-1.3z"

    /** The door, cut out of the solid house (the mask's black path). */
    const val HOME_DOOR_CUT = "M9.9 21v-4.4a2.1 2.1 0 0 1 4.2 0V21z"

    /** The door's outline, drawn on top. */
    const val HOME_DOOR = "M9.9 20.6v-4a2.1 2.1 0 0 1 4.2 0v4"

    /** The groove on the record. */
    const val LIBRARY_GROOVE = "M15 8.1a3.9 3.9 0 0 1 3.9 3.9"

    /** The label line on the sleeve (drawn at 1.5, cut out of the solid sleeve at 1.7). */
    const val LIBRARY_LABEL = "M6.2 15.6h4.4"

    /** The glint on the lens, cut out of the solid lens. */
    const val SEARCH_GLINT = "M7.3 9.4a3.5 3.5 0 0 1 2.5-2.6"
    const val SEARCH_HANDLE = "M15.3 15.3 20 20"

    /** The two arcs and two arrowheads of the sync loop. */
    val SYNC_LOOP = listOf(
        "M19.3 9.4A7.8 7.8 0 0 0 5.6 7.2",
        "M19.7 4.8v4.7H15",
        "M4.7 14.6a7.8 7.8 0 0 0 13.7 2.2",
        "M4.3 19.2v-4.7H9",
    )

    /** The three fader tracks. */
    const val SETTINGS_TRACKS = "M6 4v16M12 4v16M18 4v16"

    /** Every path string, for the parse test. */
    val ALL: List<String> = listOf(
        HOUSE, HOME_DOOR_CUT, HOME_DOOR, LIBRARY_GROOVE, LIBRARY_LABEL, SEARCH_GLINT, SEARCH_HANDLE,
        SETTINGS_TRACKS,
    ) + SYNC_LOOP
}

/** The parsed paths, built once on first draw and shared by every glyph (they are only read). */
internal object TabGlyphPaths {
    private fun parse(d: String): Path = PathParser().parsePathString(d).toPath()

    val house by lazy { parse(TabGlyphPathData.HOUSE) }
    val doorCut by lazy { parse(TabGlyphPathData.HOME_DOOR_CUT) }
    val door by lazy { parse(TabGlyphPathData.HOME_DOOR) }
    val groove by lazy { parse(TabGlyphPathData.LIBRARY_GROOVE) }
    val label by lazy { parse(TabGlyphPathData.LIBRARY_LABEL) }
    val glint by lazy { parse(TabGlyphPathData.SEARCH_GLINT) }
    val handle by lazy { parse(TabGlyphPathData.SEARCH_HANDLE) }
    val syncLoop by lazy { TabGlyphPathData.SYNC_LOOP.map(::parse) }
    val tracks by lazy { parse(TabGlyphPathData.SETTINGS_TRACKS) }
}
