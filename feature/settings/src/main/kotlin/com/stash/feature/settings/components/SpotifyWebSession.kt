package com.stash.feature.settings.components

import javax.inject.Inject

/**
 * The Spotify session in the sign-in WebView's shared cookie jar, for a ViewModel to clear
 * ([clearSpotifyWebSession]) without reaching into `android.webkit` itself, so its tests can
 * check that it did.
 */
class SpotifyWebSession @Inject constructor() {

    /** Expires Spotify's session cookies in the WebView; never signs the session out on Spotify's side. */
    fun clear() = clearSpotifyWebSession()
}
