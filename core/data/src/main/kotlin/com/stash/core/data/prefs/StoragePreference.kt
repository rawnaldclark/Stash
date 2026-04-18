package com.stash.core.data.prefs

import android.net.Uri
import kotlinx.coroutines.flow.Flow

/**
 * Persists the user's choice of where downloaded tracks are stored.
 *
 * - `externalTreeUri == null` means "use the app's internal music directory"
 *   (the default, unchanged pre-SD-card-support behavior).
 * - `externalTreeUri != null` means "user picked a SAF folder (Storage Access
 *   Framework) via `ACTION_OPEN_DOCUMENT_TREE` — write new downloads there.
 *   This is what enables SD card / USB-OTG targets on phones without an SD
 *   slot, and makes the files user-accessible outside the app (so users can
 *   copy them to a PC, play them in other apps, etc.).
 *
 * Callers that write the URI MUST first invoke
 * `ContentResolver.takePersistableUriPermission(uri, ...)` on the raw URI
 * returned by the system document-picker Activity Result, otherwise the
 * permission will be revoked after the app is backgrounded.
 */
interface StoragePreference {

    /**
     * Emits the persisted external-storage tree URI, or null if the user
     * has never chosen one (or cleared their choice).
     */
    val externalTreeUri: Flow<Uri?>

    /**
     * Persists [uri] as the target for new downloads. Pass null to revert
     * to internal storage.
     */
    suspend fun setExternalTreeUri(uri: Uri?)
}
