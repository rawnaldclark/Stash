package com.stash.feature.home.components

import android.webkit.WebView
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Renders the Stash vinyl-on-checkerboard logo using a WebView that loads
 * the exact same CSS used in the browser showcase (variant F).
 *
 * This approach guarantees pixel-perfect rendering of conic gradients,
 * repeating radial gradients, and layered opacity effects that Compose
 * Canvas cannot reproduce.
 *
 * @param size The width and height of the logo container.
 */
@Composable
fun StashVinylLogo(modifier: Modifier = Modifier, size: Dp = 64.dp) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.javaScriptEnabled = false
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = android.view.View.OVER_SCROLL_NEVER

                loadDataWithBaseURL(
                    null,
                    VINYL_LOGO_HTML,
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
        },
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp)),
    )
}

/**
 * The exact CSS from the localhost showcase variant F.
 * This is the single source of truth for the Stash logo.
 */
private val VINYL_LOGO_HTML = """
<!DOCTYPE html>
<html><head><meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
<style>
*{margin:0;padding:0;box-sizing:border-box}
html,body{width:100%;height:100%;overflow:hidden;background:transparent}
.icon{width:100%;height:100%;position:relative;overflow:hidden;display:flex;align-items:center;justify-content:center;background:linear-gradient(140deg,#3b0764,#6D28D9);border-radius:0}
.icon .board{position:absolute;inset:0;display:grid;grid-template-columns:repeat(3,1fr);grid-template-rows:repeat(3,1fr);opacity:0.5}
.icon .board div:nth-child(2),.icon .board div:nth-child(4),.icon .board div:nth-child(6),.icon .board div:nth-child(8){background:#A78BFA}
.icon .record-wrap{position:relative;z-index:2;margin-top:-3%}
.icon .record{width:63%;height:0;padding-bottom:63%;border-radius:50%;position:relative;overflow:hidden;box-shadow:0 4px 20px rgba(0,0,0,0.6),0 0 0 2px rgba(167,139,250,0.12)}
.icon .record .base{position:absolute;inset:0;border-radius:50%;background:radial-gradient(circle at center,#333 33%,#111 34%,#1a1a1a 100%)}
.icon .record .rim{position:absolute;inset:0;border-radius:50%;box-shadow:inset 0 0 0 2px rgba(255,255,255,0.06)}
.icon .record .groove-texture{position:absolute;inset:2%;border-radius:50%;background:conic-gradient(from 0deg,rgba(40,40,40,1) 0deg,rgba(50,50,50,1) 15deg,rgba(35,35,35,1) 30deg,rgba(48,48,48,1) 45deg,rgba(38,38,38,1) 60deg,rgba(52,52,52,1) 75deg,rgba(36,36,36,1) 90deg,rgba(46,46,46,1) 105deg,rgba(34,34,34,1) 120deg,rgba(50,50,50,1) 135deg,rgba(38,38,38,1) 150deg,rgba(48,48,48,1) 165deg,rgba(35,35,35,1) 180deg,rgba(52,52,52,1) 195deg,rgba(36,36,36,1) 210deg,rgba(46,46,46,1) 225deg,rgba(34,34,34,1) 240deg,rgba(50,50,50,1) 255deg,rgba(40,40,40,1) 270deg,rgba(48,48,48,1) 285deg,rgba(36,36,36,1) 300deg,rgba(52,52,52,1) 315deg,rgba(38,38,38,1) 330deg,rgba(46,46,46,1) 345deg,rgba(40,40,40,1) 360deg);animation:spin 6s linear infinite}
.icon .record .ring-grooves{position:absolute;inset:0;border-radius:50%;background:repeating-radial-gradient(circle at center,transparent 0px,transparent 2px,rgba(255,255,255,0.04) 2.5px,transparent 3px,transparent 4.5px,rgba(255,255,255,0.03) 5px,transparent 5.5px);animation:spin 6s linear infinite}
.icon .record .dead-wax{position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);width:43%;height:43%;border-radius:50%;background:radial-gradient(circle,transparent 48%,rgba(255,255,255,0.04) 49%,rgba(255,255,255,0.02) 55%,transparent 56%);animation:spin 6s linear infinite}
.icon .record .label-circle{position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);width:34%;height:34%;border-radius:50%;background:linear-gradient(135deg,#A78BFA,#7C3AED);display:flex;align-items:center;justify-content:center;z-index:2;font-weight:800;color:#fff;font-family:Georgia,serif;font-size:22%;box-shadow:inset 0 1px 2px rgba(0,0,0,0.3),0 0 0 1px rgba(255,255,255,0.1)}
.icon .record .shine{position:absolute;inset:0;border-radius:50%;background:linear-gradient(150deg,rgba(255,255,255,0.12) 0%,rgba(255,255,255,0.04) 20%,transparent 45%,transparent 70%,rgba(255,255,255,0.02) 100%);z-index:3}
.icon .record .refraction{position:absolute;inset:9%;border-radius:50%;background:conic-gradient(from 200deg,transparent 0deg,rgba(139,92,246,0.06) 30deg,transparent 60deg,rgba(99,102,241,0.04) 120deg,transparent 150deg,rgba(139,92,246,0.05) 240deg,transparent 280deg);z-index:2;animation:spin 6s linear infinite}
@keyframes spin{to{transform:rotate(360deg)}}
</style></head>
<body>
<div class="icon">
  <div class="board"><div></div><div></div><div></div><div></div><div></div><div></div><div></div><div></div><div></div></div>
  <div class="record-wrap"><div class="record">
    <div class="base"></div><div class="rim"></div><div class="groove-texture"></div>
    <div class="ring-grooves"></div><div class="dead-wax"></div><div class="refraction"></div>
    <div class="label-circle">S</div><div class="shine"></div>
  </div></div>
</div>
</body></html>
""".trimIndent()
