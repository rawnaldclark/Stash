package com.stash.feature.settings.addons

import com.google.common.truth.Truth.assertThat
import com.stash.addons.contract.Quota
import com.stash.core.addons.Trust
import com.stash.data.download.lossless.addon.AddonLinks
import com.stash.data.download.lossless.addon.AddonOverview
import com.stash.data.download.lossless.tidal.OwnTidalServer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * The view models of Settings › Add-ons carry taps to [AddonOverview] and [AddonLinks] and say what
 * came back. What those two do is tested where they live; this is the step between them and the
 * screens: that a tap reaches the right call with the right add-on, that nothing is added by a read,
 * and that a change which could not be saved is said, never swallowed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddonsViewModelsTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val tidal = AddonOverview.Row(
        key = "tidal", kind = AddonOverview.Kind.TIDAL, name = null, host = null, mark = AddonOverview.Mark.OFFICIAL,
        status = AddonOverview.Status.WORKING, on = true, canSwitch = true, canRemove = false, playsSongs = true,
    )
    private val relay = AddonOverview.Row(
        key = "addon:hifi", kind = AddonOverview.Kind.ADDON, name = "Hi-Fi Relay", host = "relay.example.org",
        mark = AddonOverview.Mark.NOT_MADE_BY_STASH, status = AddonOverview.Status.WORKING, on = true, canSwitch = true,
        canRemove = true, playsSongs = true, entryKey = "k1", userAdded = true,
    )

    private fun shown(vararg order: AddonOverview.Row) =
        AddonOverview.State(order = order.toList(), keys = order.map { it.key }, loaded = true)

    private val pages = MutableStateFlow(shown(relay, tidal))
    private val overview: AddonOverview = mockk(relaxed = true) { every { state } returns pages }
    private val links: AddonLinks = mockk(relaxed = true) { every { lastAdded } returns MutableStateFlow<String?>(null) }

    private val preview = AddonLinks.Preview(
        manifestUrl = "https://relay.example.org/v1/s3cret-tok/manifest.json", id = "hifi", name = "Hi-Fi Relay", version = "1.2.0",
        description = null, host = "relay.example.org", uses = listOf(AddonLinks.Use.PLAYS_SONGS), needsToken = false,
        trust = Trust.UNOFFICIAL, sees = emptySet(), needsConsent = true, playsSongs = true,
    )

    // ------------------------------------------------------------------------------------------ Add from a link

    @Test fun `a link is read into a preview or a refusal, and reading adds nothing`() {
        coEvery { links.preview("https://relay.example.org/v1/s3cret-tok/", null) } returns AddonLinks.Outcome.Ready(preview)
        coEvery { links.preview("https://192.168.1.10/", null) } returns AddonLinks.Outcome.Refused(AddonLinks.Refusal.LOCAL_ADDRESS)
        val vm = AddFromLinkViewModel(links, overview)
        assertThat(vm.step.value).isEqualTo(AddFromLinkViewModel.Step.Idle)
        // Nothing typed: nothing is asked.
        vm.read("  ", null)
        assertThat(vm.step.value).isEqualTo(AddFromLinkViewModel.Step.Idle)
        coVerify(exactly = 0) { links.preview(any(), any()) }

        vm.read("https://relay.example.org/v1/s3cret-tok/", null)
        assertThat(vm.step.value).isEqualTo(AddFromLinkViewModel.Step.Ready(preview))
        vm.read("https://192.168.1.10/", null)
        assertThat(vm.step.value).isEqualTo(AddFromLinkViewModel.Step.Refused(AddonLinks.Refusal.LOCAL_ADDRESS))
        coVerify(exactly = 0) { links.add(any(), any(), any()) }
    }

    @Test fun `a second tap on Read while its link is being read asks nothing twice`() {
        val answer = CompletableDeferred<AddonLinks.Outcome>()
        coEvery { links.preview(any(), any()) } coAnswers { answer.await() }
        val vm = AddFromLinkViewModel(links, overview)
        vm.read("https://relay.example.org/", null)
        assertThat(vm.step.value).isEqualTo(AddFromLinkViewModel.Step.Reading)
        vm.read("https://relay.example.org/", null)
        assertThat(vm.step.value).isEqualTo(AddFromLinkViewModel.Step.Reading)
        answer.complete(AddonLinks.Outcome.Ready(preview))
        assertThat(vm.step.value).isEqualTo(AddFromLinkViewModel.Step.Ready(preview))
        coVerify(exactly = 1) { links.preview(any(), any()) }
        // Editing the link afterwards starts over.
        vm.reset()
        assertThat(vm.step.value).isEqualTo(AddFromLinkViewModel.Step.Idle)
    }

    @Test fun `a new link while another is being read takes over, and the first link's answer is never shown`() {
        // review-885 M2: a second share, Paste or scanned code while a slow host is still being read.
        // The field showed the new link, its read never started, and the first link's preview came up
        // under it: Add then added the first link's add-on.
        val first = CompletableDeferred<AddonLinks.Outcome>()
        val second = CompletableDeferred<AddonLinks.Outcome>()
        // The first read ends with an answer even when it is cancelled: nothing may show it.
        coEvery { links.preview("https://one.example.org/", any()) } coAnswers {
            try {
                first.await()
            } catch (e: CancellationException) {
                AddonLinks.Outcome.Ready(preview)
            }
        }
        coEvery { links.preview("https://two.example.org/", any()) } coAnswers { second.await() }
        val other = preview.copy(manifestUrl = "https://two.example.org/manifest.json", id = "two", name = "Two", host = "two.example.org")
        val vm = AddFromLinkViewModel(links, overview)
        vm.read("https://one.example.org/", null)
        // What the screen does when another link arrives: reset, then read.
        vm.reset()
        vm.read("https://two.example.org/", null)
        assertThat(vm.step.value).isEqualTo(AddFromLinkViewModel.Step.Reading)
        first.complete(AddonLinks.Outcome.Ready(preview))
        assertThat(vm.step.value).isEqualTo(AddFromLinkViewModel.Step.Reading)
        second.complete(AddonLinks.Outcome.Ready(other))
        assertThat(vm.step.value).isEqualTo(AddFromLinkViewModel.Step.Ready(other))
        coVerify(exactly = 1) { links.preview("https://two.example.org/", null) }
    }

    @Test fun `a new link read without a reset first also takes over`() {
        val first = CompletableDeferred<AddonLinks.Outcome>()
        coEvery { links.preview("https://one.example.org/", any()) } coAnswers { first.await() }
        coEvery { links.preview("https://two.example.org/", any()) } returns AddonLinks.Outcome.Refused(AddonLinks.Refusal.NOT_AN_ADDON)
        val vm = AddFromLinkViewModel(links, overview)
        vm.read("https://one.example.org/", null)
        vm.read("https://two.example.org/", null)
        assertThat(vm.step.value).isEqualTo(AddFromLinkViewModel.Step.Refused(AddonLinks.Refusal.NOT_AN_ADDON))
        first.complete(AddonLinks.Outcome.Ready(preview))
        assertThat(vm.step.value).isEqualTo(AddFromLinkViewModel.Step.Refused(AddonLinks.Refusal.NOT_AN_ADDON))
    }

    @Test fun `editing the field while its link is being read drops the read`() {
        val answer = CompletableDeferred<AddonLinks.Outcome>()
        coEvery { links.preview(any(), any()) } coAnswers { answer.await() }
        val vm = AddFromLinkViewModel(links, overview)
        vm.read("https://relay.example.org/", null)
        vm.reset()
        assertThat(vm.step.value).isEqualTo(AddFromLinkViewModel.Step.Idle)
        answer.complete(AddonLinks.Outcome.Ready(preview))
        assertThat(vm.step.value).isEqualTo(AddFromLinkViewModel.Step.Idle)
        // The same link can be read again afterwards.
        vm.read("https://relay.example.org/", null)
        assertThat(vm.step.value).isEqualTo(AddFromLinkViewModel.Step.Ready(preview))
    }

    @Test fun `Replace link reads the new link for the add-on it replaces`() {
        coEvery { links.preview(any(), any()) } returns AddonLinks.Outcome.Refused(AddonLinks.Refusal.DIFFERENT_ADDON)
        val vm = AddFromLinkViewModel(links, overview)
        vm.read("https://elsewhere.example.org/", "k1")
        coVerify(exactly = 1) { links.preview("https://elsewhere.example.org/", "k1") }
        assertThat(vm.step.value).isEqualTo(AddFromLinkViewModel.Step.Refused(AddonLinks.Refusal.DIFFERENT_ADDON))
    }

    @Test fun `Add carries the password and the agreement, and a refusal stays on the screen`() {
        coEvery { links.add(preview, null, false) } returns AddonLinks.Outcome.Refused(AddonLinks.Refusal.NOT_AGREED)
        coEvery { links.add(preview, "s3cret", true) } returns AddonLinks.Outcome.Added("k1")
        val vm = AddFromLinkViewModel(links, overview)
        // Without the sheet's "Add anyway": refused, and said.
        vm.add(preview, null, agreed = false)
        assertThat(vm.step.value).isEqualTo(AddFromLinkViewModel.Step.Refused(AddonLinks.Refusal.NOT_AGREED))
        vm.add(preview, "s3cret", agreed = true)
        assertThat(vm.step.value).isEqualTo(AddFromLinkViewModel.Step.Added)
    }

    @Test fun `where a new add-on lands is said from the sources that are on`() = runTest {
        val after = AddFromLinkViewModel.Lands.AFTER_OTHERS
        val afterTidal = AddFromLinkViewModel.Lands.AFTER_TIDAL
        val alone = AddFromLinkViewModel.Lands.ALONE
        val off = AddonOverview.Status.OFF
        // Tidal is the last source that is on: "for songs Tidal doesn't have".
        assertThat(AddFromLinkViewModel.landsAmong(shown(tidal))).isEqualTo(afterTidal)
        assertThat(AddFromLinkViewModel.landsAmong(shown(relay, tidal))).isEqualTo(afterTidal)
        assertThat(AddFromLinkViewModel.landsAmong(shown(tidal, relay.copy(on = false, status = off)))).isEqualTo(afterTidal)
        // Another source that is on comes after Tidal: "after your other sources".
        assertThat(AddFromLinkViewModel.landsAmong(shown(tidal, relay))).isEqualTo(after)
        // Nothing else is on (no Tidal at all, or Tidal switched off): it is asked for every song, and says so.
        assertThat(AddFromLinkViewModel.landsAmong(shown())).isEqualTo(alone)
        assertThat(AddFromLinkViewModel.landsAmong(shown(tidal.copy(on = false, status = off)))).isEqualTo(alone)
        // Before the rows have been read once, nothing is claimed about what is on.
        assertThat(AddFromLinkViewModel.landsAmong(AddonOverview.State())).isEqualTo(after)

        // And the screen is told as the rows change.
        val vm = AddFromLinkViewModel(links, overview)
        assertThat(vm.lands.first { it != after }).isEqualTo(afterTidal)
        pages.value = shown()
        assertThat(vm.lands.first { it == alone }).isEqualTo(alone)
    }

    // ------------------------------------------------------------------------------------------ the list

    @Test fun `opening the page asks for the list and the manifests again, and a failure there is not a crash`() {
        AddonsViewModel(overview, links)
        coVerify(exactly = 1) { overview.refreshIfStale() }
        coEvery { overview.refreshIfStale() } throws IOException("https://relay.example.org/v1/s3cret-tok/")
        AddonsViewModel(overview, links)
    }

    @Test fun `a switch, a drag, a move and Remove reach the add-on they were made on`() {
        coEvery { overview.setOn(any(), any()) } returns true
        coEvery { overview.move(any(), any(), any()) } returns true
        coEvery { overview.nudge(any(), any(), any()) } returns true
        coEvery { overview.remove(any()) } returns true
        val vm = AddonsViewModel(overview, links)
        vm.onSwitched(relay, false)
        vm.onDropped("addon:hifi", 0)
        vm.onNudged("tidal", up = false)
        vm.onRemove(relay)
        coVerify(exactly = 1) { overview.setOn(relay, false) }
        coVerify(exactly = 1) { overview.move(any(), "addon:hifi", 0) }
        coVerify(exactly = 1) { overview.nudge(any(), "tidal", false) }
        coVerify(exactly = 1) { overview.remove(relay) }
        assertThat(vm.saveFailed.value).isEqualTo(0)
    }

    @Test fun `each change that could not be saved is counted, so each is said once`() {
        coEvery { overview.setOn(any(), any()) } returns false
        coEvery { overview.remove(any()) } returns false
        val vm = AddonsViewModel(overview, links)
        assertThat(vm.saveFailed.value).isEqualTo(0)
        vm.onSwitched(relay, false)
        assertThat(vm.saveFailed.value).isEqualTo(1)
        vm.onRemove(relay)
        assertThat(vm.saveFailed.value).isEqualTo(2)
    }

    @Test fun `the hub row names what is on, in the order it is asked`() = runTest {
        pages.value = shown(tidal, relay.copy(on = false, status = AddonOverview.Status.OFF), relay.copy(key = "addon:two", name = "Second"))
        val rows = AddonsHubViewModel(overview).rows.first { it.isNotEmpty() }
        assertThat(rows.map { it.key }).containsExactly("tidal", "addon:two").inOrder()
    }

    // ------------------------------------------------------------------------------------------ the page

    private val own: OwnTidalServer = mockk(relaxed = true)

    @Test fun `the open page asks whether the add-on is paused, and for its allowance only when it has one and is working`() {
        coEvery { overview.paused(any()) } returns true
        coEvery { overview.quota(any()) } returns Quota(used = 12, limit = 300, resetsAt = 0L)
        val vm = AddonPageViewModel(overview, own)
        vm.look(relay)
        assertThat(vm.live.value.paused).isTrue()
        assertThat(vm.live.value.quota).isNull()
        coVerify(exactly = 0) { overview.quota(any()) }

        val metered = relay.copy(metered = true)
        vm.look(metered)
        assertThat(vm.live.value.quota).isEqualTo(Quota(used = 12, limit = 300, resetsAt = 0L))
        // One that isn't being used is not asked anything.
        vm.look(metered.copy(status = AddonOverview.Status.WANTS_MORE))
        assertThat(vm.live.value.quota).isNull()
        coVerify(exactly = 1) { overview.quota(any()) }
        // The user's own Tidal server is only looked up for the Tidal row that is one.
        assertThat(vm.live.value.ownTokenSaved).isFalse()
        coVerify(exactly = 0) { own.now() }
    }

    @Test fun `the page's switch, moves, Remove and Allow reach the add-on, and a failed save is counted`() {
        coEvery { overview.setOn(any(), any()) } returns true
        coEvery { overview.nudge(any(), any(), any()) } returns true
        coEvery { overview.remove(any()) } returns true
        coEvery { overview.agree(any()) } returns false
        val vm = AddonPageViewModel(overview, own)
        vm.onSwitched(relay, false)
        vm.onNudged(relay, up = true)
        vm.onRemove(relay)
        assertThat(vm.saveFailed.value).isEqualTo(0)
        vm.onAgreed(relay)
        assertThat(vm.saveFailed.value).isEqualTo(1)
        coVerify(exactly = 1) { overview.setOn(relay, false) }
        coVerify(exactly = 1) { overview.nudge(any(), "addon:hifi", true) }
        coVerify(exactly = 1) { overview.remove(relay) }
        coVerify(exactly = 1) { overview.agree(relay) }
    }
}
