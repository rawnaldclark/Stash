package com.stash.core.media.widget

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Which media-button events the widget's buttons send ([WidgetControls]). */
class WidgetControlsTest {

    private val song = NowPlayingWidgetState("Song", "Artist", null, isPlaying = true)

    @Test
    fun `no song means no buttons (the widget only opens the app)`() {
        assertThat(WidgetControls.of(null, serviceRunning = true)).isNull()
        assertThat(WidgetControls.of(null, serviceRunning = false)).isNull()
    }

    @Test
    fun `playing with the service up - Pause and Next as plain service starts, like the notification`() {
        val buttons = WidgetControls.of(song, serviceRunning = true)!!
        assertThat(buttons.showsPause).isTrue()
        assertThat(buttons.playPause).isEqualTo(WidgetTransport(KeyEvent.KEYCODE_MEDIA_PAUSE, foreground = false))
        assertThat(buttons.next).isEqualTo(WidgetTransport(KeyEvent.KEYCODE_MEDIA_NEXT, foreground = false))
    }

    @Test
    fun `paused - Play starts the service in the foreground`() {
        val buttons = WidgetControls.of(song.copy(isPlaying = false), serviceRunning = true)!!
        assertThat(buttons.showsPause).isFalse()
        assertThat(buttons.playPause).isEqualTo(WidgetTransport(KeyEvent.KEYCODE_MEDIA_PLAY, foreground = true))
    }

    @Test
    fun `paused - Next skips then plays, in the foreground (never a bare Next a stopped service can't take)`() {
        val buttons = WidgetControls.of(song.copy(isPlaying = false), serviceRunning = true)!!
        assertThat(buttons.next).isEqualTo(
            WidgetTransport(KeyEvent.KEYCODE_MEDIA_PLAY, foreground = true, skipFirst = true),
        )
    }

    @Test
    fun `a stored playing is not believed once the service is gone (process death, idle stop)`() {
        val buttons = WidgetControls.of(song, serviceRunning = false)!!
        assertThat(buttons.showsPause).isFalse()
        assertThat(buttons.playPause.foreground).isTrue()
        assertThat(buttons.playPause.keyCode).isEqualTo(KeyEvent.KEYCODE_MEDIA_PLAY)
        assertThat(buttons.next.skipFirst).isTrue()
        assertThat(buttons.next.foreground).isTrue()
    }

    @Test
    fun `every foreground start ends in a Play key (Android needs the notification within seconds)`() {
        for (playing in listOf(true, false)) for (running in listOf(true, false)) {
            val buttons = WidgetControls.of(song.copy(isPlaying = playing), running)!!
            for (t in listOf(buttons.playPause, buttons.next)) {
                if (t.foreground) assertThat(t.keyCode).isEqualTo(KeyEvent.KEYCODE_MEDIA_PLAY)
            }
        }
    }
}
