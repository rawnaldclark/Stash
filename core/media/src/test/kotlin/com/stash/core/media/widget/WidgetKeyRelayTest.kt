package com.stash.core.media.widget

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The widget's Pause and Next while playing go through the app's receiver ([WidgetKeyRelay]). */
@RunWith(RobolectricTestRunner::class)
class WidgetKeyRelayTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Stands in for the app's receiver (it lives in :app). */
    private class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = Unit
    }

    private val pause = WidgetTransport(KeyEvent.KEYCODE_MEDIA_PAUSE, foreground = false)
    private val next = WidgetTransport(KeyEvent.KEYCODE_MEDIA_NEXT, foreground = false)

    private fun relay(transport: WidgetTransport) = WidgetKeyRelay.intent(context, Receiver::class.java, transport)

    @Test
    fun `the tap is an explicit broadcast to the app's own receiver`() {
        val intent = relay(pause)
        assertThat(intent.action).isEqualTo(WidgetKeyRelay.ACTION)
        assertThat(intent.component).isEqualTo(ComponentName(context, Receiver::class.java))
    }

    @Test
    fun `with the service alive the key is forwarded as it was tapped`() {
        assertThat(WidgetKeyRelay.route(relay(pause), serviceAlive = true)).isEqualTo(WidgetKeyRelay.Route.Forward(pause))
        assertThat(WidgetKeyRelay.route(relay(next), serviceAlive = true)).isEqualTo(WidgetKeyRelay.Route.Forward(next))
    }

    @Test
    fun `with the service gone (a crash left Pause showing) the widget is redrawn instead`() {
        assertThat(WidgetKeyRelay.route(relay(pause), serviceAlive = false)).isEqualTo(WidgetKeyRelay.Route.Redraw)
        assertThat(WidgetKeyRelay.route(relay(next), serviceAlive = false)).isEqualTo(WidgetKeyRelay.Route.Redraw)
    }

    @Test
    fun `anything but a relayed Pause or Next is ignored`() {
        assertThat(WidgetKeyRelay.route(null, serviceAlive = true)).isEqualTo(WidgetKeyRelay.Route.Ignore)
        assertThat(WidgetKeyRelay.route(Intent("other"), serviceAlive = true)).isEqualTo(WidgetKeyRelay.Route.Ignore)
        // A Play key is never relayed: it must start the service in the foreground.
        val play = relay(WidgetTransport(KeyEvent.KEYCODE_MEDIA_PLAY, foreground = true))
        assertThat(WidgetKeyRelay.route(play, serviceAlive = true)).isEqualTo(WidgetKeyRelay.Route.Ignore)
        assertThat(WidgetKeyRelay.route(Intent(WidgetKeyRelay.ACTION), serviceAlive = true)).isEqualTo(WidgetKeyRelay.Route.Ignore)
    }

    @Test
    fun `every relayed button the controls produce routes to a forward`() {
        val playing = NowPlayingWidgetState("Song", "Artist", null, isPlaying = true)
        val buttons = WidgetControls.of(playing, serviceRunning = true)!!
        for (transport in listOf(buttons.playPause, buttons.next)) {
            assertThat(transport.foreground).isFalse()
            assertThat(WidgetKeyRelay.route(relay(transport), serviceAlive = true)).isEqualTo(WidgetKeyRelay.Route.Forward(transport))
        }
    }
}
