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
import kotlinx.coroutines.Job
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

    /** Remove on a row Stash turned off, after the user confirmed it. */
    fun onRemove(row: AddonOverview.Row) {
        viewModelScope.launch { if (!overview.remove(row)) saveFailures.value++ }
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

    /** Where a new add-on that plays songs lands among the sources that are on: the preview says so (§2.1). */
    enum class Lands {
        /** Right after Tidal: "for songs Tidal doesn't have". */
        AFTER_TIDAL,

        /** After the other sources that are on. */
        AFTER_OTHERS,

        /**
         * No other source is on (no Qobuz account, Tidal switched off or not there at all): it is
         * asked for every song, and the preview must say that, not "after your other sources".
         */
        ALONE,
    }

    val lands: StateFlow<Lands> = overview.state
        .map { landsAmong(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Lands.AFTER_OTHERS)

    /** The read that is under way, and what it is a read of. Touched on the main thread only. */
    private var reading: Job? = null
    private var readingFor: Pair<String, String?>? = null

    /**
     * Reads the add-on behind [link]. [replacingKey]: the add-on whose link this replaces, from its page.
     *
     * A read can take ten seconds on a slow host, and another link can arrive meanwhile (a second
     * share, Paste, a scanned code, an edit). The newest link wins: the read under way is cancelled,
     * and an answer is shown only if it is for the link asked last (review-885 M2). The screen used to
     * show the first link's preview under a field that held the second one.
     */
    fun read(link: String, replacingKey: String?) {
        if (link.isBlank()) return
        val asked = link to replacingKey
        // A second tap on Read for the link already being read asks nothing twice.
        if (stepState.value == Step.Reading && readingFor == asked) return
        // Named before the old read is cancelled: a read that ends as it is cancelled finds it is not the one asked for.
        readingFor = asked
        reading?.cancel()
        stepState.value = Step.Reading
        reading = viewModelScope.launch {
            val o = links.preview(link, replacingKey)
            // Cancelled or overtaken while it was read: its answer is for nothing on the screen.
            if (readingFor !== asked) return@launch
            readingFor = null
            stepState.value = when (o) {
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

    /** The field changed or was cleared: whatever was read, or is being read, is no longer what it holds. */
    fun reset() {
        readingFor = null
        reading?.cancel()
        reading = null
        stepState.value = Step.Idle
    }

    companion object {
        /** [Lands] for the rows as they stand. Before the rows have been read once, the sentence that claims least. */
        internal fun landsAmong(state: AddonOverview.State): Lands {
            if (!state.loaded) return Lands.AFTER_OTHERS
            val on = state.order.filter { it.on }
            return when {
                on.isEmpty() -> Lands.ALONE
                on.last().kind == AddonOverview.Kind.TIDAL -> Lands.AFTER_TIDAL
                else -> Lands.AFTER_OTHERS
            }
        }
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
