package com.stash.feature.settings.cloud

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.db.dao.CloudFolderWithCount
import com.stash.core.model.CloudProvider
import com.stash.data.cloud.CloudAccount
import com.stash.data.cloud.CloudLibrary
import com.stash.data.cloud.auth.CloudAuthEvent
import com.stash.data.cloud.provider.CloudEntry
import com.stash.data.cloud.provider.gdrive.GoogleDrivePicks
import com.stash.data.cloud.provider.gdrive.GooglePickOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A connected account and whether its sign-in is still on this phone. */
data class CloudAccountRow(val account: CloudAccount, val signedIn: Boolean)

/** The folder picker: which account, the folders walked into so far, and what's inside the last. */
data class CloudBrowseState(
    val accountId: Long,
    val accountName: String,
    val trail: List<CloudEntry> = emptyList(),
    val entries: List<CloudEntry> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
) {
    val current: CloudEntry? get() = trail.lastOrNull()
}

/**
 * What Settings says after a trip to Google's picker; null when there's nothing to say (the
 * user backed out).
 */
internal fun googlePickMessage(outcome: GooglePickOutcome): String? = when (outcome) {
    GooglePickOutcome.Cancelled -> null
    is GooglePickOutcome.Failed -> outcome.message
    is GooglePickOutcome.Added -> buildString {
        if (outcome.isNewAccount) append("Google Drive connected as ${outcome.displayName}. ")
        append(GoogleDrivePicks.message(outcome))
        if (outcome.added > 0 && outcome.isNewAccount) append(" Wi-Fi is best for big batches.")
    }
}

/** A disconnect waiting for the user's yes, with what it will do. */
data class PendingCloudDisconnect(val accountId: Long, val provider: CloudProvider, val removed: Int, val kept: Int)

/**
 * Settings for cloud storage libraries (Dropbox, OneDrive, Google Drive): the account cards under Accounts and
 * the Cloud folders section under Library & Storage share this ViewModel. Everything is hidden
 * when the build has no provider key ([available] false).
 */
@HiltViewModel
class CloudStorageViewModel @Inject constructor(
    private val cloud: CloudLibrary,
) : ViewModel() {

    val available: Boolean = cloud.isAvailable
    val providers: List<CloudProvider> = cloud.enabledProviders

    // Combined with signInChanges: a new account's row appears a moment before its first token
    // is saved, and without the re-check it read "Session expired" until the app restarted.
    val accounts: StateFlow<List<CloudAccountRow>> = combine(cloud.accounts, cloud.signInChanges) { list, _ -> list }
        .map { list -> list.map { CloudAccountRow(it, cloud.isSignedIn(it)) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val folders: StateFlow<List<CloudFolderWithCount>> = cloud.folders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _browse = MutableStateFlow<CloudBrowseState?>(null)
    val browse: StateFlow<CloudBrowseState?> = _browse.asStateFlow()

    private val _pendingDisconnect = MutableStateFlow<PendingCloudDisconnect?>(null)
    val pendingDisconnect: StateFlow<PendingCloudDisconnect?> = _pendingDisconnect.asStateFlow()

    init {
        viewModelScope.launch {
            cloud.authEvents.collect { event ->
                when (event) {
                    is CloudAuthEvent.Connected -> {
                        _messages.tryEmit("${event.provider.displayName} connected as ${event.displayName}.")
                        // A new account has no music yet: go straight to picking its folder.
                        if (event.isNew) openBrowser(event.accountId, event.displayName)
                    }
                    is CloudAuthEvent.Failed -> _messages.tryEmit(event.message)
                }
            }
        }
    }

    /** True for Google Drive: songs are picked in Google's picker, not as a folder browsed here. */
    fun usesPicker(provider: CloudProvider): Boolean = cloud.usesPicker(provider)

    /**
     * Opens Google's picker: for [accountId] ("Add songs", "Reconnect"), or a new account when
     * null (the first pick connects Google Drive). [launch] starts the IntentSender; its result
     * comes back to [finishGooglePick].
     */
    fun startGooglePick(accountId: Long?, launch: (IntentSender) -> Unit) {
        viewModelScope.launch {
            try {
                launch(cloud.googlePickerIntent(accountId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("CloudSettings", "Google picker didn't open: ${e::class.simpleName}")
                _messages.tryEmit("Couldn't open Google Drive. Check your connection and that Google Play services is up to date.")
            }
        }
    }

    /**
     * The picker's result Intent (null when it was dismissed). The filing itself runs in the
     * app's scope ([CloudLibrary.completeGooglePick]); leaving Settings meanwhile loses only the
     * message, never half a pick.
     */
    fun finishGooglePick(data: Intent?) {
        viewModelScope.launch {
            googlePickMessage(cloud.completeGooglePick(data))?.let { _messages.tryEmit(it) }
        }
    }

    fun connect(activityContext: Context, provider: CloudProvider) {
        runCatching { cloud.connect(activityContext, provider) }
            .onFailure { _messages.tryEmit("Couldn't open the ${provider.displayName} sign-in.") }
    }

    fun requestDisconnect(account: CloudAccount) {
        viewModelScope.launch {
            val (removed, kept) = cloud.disconnectImpact(account.id)
            _pendingDisconnect.value = PendingCloudDisconnect(account.id, account.provider, removed, kept)
        }
    }

    fun cancelDisconnect() {
        _pendingDisconnect.value = null
    }

    fun confirmDisconnect() {
        val pending = _pendingDisconnect.value ?: return
        _pendingDisconnect.value = null
        viewModelScope.launch {
            val removal = cloud.disconnect(pending.accountId)
            _messages.tryEmit(
                buildString {
                    append("${pending.provider.displayName} disconnected.")
                    if (removal.keptAsLocal > 0) append(" ${removal.keptAsLocal} downloaded songs stay on this phone.")
                },
            )
        }
    }

    // ── Folder picker ────────────────────────────────────────────────────

    fun openBrowser(accountId: Long, accountName: String) {
        _browse.value = CloudBrowseState(accountId, accountName)
        load(null)
    }

    fun enter(folder: CloudEntry) {
        if (!folder.isFolder) return
        _browse.update { it?.copy(trail = it.trail + folder) }
        load(folder.id)
    }

    fun up() {
        val state = _browse.value ?: return
        if (state.trail.isEmpty()) {
            _browse.value = null
            return
        }
        val trail = state.trail.dropLast(1)
        _browse.value = state.copy(trail = trail)
        load(trail.lastOrNull()?.id)
    }

    fun closeBrowser() {
        _browse.value = null
    }

    /** Picks the folder the picker is in. */
    fun pickCurrent() {
        val state = _browse.value ?: return
        val folder = state.current ?: return
        viewModelScope.launch {
            val added = cloud.addFolder(state.accountId, folder)
            _browse.value = null
            _messages.tryEmit(
                if (added) "Scanning ${folder.name}. Wi-Fi is best for the first scan of a big library."
                else "${folder.name} is already part of a picked folder.",
            )
        }
    }

    fun removeFolder(folderId: Long) {
        viewModelScope.launch {
            val removal = cloud.removeFolder(folderId)
            _messages.tryEmit(
                if (removal.keptAsLocal > 0) "Folder removed. ${removal.keptAsLocal} downloaded songs stay on this phone."
                else "Folder removed.",
            )
        }
    }

    fun rescan() {
        viewModelScope.launch {
            cloud.rescanAll()
            _messages.tryEmit("Checking your cloud folders for changes.")
        }
    }

    private fun load(folderId: String?) {
        val accountId = _browse.value?.accountId ?: return
        _browse.update { it?.copy(loading = true, error = null, entries = emptyList()) }
        viewModelScope.launch {
            try {
                val entries = cloud.browse(accountId, folderId)
                    .filter { it.isFolder }
                    // At the top, a folder called Music is the likely answer: list it first.
                    .sortedBy { if (folderId == null && it.name.equals("Music", ignoreCase = true)) 0 else 1 }
                _browse.update { if (it?.accountId == accountId) it.copy(entries = entries, loading = false) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("CloudBrowse", "browse failed: ${e::class.simpleName}")
                _browse.update { it?.copy(loading = false, error = "Couldn't load this folder. Check your connection.") }
            }
        }
    }
}
