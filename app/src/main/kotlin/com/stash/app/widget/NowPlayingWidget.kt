package com.stash.app.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.widget.RemoteViews
import androidx.annotation.LayoutRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import com.stash.app.R
import com.stash.core.media.PlaybackSessionBus
import com.stash.core.media.widget.WidgetButtons
import com.stash.core.media.widget.WidgetControls
import com.stash.core.media.widget.WidgetMediaIntents
import com.stash.core.media.widget.WidgetTransport
import com.stash.core.ui.theme.StashDarkColorScheme
import com.stash.core.ui.theme.StashLightColorScheme
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

/** How the widget reaches the app's singletons (Glance builds the widget, not Hilt). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface NowPlayingWidgetEntryPoint {
    fun nowPlayingWidgetStore(): NowPlayingWidgetStore
    fun playbackSessionBus(): PlaybackSessionBus
}

/**
 * Stash's home screen widget: the song that's playing (or was last), its cover, play/pause and
 * next. One widget, two shapes picked from its real size ([SizeMode.Exact], so a resize redraws it):
 *  - wide (4×1 and other short, wide sizes): a card with the rounded cover on the left, title and
 *    artist, then the play/pause disc in the album colour and Next;
 *  - square (2×2 and taller sizes): the cover full bleed, with a dark strip along the bottom holding
 *    the title, the artist and the two buttons.
 *
 * Updates are pushed by the playback service ([GlanceNowPlayingWidgetSink]); this widget has no
 * update period. A tap on the cover or title opens Now Playing; with nothing to show, the whole
 * widget opens the app.
 */
class NowPlayingWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(context.applicationContext, NowPlayingWidgetEntryPoint::class.java)
        val store = entryPoint.nowPlayingWidgetStore()
        val sessionBus = entryPoint.playbackSessionBus()
        val first = try {
            store.current()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            WidgetModel.Empty
        }
        provideContent {
            val model by store.models.collectAsState(initial = first)
            val serviceRunning by sessionBus.sessionAlive.collectAsState()
            GlanceTheme(colors = WidgetColors) {
                NowPlayingContent(model, serviceRunning)
            }
        }
    }
}

/** The app's own Material 3 schemes; Glance switches between them with the system's dark mode. */
private val WidgetColors = ColorProviders(light = StashLightColorScheme, dark = StashDarkColorScheme)

/** The widget card's corners: the app's large shape (StashShapes.large). */
private val CardRadius = 16.dp

/** The 4×1 cover's corners: the app's small shape, so it nests in the card's 16 dp at an 8 dp inset. */
private val CoverRadius = 8.dp

private val CardInset = 8.dp

/** Taller than this, and not much wider than tall, and the widget draws its square shape. */
private val SquareMinHeight = 100.dp
private const val SQUARE_MAX_ASPECT = 1.6f

private fun isSquare(size: DpSize): Boolean =
    size.height >= SquareMinHeight && size.width.value <= size.height.value * SQUARE_MAX_ASPECT

@Composable
private fun NowPlayingContent(model: WidgetModel, serviceRunning: Boolean) {
    val context = LocalContext.current
    val size = LocalSize.current
    val buttons = WidgetControls.of(model.state, serviceRunning)
    val state = model.state
    val title = state?.title ?: context.getString(R.string.widget_nothing_playing)
    val artist = state?.artist ?: context.getString(R.string.widget_open_stash)
    val open: Action? = (if (state != null) WidgetMediaIntents.openPlayer(context) else WidgetMediaIntents.openApp(context))
        ?.let { actionStartActivity(it) }

    if (isSquare(size)) {
        SquareCard(model.cover, title, artist, buttons, model.accents, open)
    } else {
        WideCard(model.cover, title, artist, buttons, model.accents, open, size)
    }
}

/** The card behind everything: the theme's surface, rounded, marked as the widget's background. */
@Composable
private fun cardModifier(open: Action?): GlanceModifier {
    val base = GlanceModifier
        .fillMaxSize()
        .appWidgetBackground()
        // A tinted rounded drawable, so the corners are round below Android 12 too (cornerRadius
        // is a no-op there); cornerRadius also clips the square shape's full-bleed cover on 12+.
        .background(ImageProvider(R.drawable.widget_card), colorFilter = ColorFilter.tint(GlanceTheme.colors.surface))
        .cornerRadius(CardRadius)
    return if (open != null) base.clickable(open) else base
}

@Composable
private fun WideCard(
    cover: Bitmap?,
    title: String,
    artist: String,
    buttons: WidgetButtons?,
    accents: WidgetAccents,
    open: Action?,
    size: DpSize,
) {
    val coverSize = (size.height - CardInset * 2).coerceIn(36.dp, 88.dp)
    Row(
        modifier = cardModifier(open).padding(CardInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(cover, GlanceModifier.size(coverSize).cornerRadius(CoverRadius))
        Spacer(GlanceModifier.width(12.dp))
        Column(modifier = GlanceModifier.defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
            WidgetText(
                text = title,
                layout = R.layout.widget_text_title,
                light = StashLightColorScheme.onSurface,
                dark = StashDarkColorScheme.onSurface,
            )
            if (artist.isNotEmpty()) {
                Spacer(GlanceModifier.height(2.dp))
                WidgetText(
                    text = artist,
                    layout = R.layout.widget_text_artist,
                    light = StashLightColorScheme.onSurfaceVariant,
                    dark = StashDarkColorScheme.onSurfaceVariant,
                )
            }
        }
        if (buttons != null) {
            Spacer(GlanceModifier.width(8.dp))
            PlayPauseButton(buttons, accents, diameter = 40.dp)
            Spacer(GlanceModifier.width(4.dp))
            NextButton(buttons.next, diameter = 40.dp, tint = GlanceTheme.colors.onSurface)
        }
    }
}

@Composable
private fun SquareCard(
    cover: Bitmap?,
    title: String,
    artist: String,
    buttons: WidgetButtons?,
    accents: WidgetAccents,
    open: Action?,
) {
    Box(modifier = cardModifier(open)) {
        Cover(cover, GlanceModifier.fillMaxSize())
        Column(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.Bottom) {
            // The strip: a gradient from clear to 72% black, so white text reads on any cover.
            Column(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .background(ImageProvider(R.drawable.widget_scrim))
                    .padding(start = 12.dp, end = 8.dp, top = 28.dp, bottom = 8.dp),
            ) {
                WidgetText(text = title, layout = R.layout.widget_text_title_cover, light = OnScrim, dark = OnScrim)
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = GlanceModifier.defaultWeight()) {
                        if (artist.isNotEmpty()) {
                            WidgetText(text = artist, layout = R.layout.widget_text_artist, light = OnScrimSecondary, dark = OnScrimSecondary)
                        }
                    }
                    if (buttons != null) {
                        PlayPauseButton(buttons, accents, diameter = 36.dp)
                        Spacer(GlanceModifier.width(2.dp))
                        NextButton(buttons.next, diameter = 36.dp, tint = ColorProvider(day = OnScrim, night = OnScrim))
                    }
                }
            }
        }
    }
}

/** Text on the square's dark strip: white whatever the theme (it sits on the cover, not the card). */
private val OnScrim = Color.White
private val OnScrimSecondary = Color.White.copy(alpha = 0.78f)

@Composable
private fun Cover(cover: Bitmap?, modifier: GlanceModifier) {
    Image(
        provider = if (cover != null) ImageProvider(cover) else ImageProvider(R.drawable.widget_cover_placeholder),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Crop,
    )
}

/** Play / pause: a disc in the song's accent with its ink on top, as the mini player draws it. */
@Composable
private fun PlayPauseButton(buttons: WidgetButtons, accents: WidgetAccents, diameter: Dp) {
    val context = LocalContext.current
    val label = context.getString(if (buttons.showsPause) R.string.widget_pause else R.string.widget_play)
    Box(
        modifier = GlanceModifier
            .size(diameter)
            .background(
                ImageProvider(R.drawable.widget_circle),
                colorFilter = ColorFilter.tint(ColorProvider(day = Color(accents.accentLight), night = Color(accents.accentDark))),
            )
            .cornerRadius(diameter / 2)
            .clickable(mediaAction(context, buttons.playPause))
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(if (buttons.showsPause) R.drawable.widget_ic_pause else R.drawable.widget_ic_play),
            contentDescription = null,
            modifier = GlanceModifier.size(diameter * 0.6f),
            colorFilter = ColorFilter.tint(ColorProvider(day = Color(accents.onAccentLight), night = Color(accents.onAccentDark))),
        )
    }
}

@Composable
private fun NextButton(transport: WidgetTransport, diameter: Dp, tint: androidx.glance.unit.ColorProvider) {
    val context = LocalContext.current
    val label = context.getString(R.string.widget_next)
    Box(
        modifier = GlanceModifier
            .size(diameter)
            .cornerRadius(diameter / 2)
            .clickable(mediaAction(context, transport))
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(R.drawable.widget_ic_next),
            contentDescription = null,
            modifier = GlanceModifier.size(24.dp),
            colorFilter = ColorFilter.tint(tint),
        )
    }
}

/** A media-button tap: the same event the media notification sends (see WidgetMediaIntents). */
private fun mediaAction(context: Context, transport: WidgetTransport): Action =
    actionStartService(WidgetMediaIntents.mediaButton(context, transport), isForegroundService = transport.foreground)

/**
 * One line of text in the app's own fonts. Glance's Text can only name system font families, so
 * the line is a tiny RemoteViews layout ([layout]: a TextView whose `fontFamily` is the app's
 * Space Grotesk or Inter resource), which the launcher inflates from Stash's resources.
 */
@Composable
private fun WidgetText(text: String, @LayoutRes layout: Int, light: Color, dark: Color) {
    val context = LocalContext.current
    val views = RemoteViews(context.packageName, layout).apply {
        setTextViewText(R.id.widget_text, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The launcher picks the colour for the system's dark mode as it draws.
            setColorInt(R.id.widget_text, "setTextColor", light.toArgb(), dark.toArgb())
        } else {
            setTextColor(R.id.widget_text, (if (context.isNightMode()) dark else light).toArgb())
        }
    }
    AndroidRemoteViews(views, GlanceModifier.fillMaxWidth())
}

private fun Context.isNightMode(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
