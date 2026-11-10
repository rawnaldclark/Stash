package com.stash.core.data.sync

import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthService
import com.stash.core.auth.spotify.SpotifyAccounts

/**
 * The `:sources` a sync's download step takes rows for
 * ([com.stash.core.data.db.dao.DownloadQueueDao.getAllPendingBySources]): every connected
 * service, and always BOTH (a YouTube download needs no login). The sync, its reconcile and
 * the resume run all ask through here, so they agree on which rows are theirs.
 */
suspend fun connectedDownloadSources(tokenManager: TokenManager, spotifyAccounts: SpotifyAccounts): List<String> = buildList {
    if (spotifyAccounts.isSpotifyAuthenticated()) add("SPOTIFY")
    if (tokenManager.isAuthenticated(AuthService.YOUTUBE_MUSIC)) add("YOUTUBE")
    if (tokenManager.hasSoundCloudConnection()) add("SOUNDCLOUD")
    add("BOTH")
}
