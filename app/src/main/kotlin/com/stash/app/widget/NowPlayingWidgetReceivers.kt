package com.stash.app.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * The wide "Now playing" widget in the picker (4×1 by default, `xml/now_playing_widget_info`).
 * The same [NowPlayingWidget] as the square one; the picker shows each with its own preview.
 */
class NowPlayingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NowPlayingWidget()
}

/** The square "Now playing" widget in the picker (2×2 by default, `xml/now_playing_widget_square_info`). */
class NowPlayingSquareWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NowPlayingWidget()
}
