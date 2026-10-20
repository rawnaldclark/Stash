package com.stash.data.cloud.provider.gdrive

import com.stash.core.data.db.dao.CloudDao
import com.stash.core.data.db.entity.CloudFolderEntity

/**
 * A Google Drive account has two fixed `cloud_folders` rows, neither a real folder the user
 * picked: [GoogleDriveProvider.PICKS_ITEM_ID] (the songs picked in Google's picker) and
 * [GoogleDriveProvider.UPLOADS_ITEM_ID] (songs Stash saved to the user's Drive, from this phone
 * or another one). This makes [itemId]'s row on first use and returns it.
 */
suspend fun CloudDao.driveFolderRow(accountId: Long, itemId: String, now: Long): CloudFolderEntity {
    getFolders(accountId).firstOrNull { it.itemId == itemId }?.let { return it }
    val (name, path) = when (itemId) {
        GoogleDriveProvider.UPLOADS_ITEM_ID -> GoogleDriveProvider.UPLOADS_NAME to GoogleDriveProvider.UPLOADS_PATH
        else -> GoogleDriveProvider.PICKS_NAME to ""
    }
    // insertFolder ignores a conflict (-1): another caller made the row at the same moment.
    insertFolder(CloudFolderEntity(accountId = accountId, itemId = itemId, name = name, path = path, addedAt = now))
    return getFolders(accountId).first { it.itemId == itemId }
}
