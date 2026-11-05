package com.stash.data.cloud.provider.gdrive

import com.stash.core.data.db.dao.CloudDao
import com.stash.core.data.db.entity.CloudAccountEntity
import com.stash.core.model.CloudProvider
import com.stash.data.cloud.auth.CloudTokenManager
import com.stash.data.cloud.auth.TokenResult

/** How a trip to Google's picker ended, for Settings to say. */
sealed interface GooglePickOutcome {
    /**
     * @property added picked files that are new to the library (the scan files them shortly).
     * @property alreadyThere picked files that were already songs.
     */
    data class Added(
        val accountId: Long,
        val displayName: String,
        val isNewAccount: Boolean,
        val added: Int,
        val alreadyThere: Int,
    ) : GooglePickOutcome

    data object Cancelled : GooglePickOutcome
    data class Failed(val message: String) : GooglePickOutcome

    /**
     * Refused: another Drive account is connected and Google didn't say which account one of them
     * is (see [GoogleDrivePicks]). Nothing was stored.
     */
    data object AccountUnidentified : GooglePickOutcome
}

/**
 * Files a picker result: the picker is Google Drive's sign-in too, so the first pick connects the
 * account and every later one ("Add more") adds songs to it.
 *
 * Steps: look up who picked (Drive `about`), create or re-link the `cloud_accounts` row, store
 * the token (its refresh slot = the account email, for silent re-authorization), make sure the
 * account's picks folder row exists, queue the new ids in [GooglePickStore] and start a scan of
 * that folder. Ids already in the library aren't queued again.
 *
 * Silent re-authorization finds an account by its email. Without one, Play services answers for
 * whichever account it likes, which with two accounts can be the wrong one, and songs would then
 * play and upload under the wrong sign-in. So an account with no email is never connected next to
 * another, in either order.
 */
class GoogleDrivePicks(
    private val dao: CloudDao,
    private val tokens: CloudTokenManager,
    private val auth: GoogleDriveAuth,
    private val store: GooglePickStore,
    private val scanNow: suspend (folderId: Long) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun accept(result: GooglePickerResult): GooglePickOutcome = when (result) {
        GooglePickerResult.Cancelled -> GooglePickOutcome.Cancelled
        is GooglePickerResult.Failed -> GooglePickOutcome.Failed(result.message)
        is GooglePickerResult.Picked -> accept(result.accessToken, result.fileIds)
    }

    private suspend fun accept(accessToken: String, fileIds: List<String>): GooglePickOutcome {
        val who = auth.identity(accessToken)
        val provider = CloudProvider.GOOGLE_DRIVE
        val existing = dao.findAccount(provider.id, who.remoteId)
        // A lookup that lost the email must not cost a known account the one it has.
        val email = who.email?.takeIf { it.isNotBlank() } ?: existing?.email
        if (existing == null) {
            val others = dao.getAccounts().filter { it.provider == provider.id }
            if (others.isNotEmpty() && (email == null || others.any { it.email.isNullOrBlank() })) {
                return GooglePickOutcome.AccountUnidentified
            }
        }
        val accountId = if (existing != null) {
            dao.updateAccount(existing.copy(displayName = who.displayName, email = email))
            existing.id
        } else {
            dao.insertAccount(
                CloudAccountEntity(
                    provider = provider.id, remoteId = who.remoteId, displayName = who.displayName,
                    email = email, connectedAt = clock(),
                ),
            )
        }
        tokens.saveNew(
            provider, accountId,
            TokenResult(
                accessToken = accessToken,
                refreshToken = email ?: GoogleDriveAuth.NO_ACCOUNT,
                expiresInSeconds = GoogleDriveAuth.TOKEN_LIFETIME_SECONDS,
                scope = PlayServicesGoogleAuthorizer.DRIVE_FILE_SCOPE,
            ),
        )
        val folderId = picksFolder(accountId)
        // Songs Stash saved to this Drive (on another phone, or before a reinstall) come along.
        val uploads = uploadsFolder(accountId)
        if (existing == null) scanNow(uploads)
        val known = dao.folderItemIds(folderId).toSet()
        val fresh = fileIds.filter { it !in known }
        if (fresh.isNotEmpty()) {
            store.add(folderId, fresh)
            scanNow(folderId)
        }
        return GooglePickOutcome.Added(accountId, who.displayName, existing == null, fresh.size, fileIds.size - fresh.size)
    }

    /** The account's picks folder row, made on first use. */
    private suspend fun picksFolder(accountId: Long): Long =
        dao.driveFolderRow(accountId, GoogleDriveProvider.PICKS_ITEM_ID, clock()).id

    /**
     * The account's "Saved from Stash" row, so songs Stash saved to this Drive (from any phone)
     * are found by its scans.
     */
    private suspend fun uploadsFolder(accountId: Long): Long =
        dao.driveFolderRow(accountId, GoogleDriveProvider.UPLOADS_ITEM_ID, clock()).id

    companion object {
        /** What Settings says after a pick. */
        fun message(outcome: GooglePickOutcome.Added): String = when {
            outcome.added == 0 && outcome.alreadyThere == 0 -> "Nothing was picked."
            outcome.added == 0 -> "Those songs are already in your library."
            else -> buildString {
                append(if (outcome.added == 1) "Adding 1 song from Google Drive." else "Adding ${outcome.added} songs from Google Drive.")
                if (outcome.alreadyThere > 0) append(" ${outcome.alreadyThere} were already in your library.")
            }
        }
    }
}
