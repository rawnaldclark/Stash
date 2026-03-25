package com.stash.core.ui.components

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stash.core.ui.theme.StashTheme

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    blurRadius: Dp = 24.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val extendedColors = StashTheme.extendedColors
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .then(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Modifier.graphicsLayer {
                        renderEffect = RenderEffect
                            .createBlurEffect(blurRadius.toPx(), blurRadius.toPx(), android.graphics.Shader.TileMode.CLAMP)
                            .asComposeRenderEffect()
                    }
                } else Modifier
            ),
        color = extendedColors.glassBackground,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, extendedColors.glassBorder),
    ) {
        Box(modifier = Modifier.padding(16.dp), content = content)
    }
}
