package com.stash.core.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The tab bar's icons: Iconsax, "Broken" style (the set Namida's tab bar uses).
 *
 * Path data copied unchanged from iconsax-react 0.0.8 (MIT, github.com/rendinjast/iconsax-react),
 * the React build of the free Iconsax set by vuesax. 24-unit grid, 1.5 stroke, round caps and joins.
 * One glyph serves both states; the tab bar tints it.
 */
object StashNavIcons {

    /** home-2. */
    val Home: ImageVector by lazy {
        brokenIcon(
            "Home",
            "M22 10.498c0-1.21-.81-2.76-1.8-3.45l-6.18-4.33c-1.4-.98-3.65-.93-5 .12l-5.39 4.2c-.9.7-1.63 2.19-1.63 3.32v7.41c0 2.32 1.89 4.22 4.21 4.22h11.58c2.32 0 4.21-1.9 4.21-4.21v-3.1M12 17.988v-3",
        )
    }

    /** music-library-2. */
    val Library: ImageVector by lazy {
        brokenIcon(
            "Library",
            "M2 13c0-2.65 1.15-4.15 3-4.72C5.6 8.09 6.27 8 7 8h10c.73 0 1.4.09 2 .28 1.85.57 3 2.07 3 4.72v4c0 3.5-2 5-5 5H7c-3 0-5-1.5-5-5",
            "M19 7v1.28c-.6-.19-1.27-.28-2-.28H7c-.73 0-1.4.09-2 .28V7c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2ZM16 3.51V5H8V3.51C8 2.68 8.68 2 9.51 2h4.98c.83 0 1.51.68 1.51 1.51Z",
            "M9.07 19.449a1.32 1.32 0 1 0 0-2.64 1.32 1.32 0 0 0 0 2.64Z",
            "M15.248 17.25v-5.17c0-1.1-.69-1.26-1.39-1.06l-2.65.72c-.48.13-.81.51-.81 1.06v5.33",
            "M13.93 18.57a1.32 1.32 0 1 0 0-2.64 1.32 1.32 0 0 0 0 2.64ZM10.398 14.347l4.85-1.32",
        )
    }

    /** search-normal-1. */
    val Search: ImageVector by lazy {
        brokenIcon(
            "Search",
            "M11.5 2c5.25 0 9.5 4.25 9.5 9.5S16.75 21 11.5 21 2 16.75 2 11.5c0-3.7 2.11-6.9 5.2-8.47M22 22l-2-2",
        )
    }

    /** refresh. */
    val Sync: ImageVector by lazy {
        brokenIcon(
            "Sync",
            "M18.01 19.99A9.964 9.964 0 0112 22c-5.52 0-8.89-5.56-8.89-5.56m0 0h4.52m-4.52 0v5M22 12c0 1.82-.49 3.53-1.34 5M6.03 3.97A9.921 9.921 0 0112 2c6.67 0 10 5.56 10 5.56m0 0v-5m0 5h-4.44M2 12c0-1.82.48-3.53 1.33-5",
        )
    }

    /** setting-2: the gear on Namida's top-right settings button. */
    val Settings: ImageVector by lazy {
        brokenIcon(
            "Settings",
            "M15 12c0-1.66-1.34-3-3-3s-3 1.34-3 3a2.996 2.996 0 0 0 4.17 2.76",
            "m6.88 20.58 1.09.63c.79.47 1.81.19 2.28-.6l.11-.19c.9-1.57 2.38-1.57 3.29 0l.11.19c.47.79 1.49 1.07 2.28.6l1.73-.99c.91-.52 1.22-1.69.7-2.59-.91-1.57-.17-2.85 1.64-2.85 1.04 0 1.9-.85 1.9-1.9v-1.76c0-1.04-.85-1.9-1.9-1.9-1.01 0-1.69-.4-1.93-1.03-.19-.49-.11-1.13.29-1.82.52-.91.21-2.07-.7-2.59l-.81-.46M13.64 3.581c-.9 1.57-2.38 1.57-3.29 0l-.11-.19a1.655 1.655 0 0 0-2.27-.6l-1.73.99c-.91.52-1.22 1.69-.7 2.6.91 1.56.17 2.84-1.64 2.84-1.04 0-1.9.85-1.9 1.9v1.76c0 1.04.85 1.9 1.9 1.9 1.81 0 2.55 1.28 1.64 2.85",
        )
    }

    private fun brokenIcon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(
            name = "StashNav.$name",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            paths.forEach { d ->
                addPath(
                    pathData = addPathNodes(d),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                    strokeLineMiter = 10f,
                )
            }
        }.build()
}
