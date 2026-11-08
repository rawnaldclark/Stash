package com.stash.feature.settings.spotify

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthState
import com.stash.core.auth.model.SpotifyCookieCheck
import com.stash.core.auth.model.ValidatedSpotifySignIn
import com.stash.core.auth.spotify.SpotifyAccount
import com.stash.core.auth.spotify.SpotifyProfile
import com.stash.core.data.db.dao.SpotifyAccountDao
import com.stash.core.data.db.dao.SyncHistoryDao
import com.stash.core.data.db.entity.SpotifyAccountEntity
import com.stash.core.data.sync.MAX_EXTRA_SPOTIFY_ACCOUNTS
import com.stash.core.data.sync.SpotifyAccountRemoval
import com.stash.core.data.sync.SpotifyExtraAccountRemover
import com.stash.core.data.sync.SyncPreferencesManager
import com.stash.core.data.sync.SyncScheduler
import com.stash.feature.settings.components.SpotifyWebSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * One of the other Spotify accounts (plan 2026-11-04: an "extra"), as Settings shows it. Carries
 * no Spotify user id: the screen has no use for one, and it is never shown.
 *
 * @property needsSignIn Its sign-in stopped working (a sync found it signed out), or there is none
 *   on this phone (a restored backup, a keyset reset): its card offers "Sign in again".
 */
data class OtherSpotifyAccount(
    val id: Long,
    val name: String,
    val syncPlaylists: Boolean,
    val syncLiked: Boolean,
    val syncAlbums: Boolean,
    val syncMixes: Boolean,
    val needsSignIn: Boolean,
)

/** What a sign-in started from the other accounts is for. */
sealed interface OtherAccountSignIn {
    /** "Add another Spotify account". */
    data object Add : OtherAccountSignIn

    /** "Sign in again" on the account [accountId]: only that same Spotify account is taken. */
    data class Again(val accountId: Long) : OtherAccountSignIn
}

/** Why the paste dialog shows an error; the words are string resources. */
enum class OtherAccountSignInError {
    /** Spotify gave no signed-in session for it. */
    INVALID,

    /** Checking or saving it failed on this phone (a Keystore that wouldn't answer, no network). */
    NOT_SAVED,
}

/** A sign-in that was refused, explained in a dialog; nothing was saved. */
sealed interface OtherAccountRefusal {
    /** It is the account Stash is connected to already, [mainName] (null when it has none to show). */
    data class AlreadyConnected(val mainName: String?) : OtherAccountRefusal

    /** Spotify didn't say whose account it is, so it can't be kept apart from the others. */
    data object Unidentified : OtherAccountRefusal

    /** Signing [name] in again landed on another Spotify account. */
    data class DifferentAccount(val name: String) : OtherAccountRefusal

    /** There are already [MAX_EXTRA_SPOTIFY_ACCOUNTS] other accounts. */
    data object Full : OtherAccountRefusal
}

/** A short message for the snackbar; the words are string resources. */
sealed interface OtherAccountNotice {
    data class SignedInAgain(val name: String) : OtherAccountNotice
    data class Removed(val name: String) : OtherAccountNotice

    /** The account was gone already (removed a moment before). */
    data object AlreadyRemoved : OtherAccountNotice

    /** Its sign-in couldn't be forgotten, so nothing was removed. */
    data class NotRemoved(val name: String) : OtherAccountNotice

    /** Its sign-in was forgotten but the rest failed: removing it again finishes. */
    data class RemoveUnfinished(val name: String) : OtherAccountNotice

    /** Added before the first sync ever ran: its music comes with that one. */
    data class AddedForFirstSync(val name: String) : OtherAccountNotice
    data object Syncing : OtherAccountNotice
}

/** A just-added account waiting for its name, pre-filled with [suggested] (Spotify's name for it). */
data class OtherAccountNaming(val accountId: Long, val suggested: String)

/**
 * Everything the other accounts' flows put on screen. One at a time, in practice: a sign-in (the
 * WebView, then the paste dialog that shows its check), then a refusal or the name, then "Sync
 * now?"; or an account's sheet, with its rename and remove dialogs over it.
 */
data class OtherAccountsUiState(
    /** Non-null while a sign-in is under way: what it is for. */
    val signIn: OtherAccountSignIn? = null,
    val showWebLogin: Boolean = false,
    val showPasteDialog: Boolean = false,
    val validating: Boolean = false,
    val error: OtherAccountSignInError? = null,
    val refusal: OtherAccountRefusal? = null,
    val naming: OtherAccountNaming? = null,
    /** The name of an account just added, while "Sync now?" asks. */
    val offerSyncNow: String? = null,
    /** The account whose sheet is open. */
    val openAccountId: Long? = null,
    val renamingId: Long? = null,
    val confirmRemoveId: Long? = null,
)

/**
 * Settings › Accounts › Spotify: the other Spotify accounts (plan 2026-11-04 §3, Wave 3). One main
 * account (the connected one, which hearts go to) plus up to [MAX_EXTRA_SPOTIFY_ACCOUNTS] read-only
 * others: add one, name it, choose what it syncs, sign it in again, remove it.
 *
 * **Adding** never goes through the main account's connect, which would refuse another account
 * and writes the main sign-in. The cookie is checked without saving anything
 * ([TokenManager.validateSpotifyCookie]); the main account itself, an account Spotify won't name,
 * or one past the limit is refused with nothing saved. An account added already is signed in
 * again instead of copied. Otherwise its row goes in, then its sign-in, and a sign-in that won't
 * save takes the new row back out. Spotify's cookies in the WebView are cleared once more after
 * the check (the sign-in screen already clears them as it hands the cookie over), so the WebView
 * never keeps an account; Spotify's logout page is never loaded, which would end the session
 * that was just captured.
 *
 * **Signing in again** takes only the same account, checked against its row.
 *
 * **Removing** is [SpotifyExtraAccountRemover]'s: its rows are hidden, its downloads stay, and
 * nothing is cancelled.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SpotifyAccountsViewModel @Inject constructor(
    private val tokenManager: TokenManager,
    private val accountDao: SpotifyAccountDao,
    private val remover: SpotifyExtraAccountRemover,
    private val syncScheduler: SyncScheduler,
    private val syncPreferences: SyncPreferencesManager,
    private val syncHistoryDao: SyncHistoryDao,
    private val webSession: SpotifyWebSession,
) : ViewModel() {

    /**
     * Bumped whenever a sign-in is saved or forgotten here. The stored sign-ins aren't observable,
     * and a row whose sign-in changed may not change itself (a restored row signed in again had
     * no `signed_out_at` to clear), so "Sign in again" is worked out once more on each bump.
     */
    private val signInChanges = MutableStateFlow(0)

    /** The connected (main) account's name: the one hearts go to. Null while none is connected. */
    val mainName: StateFlow<String?> = tokenManager.spotifyAuthState
        .map { mainNameOf(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), mainNameOf(tokenManager.spotifyAuthState.value))

    /** The other accounts, in the order they were added. */
    val accounts: StateFlow<List<OtherSpotifyAccount>> = combine(accountDao.observeAll(), signInChanges) { rows, _ -> rows }
        .mapLatest { rows -> rows.map { it.toUi(needsSignIn = it.signedOutAt != null || !hasSignIn(it)) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Whether "Add another Spotify account" shows: a main account is connected (the others sync
     * behind it, and hearts need it) and there is room. False until the accounts are read, so the
     * row never flashes up at the limit.
     */
    val canAdd: StateFlow<Boolean> = combine(tokenManager.spotifyAuthState, accountDao.observeAll()) { auth, rows ->
        auth is AuthState.Connected && rows.size < MAX_EXTRA_SPOTIFY_ACCOUNTS
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _state = MutableStateFlow(OtherAccountsUiState())
    val state: StateFlow<OtherAccountsUiState> = _state.asStateFlow()

    private val _notices = MutableSharedFlow<OtherAccountNotice>(extraBufferCapacity = 4)
    val notices: SharedFlow<OtherAccountNotice> = _notices.asSharedFlow()

    /** The sign-in being checked: Cancel drops it (nothing is saved until its last step). */
    private var signInJob: Job? = null

    // ── Signing in ───────────────────────────────────────────────────────

    /** "Add another Spotify account": opens Spotify's sign-in. */
    fun startAdd() = openSignIn(OtherAccountSignIn.Add)

    /** "Sign in again" on [accountId]'s card or sheet. */
    fun startSignInAgain(accountId: Long) = openSignIn(OtherAccountSignIn.Again(accountId))

    private fun openSignIn(purpose: OtherAccountSignIn) {
        signInJob?.cancel()
        _state.update {
            it.copy(signIn = purpose, showWebLogin = true, showPasteDialog = false, validating = false, error = null, refusal = null)
        }
    }

    /** The sign-in screen's "Paste cookie" fallback. */
    fun pasteInstead() {
        _state.update { it.copy(showWebLogin = false, showPasteDialog = true, validating = false, error = null) }
    }

    /** Cancel or Back on the sign-in screen or the paste dialog: a check under way is dropped. */
    fun cancelSignIn() {
        signInJob?.cancel()
        _state.update { it.withSignInClosed() }
    }

    /**
     * The Accounts screen left for good (not a rotation): a sign-in left open would otherwise
     * greet the next visit, as the main account's does ([com.stash.feature.settings.SettingsViewModel.onWebLoginsClosed]).
     */
    fun onScreenClosed() = cancelSignIn()

    /**
     * The sign-in screen captured a cookie. Its check reports through the paste dialog, whose
     * spinner covers the few seconds it takes and which shows a rejection: as the main account's
     * sign-in does.
     */
    fun onCookieCaptured(cookie: String) {
        _state.update { it.copy(showWebLogin = false, showPasteDialog = true) }
        submit(cookie)
    }

    /** Checks [cookie] for the sign-in under way, then adds the account or signs it in again. */
    fun submit(cookie: String) {
        val purpose = _state.value.signIn ?: return
        if (cookie.isBlank()) return
        signInJob?.cancel()
        signInJob = viewModelScope.launch {
            _state.update { it.copy(validating = true, error = null) }
            val check = try {
                tokenManager.validateSpotifyCookie(cookie.trim())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "checking another Spotify account's sign-in failed: ${e.javaClass.simpleName}")
                null
            }
            val outcome = when (check) {
                null -> Outcome.Error(OtherAccountSignInError.NOT_SAVED)
                SpotifyCookieCheck.Invalid -> Outcome.Error(OtherAccountSignInError.INVALID)
                // From here it writes: a Cancel tapped meanwhile must not leave a row without its sign-in.
                is SpotifyCookieCheck.Valid -> withContext(NonCancellable) {
                    try {
                        settle(purpose, check)
                    } catch (e: Exception) {
                        // A database that wouldn't answer: a row added stays only with its sign-in (see save).
                        Log.w(TAG, "adding another Spotify account failed: ${e.javaClass.simpleName}")
                        Outcome.Error(OtherAccountSignInError.NOT_SAVED)
                    }
                }
            }
            // Again, whatever came of it: the WebView must never keep an account (one refused included).
            webSession.clear()
            show(outcome)
        }
    }

    private sealed interface Outcome {
        data class Error(val error: OtherAccountSignInError) : Outcome
        data class Refused(val refusal: OtherAccountRefusal) : Outcome
        data class Added(val accountId: Long, val name: String) : Outcome
        data class SignedInAgain(val name: String) : Outcome

        /** The account being signed in again was removed meanwhile: nothing to do. */
        data object Gone : Outcome
    }

    private suspend fun settle(purpose: OtherAccountSignIn, check: SpotifyCookieCheck.Valid): Outcome {
        val account = check.account ?: return Outcome.Refused(OtherAccountRefusal.Unidentified)
        return when (purpose) {
            OtherAccountSignIn.Add -> add(check, account)
            is OtherAccountSignIn.Again -> signInAgain(purpose.accountId, check, account)
        }
    }

    private suspend fun add(check: SpotifyCookieCheck.Valid, account: SpotifyProfile): Outcome {
        if (check.isMainAccount == true) return Outcome.Refused(alreadyConnected())
        // Added already: sign that row in again, never a second row for one account.
        accountDao.findByAccount(account.userId)?.let { return save(it, check.signIn, isNew = false) }
        if (accountDao.count() >= MAX_EXTRA_SPOTIFY_ACCOUNTS) return Outcome.Refused(OtherAccountRefusal.Full)
        val row = accountDao.addOrFind(account.userId, suggestedName(account), System.currentTimeMillis())
        return save(row, check.signIn, isNew = true)
    }

    private suspend fun signInAgain(accountId: Long, check: SpotifyCookieCheck.Valid, account: SpotifyProfile): Outcome {
        val row = accountDao.getById(accountId) ?: return Outcome.Gone
        if (!SpotifyAccount(row.id, row.userId).isAccount(account)) {
            return Outcome.Refused(OtherAccountRefusal.DifferentAccount(row.label))
        }
        if (check.isMainAccount == true) return Outcome.Refused(alreadyConnected())
        return save(row, check.signIn, isNew = false)
    }

    /**
     * Saves [signIn] as [row]'s sign-in. When it won't save, a row added for it ([isNew]) goes back
     * out: an account with no sign-in would sit there asking to be signed in again. A refusal from
     * the token store means the main account (it refuses nothing else that reaches it here).
     */
    private suspend fun save(row: SpotifyAccountEntity, signIn: ValidatedSpotifySignIn, isNew: Boolean): Outcome {
        val saved = try {
            tokenManager.saveSpotifyExtra(SpotifyAccount(row.id, row.userId), signIn)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "saving another Spotify account's sign-in failed: ${e.javaClass.simpleName}")
            null
        }
        if (saved != true) {
            if (isNew) forgetRow(row.id)
            return if (saved == false) Outcome.Refused(alreadyConnected()) else Outcome.Error(OtherAccountSignInError.NOT_SAVED)
        }
        // Its sign-in works again: no more "Sign in again" (the next sync would also clear it).
        runCatching { accountDao.updateSignedOutAt(row.id, null) }
            .onFailure { Log.w(TAG, "clearing an account's signed-out mark failed: ${it.javaClass.simpleName}") }
        signInChanges.update { it + 1 }
        return if (isNew) Outcome.Added(row.id, row.label) else Outcome.SignedInAgain(row.label)
    }

    private suspend fun forgetRow(accountId: Long) {
        try {
            accountDao.delete(accountId)
        } catch (e: Exception) {
            Log.w(TAG, "taking back a row whose sign-in didn't save failed: ${e.javaClass.simpleName}")
        }
    }

    private fun show(outcome: Outcome) {
        when (outcome) {
            is Outcome.Error -> _state.update { it.copy(validating = false, error = outcome.error) }
            is Outcome.Refused -> _state.update { it.withSignInClosed().copy(refusal = outcome.refusal) }
            is Outcome.Added -> _state.update {
                it.withSignInClosed().copy(naming = OtherAccountNaming(outcome.accountId, outcome.name))
            }
            is Outcome.SignedInAgain -> {
                _state.update { it.withSignInClosed() }
                _notices.tryEmit(OtherAccountNotice.SignedInAgain(outcome.name))
            }
            Outcome.Gone -> _state.update { it.withSignInClosed() }
        }
    }

    /** The listener read why a sign-in was refused. */
    fun dismissRefusal() = _state.update { it.copy(refusal = null) }

    // ── Naming a new account, then "Sync now?" ──────────────────────────────

    /** The name typed for a just-added account; blank keeps Spotify's. Then "Sync now?". */
    fun confirmName(name: String) {
        val naming = _state.value.naming ?: return
        _state.update { it.copy(naming = null) }
        launchSafely("naming an account") {
            val chosen = cleanName(name)
            if (chosen != null && chosen != naming.suggested) accountDao.updateLabel(naming.accountId, chosen)
            offerSync(chosen ?: naming.suggested)
        }
    }

    /** "Skip" on the name: it keeps Spotify's name. Then "Sync now?". */
    fun skipName() {
        val naming = _state.value.naming ?: return
        _state.update { it.copy(naming = null) }
        launchSafely("offering a sync") { offerSync(naming.suggested) }
    }

    /**
     * Asks "Sync now?", unless no sync has ever run: the first one asks how music should arrive
     * (the Sync tab's first-sync sheet), which a sync started from here would skip.
     */
    private suspend fun offerSync(name: String) {
        if (firstSyncDone()) {
            _state.update { it.copy(offerSyncNow = name) }
        } else {
            _notices.tryEmit(OtherAccountNotice.AddedForFirstSync(name))
        }
    }

    private suspend fun firstSyncDone(): Boolean = try {
        syncPreferences.arrivalChosen.first() || syncHistoryDao.getLatest() != null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /** "Sync now": a manual sync, as the Sync tab's button starts it (Auto-sync's pause after an Undo ends too). */
    fun syncNow() {
        _state.update { it.copy(offerSyncNow = null) }
        launchSafely("starting a sync") {
            val prefs = syncPreferences.preferences.first()
            if (prefs.autoSyncPausedAfterUndo) syncPreferences.setAutoSyncPausedAfterUndo(false)
            syncScheduler.triggerManualSync(prefs.wifiOnly)
            _notices.tryEmit(OtherAccountNotice.Syncing)
        }
    }

    /** "Later": its music comes with the next sync. */
    fun syncLater() = _state.update { it.copy(offerSyncNow = null) }

    // ── An account's sheet: name, what it syncs, remove ─────────────────────

    fun openAccount(accountId: Long) = _state.update { it.copy(openAccountId = accountId) }

    fun closeAccount() = _state.update { it.copy(openAccountId = null) }

    fun startRename(accountId: Long) = _state.update { it.copy(renamingId = accountId) }

    fun cancelRename() = _state.update { it.copy(renamingId = null) }

    /** Renames [accountId]; a blank name changes nothing. */
    fun rename(accountId: Long, name: String) {
        _state.update { it.copy(renamingId = null) }
        val chosen = cleanName(name) ?: return
        launchSafely("renaming an account") { accountDao.updateLabel(accountId, chosen) }
    }

    // One setter per switch (the DAO's), so two quick taps can't undo each other.

    fun setSyncPlaylists(accountId: Long, on: Boolean) =
        launchSafely("a Playlists switch") { accountDao.setSyncPlaylists(accountId, on) }

    fun setSyncLiked(accountId: Long, on: Boolean) =
        launchSafely("a Liked Songs switch") { accountDao.setSyncLiked(accountId, on) }

    fun setSyncAlbums(accountId: Long, on: Boolean) =
        launchSafely("a Saved albums switch") { accountDao.setSyncAlbums(accountId, on) }

    fun setSyncMixes(accountId: Long, on: Boolean) =
        launchSafely("a Mixes switch") { accountDao.setSyncMixes(accountId, on) }

    /** "Remove <name>": asks first. */
    fun requestRemove(accountId: Long) = _state.update { it.copy(confirmRemoveId = accountId) }

    fun cancelRemove() = _state.update { it.copy(confirmRemoveId = null) }

    /**
     * Removes the account the confirm was for, and says how it went. No download is cancelled here
     * or by the remover: Disconnect's cancel goes by song source, which would take the main
     * account's queued songs too.
     */
    fun confirmRemove() {
        val accountId = _state.value.confirmRemoveId ?: return
        _state.update { it.copy(confirmRemoveId = null, openAccountId = null) }
        viewModelScope.launch {
            val name = accounts.value.firstOrNull { it.id == accountId }?.name
                ?: runCatching { accountDao.getById(accountId)?.label }.getOrNull()
                ?: ""
            val notice = try {
                when (remover.remove(accountId)) {
                    is SpotifyAccountRemoval.Removed -> OtherAccountNotice.Removed(name)
                    SpotifyAccountRemoval.NotFound -> OtherAccountNotice.AlreadyRemoved
                    SpotifyAccountRemoval.SignInNotForgotten -> OtherAccountNotice.NotRemoved(name)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The remover forgot the sign-in first, so the account stays, signed out.
                Log.w(TAG, "removing another Spotify account failed part way: ${e.javaClass.simpleName}")
                OtherAccountNotice.RemoveUnfinished(name)
            }
            signInChanges.update { it + 1 }
            _notices.tryEmit(notice)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Runs [block], logging (never throwing) a failure: a full disk must not crash Settings over a switch. */
    private fun launchSafely(what: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$what failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun alreadyConnected() = OtherAccountRefusal.AlreadyConnected(mainNameOf(tokenManager.spotifyAuthState.value))

    private suspend fun hasSignIn(row: SpotifyAccountEntity): Boolean = try {
        tokenManager.hasSpotifyExtraSignIn(SpotifyAccount(row.id, row.userId))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Unreadable: it can't sync either, so it asks to be signed in again.
        false
    }

    private fun OtherAccountsUiState.withSignInClosed() =
        copy(signIn = null, showWebLogin = false, showPasteDialog = false, validating = false, error = null)

    private fun SpotifyAccountEntity.toUi(needsSignIn: Boolean) = OtherSpotifyAccount(
        id = id,
        name = label,
        syncPlaylists = syncPlaylists,
        syncLiked = syncLiked,
        syncAlbums = syncAlbums,
        syncMixes = syncMixes,
        needsSignIn = needsSignIn,
    )

    internal companion object {
        private const val TAG = "SpotifyAccounts"

        /** The longest name an account can be given: it has to fit a card's title. */
        const val MAX_NAME_LENGTH = 40

        /** A typed name, trimmed and cut to [MAX_NAME_LENGTH]; null when there is nothing left. */
        fun cleanName(name: String): String? =
            name.trim().replace(Regex("\\s+"), " ").take(MAX_NAME_LENGTH).trim().takeIf { it.isNotEmpty() }

        /** The name a new account starts with: Spotify's name for it (its user id when it gives none). */
        fun suggestedName(account: SpotifyProfile): String =
            cleanName(account.displayName) ?: cleanName(account.userId) ?: "Spotify"

        fun mainNameOf(auth: AuthState): String? =
            (auth as? AuthState.Connected)?.user?.displayName?.trim()?.takeIf { it.isNotEmpty() }
    }
}
