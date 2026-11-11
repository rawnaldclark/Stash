package com.stash.feature.settings.addons

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.addons.contract.Quota
import com.stash.data.download.lossless.addon.AddonLinks
import com.stash.data.download.lossless.addon.AddonOverview
import com.stash.data.download.lossless.tidal.OwnTidalServer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Settings › Add-ons (add-ons plan phase 4 §2.1): the play order's rows, each with its switch, and
 * Get add-ons. Everything shown comes from [AddonOverview]; this only carries the taps to it.
 */
@HiltViewModel
class AddonsViewModel @Inject constructor(
    private val overview: AddonOverview,
    private val links: AddonLinks,
) : ViewModel() {

    val state: StateFlow<AddonOverview.State> =
        overview.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AddonOverview.State())

    /** The add-on added a moment ago: its row is highlighted once. */
    val highlighted: StateFlow<String?> = links.lastAdded

    init {
        // The index and the manifests, when the copies are stale (§1.5): opening the page is when it matters.
        viewModelScope.launch {
            try {
                overview.refreshIfStale()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "refresh failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private val saveFailures = MutableStateFlow(0)

    /** Counts the changes that could not be saved: each one is said once ("Couldn't save. Try again."). */
    val saveFailed: StateFlow<Int> = saveFailures.asStateFlow()

    /** A drag ended: the row [key] was dropped at index [to] of the list on screen. */
    fun onDropped(key: String, to: Int) {
        val shown = state.value
        viewModelScope.launch { if (!overview.move(shown, key, to)) saveFailures.value++ }
    }

    /** TalkBack's Move up / Move down, one place at a time. */
    fun onNudged(key: String, up: Boolean) {
        val shown = state.value
        viewModelScope.launch { if (!overview.nudge(shown, key, up)) saveFailures.value++ }
    }

    fun onSwitched(row: AddonOverview.Row, on: Boolean) {
        viewModelScope.launch { if (!overview.setOn(row, on)) saveFailures.value++ }
    }

    fun onHighlightShown() = links.highlightShown()

    private companion object {
        const val TAG = "AddonsScreen"
    }
}

/**
 * One add-on's page (§2.9): the same rows as the list, plus what is only worth asking when the page
 * is open (whether the add-on is paused right now, and its allowance).
 */
@HiltViewModel
class AddonPageViewModel @Inject constructor(
    private val overview: AddonOverview,
    private val own: OwnTidalServer,
) : ViewModel() {

    val state: StateFlow<AddonOverview.State> =
        overview.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AddonOverview.State())

    /** What only the open page asks. [paused]: it failed or asked to be left alone, so it isn't being asked right now. */
    data class Live(val paused: Boolean = false, val quota: Quota? = null, val ownTokenSaved: Boolean = false)

    private val liveState = MutableStateFlow(Live())
    val live: StateFlow<Live> = liveState.asStateFlow()

    /** Asks what [row]'s page shows beyond the row itself. Called when the page opens and when the row changes. */
    fun look(row: AddonOverview.Row) {
        viewModelScope.launch {
            try {
                liveState.value = Live(
                    paused = overview.paused(row),
                    quota = if (row.metered && row.status == AddonOverview.Status.WORKING) overview.quota(row) else null,
                    ownTokenSaved = row.ownServer && own.now()?.token != null,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "page details failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private val saveFailures = MutableStateFlow(0)

    /** Counts the changes that could not be saved: each one is said once ("Couldn't save. Try again."). */
    val saveFailed: StateFlow<Int> = saveFailures.asStateFlow()

    fun onSwitched(row: AddonOverview.Row, on: Boolean) {
        viewModelScope.launch { if (!overview.setOn(row, on)) saveFailures.value++ }
    }

    fun onNudged(row: AddonOverview.Row, up: Boolean) {
        val shown = state.value
        viewModelScope.launch { if (!overview.nudge(shown, row.key, up)) saveFailures.value++ }
    }

    /** Remove, or "Use Stash's Tidal instead", after the user confirmed it. */
    fun onRemove(row: AddonOverview.Row) {
        viewModelScope.launch { if (!overview.remove(row)) saveFailures.value++ }
    }

    /** "Wants to see more": the user agreed on the sheet. */
    fun onAgreed(row: AddonOverview.Row) {
        viewModelScope.launch { if (!overview.agree(row)) saveFailures.value++ }
    }

    private companion object {
        const val TAG = "AddonPage"
    }
}

/**
 * Add from a link (§2.1, §2.2): a link in (paste, Share → Stash, a scanned code), the add-on read,
 * a preview, then Add. Nothing is added before the tap on Add, and an add-on Stash doesn't run only
 * after "Add anyway" on the sheet.
 */
@HiltViewModel
class AddFromLinkViewModel @Inject constructor(
    private val links: AddonLinks,
    overview: AddonOverview,
) : ViewModel() {

    /** Where the flow stands. */
    sealed interface Step {
        data object Idle : Step
        data object Reading : Step
        data class Ready(val preview: AddonLinks.Preview) : Step
        data class Refused(val reason: AddonLinks.Refusal) : Step
        data object Added : Step
    }

    private val stepState = MutableStateFlow<Step>(Step.Idle)
    val step: StateFlow<Step> = stepState.asStateFlow()

    /**
     * The play order ends with Tidal, switched on: a new add-on lands right after it, and the
     * preview can say "for songs Tidal doesn't have". Otherwise it says "after your other sources".
     */
    val landsAfterTidal: StateFlow<Boolean> = overview.state
        .map { s -> s.order.lastOrNull()?.let { it.kind == AddonOverview.Kind.TIDAL && it.on } == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Reads the add-on behind [link]. [replacingKey]: the add-on whose link this replaces, from its page. */
    fun read(link: String, replacingKey: String?) {
        if (link.isBlank() || stepState.value == Step.Reading) return
        stepState.value = Step.Reading
        viewModelScope.launch {
            stepState.value = when (val o = links.preview(link, replacingKey)) {
                is AddonLinks.Outcome.Ready -> Step.Ready(o.preview)
                is AddonLinks.Outcome.Refused -> Step.Refused(o.reason)
                is AddonLinks.Outcome.Added -> Step.Added
            }
        }
    }

    /** Add (or Replace). [agreed]: "Add anyway" on the "Not made by Stash" sheet. */
    fun add(preview: AddonLinks.Preview, token: String?, agreed: Boolean) {
        viewModelScope.launch {
            stepState.value = when (val o = links.add(preview, token, agreed)) {
                is AddonLinks.Outcome.Refused -> Step.Refused(o.reason)
                else -> Step.Added
            }
        }
    }

    fun reset() {
        if (stepState.value != Step.Reading) stepState.value = Step.Idle
    }
}

/** The Settings hub's Add-ons row: the names of what is on, in the order they are asked. */
@HiltViewModel
class AddonsHubViewModel @Inject constructor(overview: AddonOverview) : ViewModel() {
    /** Add-on rows that are on; the screen names the two named rows itself. */
    val rows: StateFlow<List<AddonOverview.Row>> = overview.state
        .map { s -> s.order.filter { it.on } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
