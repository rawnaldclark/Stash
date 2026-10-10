package com.stash.core.data.weblink

/**
 * Where the sync service is and whether "Link Stash on the web" is switched on, from the app's BuildConfig (release:
 * `https://sync.stashfm.app`, feature off until it ships; debug: local.properties `sync.baseUrl`, feature on). Provided by
 * the app module, so `core:data` never reads BuildConfig itself.
 *
 * @property baseUrl the Worker's origin, without a trailing slash; routes add `/v1/…`.
 * @property enabled false hides every surface of the feature (spec §14 phase 3).
 */
data class WebLinkConfig(val baseUrl: String, val enabled: Boolean) {
    init {
        require(baseUrl.startsWith("https://") || baseUrl.startsWith("http://")) { "sync base URL must be http(s)" }
    }

    /** True for a plain-http Worker (a debug build pointed at `wrangler dev`): the client then allows cleartext. */
    val cleartext: Boolean get() = baseUrl.startsWith("http://")
}
