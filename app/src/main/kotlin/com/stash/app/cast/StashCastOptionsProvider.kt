package com.stash.app.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions

/**
 * Configures the Cast SDK; named in the app manifest. The Default Media
 * Receiver plays our LAN URLs, so there is no custom receiver to host
 * (spec 2026-10-06 §1).
 */
class StashCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(RECEIVER_APP_ID)
        // Stash's own MediaSession and notification already drive the speaker
        // (CastSessionPlayer); the SDK's would be a second, conflicting set.
        .setCastMediaOptions(
            CastMediaOptions.Builder()
                .setNotificationOptions(null)
                .setMediaSessionEnabled(false)
                .build(),
        )
        .setStopReceiverApplicationWhenEndingSession(true)
        // A session the app didn't start can't be served: the media server
        // lives in the playback service. Start fresh each time.
        .setResumeSavedSession(false)
        .setEnableReconnectionService(false)
        .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null

    companion object {
        val RECEIVER_APP_ID: String = CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
    }
}
