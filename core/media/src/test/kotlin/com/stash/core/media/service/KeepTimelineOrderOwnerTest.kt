package com.stash.core.media.service

import android.os.Bundle
import androidx.media3.session.MediaSession
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * The service keeps a big queue's timeline order for as long as the controller that asked for it
 * (the app's) is connected: its disconnect is when the app stops looking after the order, and the
 * idle stop alone is not (a play landing mid-stop keeps this instance and the app's controller).
 */
@RunWith(RobolectricTestRunner::class)
class KeepTimelineOrderOwnerTest {

    private val service = Robolectric.buildService(StashPlaybackService::class.java).get()

    /** A controller of this app (the repository's, or Media3's own notification controller), told apart by pid. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun controller(pid: Int, packageName: String = "com.stash.app.debug") =
        MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            packageName, pid, /* uid= */ 10_443, /* libraryVersion= */ 0, /* interfaceVersion= */ 0,
            /* trusted= */ true, Bundle.EMPTY, /* isPackageNameVerified= */ true,
        )

    private val app = controller(pid = 1)
    private val notification = controller(pid = 2)

    @Test
    fun `the app's controller leaving drops the request, another controller leaving doesn't`() {
        service.onKeepTimelineOrder(app, on = true)
        assertThat(service.keepTimelineOrderOn).isTrue()

        service.onControllerDisconnected(notification)
        assertThat(service.keepTimelineOrderOn).isTrue()

        service.onControllerDisconnected(app)
        assertThat(service.keepTimelineOrderOn).isFalse()
    }

    @Test
    fun `while the app's controller stays, the request stays`() {
        service.onKeepTimelineOrder(app, on = true)

        // The idle stop with a play queued behind it: the app's controller never leaves.
        service.onControllerDisconnected(controller(pid = 3, packageName = "com.google.android.projection.gearhead"))

        assertThat(service.keepTimelineOrderOn).isTrue()
    }

    @Test
    fun `turned off, the request has no owner left for a later disconnect to matter`() {
        service.onKeepTimelineOrder(app, on = true)
        service.onKeepTimelineOrder(app, on = false)
        service.onControllerDisconnected(app)
        assertThat(service.keepTimelineOrderOn).isFalse()

        // Asked again by the app's next controller (after a reconnect): the old one leaving doesn't matter.
        val next = controller(pid = 4)
        service.onKeepTimelineOrder(next, on = true)
        service.onControllerDisconnected(app)
        assertThat(service.keepTimelineOrderOn).isTrue()
        service.onControllerDisconnected(next)
        assertThat(service.keepTimelineOrderOn).isFalse()
    }
}
