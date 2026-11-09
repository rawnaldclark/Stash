package com.stash.feature.settings.weblink

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.supporter.SupporterRepository
import com.stash.core.model.CloudProvider
import com.stash.data.cloud.CloudLibrary
import com.stash.data.download.lossless.quota.LosslessQuota
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import com.stash.feature.settings.SupporterRow
import com.stash.feature.settings.cloud.CloudAccountRow
import com.stash.feature.settings.supporterRow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * What Your Stash reads for its Supporter and Cloud sections, read-only. Each value is null until it has really been read,
 * so a section waits instead of flashing a placeholder (the Supporter row appearing late, or "Connect your cloud storage"
 * for a moment on a phone with accounts).
 *
 * The page needs only rows, so it doesn't hold a [com.stash.feature.settings.cloud.CloudStorageViewModel]: that one also
 * follows sign-ins, folders and uploads. The same sources and the same [supporterRow] rule as Lossless sources and Accounts
 * & Sync, so the three pages can't disagree.
 */
@HiltViewModel
class YourStashViewModel @Inject constructor(
    quota: LosslessQuota,
    config: LosslessConfigFetcher,
    supporter: SupporterRepository,
    private val cloud: CloudLibrary,
) : ViewModel() {

    /** The supporter row as Lossless sources shows it; null before the first read. */
    val supporter: StateFlow<SupporterRow?> = combine(
        quota.state,
        config.tidalRelay.map { it != null },
        supporter.token,
    ) { allowance, relayConfigured, token ->
        supporterRow(allowance, relayConfigured, token != null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** This build offers cloud storage at all (it has a provider key). */
    val cloudAvailable: Boolean = cloud.isAvailable

    /** The providers this build offers, for the "connect" row. */
    val cloudProviders: List<CloudProvider> = cloud.enabledProviders

    /**
     * The connected accounts and whether each sign-in is still on this phone; null before the first read. Re-read when a
     * token is saved or cleared, as Accounts & Sync does.
     */
    val cloudAccounts: StateFlow<List<CloudAccountRow>?> = combine(cloud.accounts, cloud.signInChanges) { list, _ -> list }
        .map { list -> list.map { CloudAccountRow(it, cloud.isSignedIn(it)) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
