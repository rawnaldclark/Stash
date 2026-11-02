package com.stash.core.media.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.media.service.StashPlaybackService
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The intents behind the widget's taps ([WidgetMediaIntents]). */
@RunWith(RobolectricTestRunner::class)
class WidgetMediaIntentsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Suppress("DEPRECATION") // getParcelableExtra(String): the typed overload is API 33+
    private fun keyOf(intent: Intent): KeyEvent = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)!!

    @Test
    fun `a media button goes straight to the playback service, as the notification's do`() {
        val intent = WidgetMediaIntents.mediaButton(context, WidgetTransport(KeyEvent.KEYCODE_MEDIA_PAUSE, foreground = false))

        assertThat(intent.action).isEqualTo(Intent.ACTION_MEDIA_BUTTON)
        assertThat(intent.component).isEqualTo(ComponentName(context, StashPlaybackService::class.java))
        assertThat(keyOf(intent).keyCode).isEqualTo(KeyEvent.KEYCODE_MEDIA_PAUSE)
        assertThat(keyOf(intent).action).isEqualTo(KeyEvent.ACTION_DOWN)
        assertThat(intent.hasExtra(WidgetMediaIntents.EXTRA_SKIP_FIRST)).isFalse()
    }

    @Test
    fun `every button the controls produce builds the intent for its key`() {
        val song = NowPlayingWidgetState("Song", "Artist", null, isPlaying = true)
        for (playing in listOf(true, false)) for (running in listOf(true, false)) {
            val buttons = WidgetControls.of(song.copy(isPlaying = playing), running)!!
            for (transport in listOf(buttons.playPause, buttons.next)) {
                val intent = WidgetMediaIntents.mediaButton(context, transport)
                assertThat(keyOf(intent).keyCode).isEqualTo(transport.keyCode)
                assertThat(WidgetMediaIntents.isSkipFirst(intent)).isEqualTo(transport.skipFirst)
            }
        }
    }

    @Test
    fun `Next from a paused widget is a Play key that asks for one skip first`() {
        val intent = WidgetMediaIntents.mediaButton(
            context,
            WidgetTransport(KeyEvent.KEYCODE_MEDIA_PLAY, foreground = true, skipFirst = true),
        )
        assertThat(keyOf(intent).keyCode).isEqualTo(KeyEvent.KEYCODE_MEDIA_PLAY)
        assertThat(WidgetMediaIntents.isSkipFirst(intent)).isTrue()
    }

    @Test
    fun `the skip flag only counts on a media button`() {
        assertThat(WidgetMediaIntents.isSkipFirst(null)).isFalse()
        assertThat(WidgetMediaIntents.isSkipFirst(Intent("other").putExtra(WidgetMediaIntents.EXTRA_SKIP_FIRST, true))).isFalse()
        assertThat(WidgetMediaIntents.isSkipFirst(Intent(Intent.ACTION_MEDIA_BUTTON))).isFalse()
    }

    @Test
    fun `the cover opens the launcher activity on the full player, single top`() {
        val launcher = ComponentName(context.packageName, "com.stash.app.MainActivity")
        val intent = StashPlaybackService.openPlayerIntent(launcher)

        assertThat(intent.action).isEqualTo(StashPlaybackService.ACTION_OPEN_PLAYER)
        assertThat(intent.component).isEqualTo(launcher)
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP).isNotEqualTo(0)
    }
}
