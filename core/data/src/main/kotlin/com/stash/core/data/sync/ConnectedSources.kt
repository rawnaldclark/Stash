package com.stash.core.data.sync

import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthService

/**
 * The `:sources` a sync's download step takes rows for
 * ([com.stash.core.data.db.dao.DownloadQueueDao.getAllPendingBySources]): every connected
 * service, and always BOTH (a YouTube download needs no login). The sync, its reconcile and
 * the resume run all ask through here, so they agree on which rows are theirs.
 */
suspend fun connectedDownloadSources(tokenManager: TokenManager): List<String> = buildList {
    if (tokenManager.isAuthenticated(AuthService.SPOTIFY)) add("SPOTIFY")
    if (tokenManager.isAuthenticated(AuthService.YOUTUBE_MUSIC)) add("YOUTUBE")
    if (tokenManager.hasSoundCloudConnection()) add("SOUNDCLOUD")
    add("BOTH")
}
