package com.stash.feature.nowplaying.listen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.nowplaying.ui.FullArtTheme
import com.stash.feature.nowplaying.ui.NpInkLight
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The React button's icon, read from its drawn pixels (an icon's tint isn't in the semantics tree). A content colour no
 * theme would use stands in for whatever is above the player: the icon must wear Now Playing's ink, never that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReactionButtonInkTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val contentColor = Color(0xFF123456)
    private val ground = Color(0xFF808080)

    private var appDark by mutableStateOf(true)
    private var fullArt by mutableStateOf(false)

    private fun show() {
        rule.setContent {
            StashTheme(darkTheme = appDark) {
                CompositionLocalProvider(LocalContentColor provides contentColor) {
                    FullArtTheme(on = fullArt) {
                        Box(Modifier.background(ground).padding(8.dp)) { ReactionButton(onReact = {}) }
                    }
                }
            }
        }
    }

    /** Of the icon's pixels, the one furthest from the ground: its strokes are wide enough to have fully covered ones. */
    private fun iconInk(): Int {
        rule.waitForIdle()
        val b = rule.onNodeWithContentDescription("React", useUnmergedTree = true).getBoundsInRoot()
        val d = rule.density.density
        lateinit var bmp: Bitmap
        rule.runOnUiThread {
            val view = rule.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bmp))
        }
        val g = ground.toArgb()
        fun dist(p: Int) = (0..2).sumOf { abs((p shr (it * 8) and 255) - (g shr (it * 8) and 255)) }
        var best = g
        for (y in (b.top.value * d).toInt() until (b.bottom.value * d).toInt()) {
            for (x in (b.left.value * d).toInt() until (b.right.value * d).toInt()) {
                val p = bmp.getPixel(x, y)
                if (dist(p) > dist(best)) best = p
            }
        }
        return best
    }

    private fun assertInk(expected: Color) {
        val want = expected.toArgb()
        val got = iconInk()
        val close = (0..2).all { abs((got shr (it * 8) and 255) - (want shr (it * 8) and 255)) <= 2 }
        assertTrue("icon ink #%06X, expected #%06X".format(got and 0xFFFFFF, want and 0xFFFFFF), close)
    }

    @Test
    fun `in the normal layout the icon is white in a dark theme and plum-black in a light one`() {
        show()
        assertInk(Color.White)
        appDark = false
        assertInk(NpInkLight)
    }

    @Test
    fun `on Full art it's white in a light app too`() {
        appDark = false
        fullArt = true
        show()
        assertInk(Color.White)
    }
}
