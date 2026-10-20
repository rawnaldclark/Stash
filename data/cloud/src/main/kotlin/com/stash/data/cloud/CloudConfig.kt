package com.stash.data.cloud

import com.stash.core.model.CloudProvider

/**
 * Build-time credentials of the cloud providers, from `local.properties` through the app's
 * BuildConfig (`DROPBOX_APP_KEY`, `ONEDRIVE_CLIENT_ID`). Both are public-client ids, not
 * secrets (PKCE, no client secret). An empty one hides that provider everywhere, which is what a
 * plain checkout and CI get.
 */
data class CloudConfig(
    val dropboxAppKey: String,
    val oneDriveClientId: String,
    /** The app's applicationId (debug adds `.debug`); OneDrive's redirect scheme. */
    val applicationId: String,
) {
    fun isEnabled(provider: CloudProvider): Boolean = when (provider) {
        CloudProvider.DROPBOX -> dropboxAppKey.isNotBlank()
        CloudProvider.ONEDRIVE -> oneDriveClientId.isNotBlank()
    }

    /** Providers this build can connect, in the order Settings lists them. */
    val enabledProviders: List<CloudProvider> get() = CloudProvider.entries.filter(::isEnabled)

    val anyEnabled: Boolean get() = enabledProviders.isNotEmpty()
}
