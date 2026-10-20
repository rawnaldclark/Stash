package com.stash.data.cloud

import com.stash.core.model.CloudProvider

/**
 * Build-time credentials of the cloud providers, from `local.properties` through the app's
 * BuildConfig (`DROPBOX_APP_KEY`, `ONEDRIVE_CLIENT_ID`, `GOOGLE_DRIVE_ENABLED`). The ids are public-client ids, not
 * secrets (PKCE, no client secret). An empty one hides that provider everywhere, which is what a
 * plain checkout and CI get.
 */
data class CloudConfig(
    val dropboxAppKey: String,
    val oneDriveClientId: String,
    /** The app's applicationId (debug adds `.debug`); OneDrive's redirect scheme. */
    val applicationId: String,
    /**
     * Google Drive needs no id in the app: Google matches the Android OAuth client by package
     * name + signing certificate. `GOOGLE_DRIVE_ENABLED=true` in local.properties turns it on
     * once those clients exist in the Cloud project.
     */
    val googleDriveEnabled: Boolean = false,
) {
    fun isEnabled(provider: CloudProvider): Boolean = when (provider) {
        CloudProvider.DROPBOX -> dropboxAppKey.isNotBlank()
        CloudProvider.ONEDRIVE -> oneDriveClientId.isNotBlank()
        CloudProvider.GOOGLE_DRIVE -> googleDriveEnabled
    }

    /** Providers this build can connect, in the order Settings lists them. */
    val enabledProviders: List<CloudProvider> get() = CloudProvider.entries.filter(::isEnabled)

    val anyEnabled: Boolean get() = enabledProviders.isNotEmpty()
}
