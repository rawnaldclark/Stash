package com.stash.feature.settings.addons

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.google.common.truth.Truth.assertThat
import com.stash.core.addons.AddonSees
import com.stash.core.addons.BlockReason
import com.stash.core.addons.OfficialEntry
import com.stash.core.addons.Trust
import com.stash.data.download.lossless.addon.AddonLinks
import com.stash.data.download.lossless.addon.AddonOverview
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings › Add-ons, drawn with the real strings (add-ons plan phase 4 §2.14): the sheet's lines for
 * each set of things an add-on lists, the play order with TalkBack's Move up and Move down, and the
 * Add flow's rule that only "Add anyway" adds an add-on Stash doesn't run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class AddonScreensTest {

    @get:Rule
    val rule = createComposeRule()

    // ------------------------------------------------------------------------------------------ the "Not made by Stash" sheet

    private fun showSheet(sees: Set<AddonSees>, more: Boolean = false, onConfirm: () -> Unit = {}, onDismiss: () -> Unit = {}) = rule.setContent {
        MaterialTheme { NotMadeByStashContent("Hi-Fi Relay", "relay.example.org", sees, onConfirm, onDismiss, more) }
    }

    @Test fun `the sheet says where it runs, what its server sees and what it never gets`() {
        showSheet(setOf(AddonSees.SEARCHES, AddonSees.PLAYS, AddonSees.SAVES))
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertExists()
        rule.onNodeWithText("Not made by Stash").assertExists()
        rule.onNodeWithText("Hi-Fi Relay runs on relay.example.org. Stash doesn't run it or check it.").assertExists()
        rule.onNodeWithText("Its server will see").assertExists()
        rule.onNodeWithText("What you search for, the songs you play and save, and your internet address.").assertExists()
        rule.onNodeWithText("It never gets").assertExists()
        rule.onNodeWithText("Your accounts, your passwords, or anything from your other add-ons.").assertExists()
        rule.onNodeWithText("Only add add-ons from people you trust.").assertExists()
        rule.onNodeWithText("Add anyway").assertExists()
        rule.onNodeWithText("Cancel").assertExists()
    }

    @Test fun `the will-see line is built from what the add-on lists, and never says more`() {
        // Every set has its own whole sentence; the internet address is in all of them.
        val lines = mapOf(
            setOf(AddonSees.SEARCHES, AddonSees.PLAYS, AddonSees.SAVES) to "What you search for, the songs you play and save, and your internet address.",
            setOf(AddonSees.SEARCHES, AddonSees.PLAYS) to "What you search for, the songs you play, and your internet address.",
            setOf(AddonSees.SEARCHES, AddonSees.SAVES) to "What you search for, the songs you save, and your internet address.",
            setOf(AddonSees.PLAYS, AddonSees.SAVES) to "The songs you play and save, and your internet address.",
            setOf(AddonSees.SEARCHES) to "What you search for, and your internet address.",
            setOf(AddonSees.PLAYS) to "The songs you play, and your internet address.",
            setOf(AddonSees.SAVES) to "The songs you save, and your internet address.",
            emptySet<AddonSees>() to "Your internet address.",
        )
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        for ((sees, line) in lines) assertThat(context.getString(seesText(sees))).isEqualTo(line)
        // All eight sets are covered: none falls back to another's words.
        assertThat(lines.values.toSet()).hasSize(8)
    }

    @Test fun `only its buttons act - Add anyway confirms, Cancel dismisses`() {
        var confirmed = 0
        var dismissed = 0
        showSheet(setOf(AddonSees.PLAYS), onConfirm = { confirmed++ }, onDismiss = { dismissed++ })
        rule.onNodeWithText("The songs you play, and your internet address.").assertExists()
        assertThat(confirmed).isEqualTo(0)
        rule.onNodeWithText("Cancel").performClick()
        assertThat(dismissed).isEqualTo(1)
        assertThat(confirmed).isEqualTo(0)
        rule.onNodeWithText("Add anyway").performClick()
        assertThat(confirmed).isEqualTo(1)
    }

    @Test fun `asking again because it wants more says so, with Allow`() {
        showSheet(setOf(AddonSees.SEARCHES, AddonSees.PLAYS, AddonSees.SAVES), more = true)
        rule.onNodeWithText("Wants to see more").assertExists()
        rule.onNodeWithText("Hi-Fi Relay, on relay.example.org, now asks for more than you agreed to. Stash doesn't run it or check it.").assertExists()
        rule.onNodeWithText("Allow").assertExists()
        rule.onAllNodesWithText("Add anyway").assertCountEquals0()
    }

    // ------------------------------------------------------------------------------------------ the list and the play order

    private val qobuz = AddonOverview.Row(
        key = "qobuz", kind = AddonOverview.Kind.QOBUZ, name = null, host = null, mark = AddonOverview.Mark.NONE,
        status = AddonOverview.Status.WORKING, on = true, canSwitch = false, canRemove = false, playsSongs = true,
    )
    private val tidal = AddonOverview.Row(
        key = "tidal", kind = AddonOverview.Kind.TIDAL, name = null, host = null, mark = AddonOverview.Mark.OFFICIAL,
        status = AddonOverview.Status.WORKING, on = true, canSwitch = true, canRemove = false, playsSongs = true,
    )
    private val relay = AddonOverview.Row(
        key = "addon:hifi", kind = AddonOverview.Kind.ADDON, name = "Hi-Fi Relay", host = "relay.example.org",
        mark = AddonOverview.Mark.NOT_MADE_BY_STASH, status = AddonOverview.Status.WORKING, on = true, canSwitch = true,
        canRemove = true, playsSongs = true, entryKey = "k1", userAdded = true,
    )

    private val opened = mutableListOf<String>()
    private val nudged = mutableListOf<Pair<String, Boolean>>()
    private val switched = mutableListOf<Pair<String, Boolean>>()
    private val addFromLink = mutableListOf<String?>()
    private val removed = mutableListOf<String>()

    private fun showList(state: AddonOverview.State, highlighted: String? = null) = rule.setContent {
        MaterialTheme {
            AddonsContent(
                state = state,
                highlightedEntry = highlighted,
                onBack = {},
                onOpen = { opened += it.key },
                onAddFromLink = { addFromLink += it },
                onDropped = { _, _ -> },
                onNudged = { key, up -> nudged += key to up },
                onSwitched = { row, on -> switched += row.key to on },
                onRemove = { removed += it.key },
            )
        }
    }

    private fun state(vararg order: AddonOverview.Row, others: List<AddonOverview.Row> = emptyList(), official: List<OfficialEntry> = emptyList(), unreadable: Boolean = false) =
        AddonOverview.State(order = order.toList(), others = others, official = official, keys = order.map { it.key }, loaded = true, unreadable = unreadable)

    /** The labels of the TalkBack actions on the row named [title]. */
    private fun actionsOf(title: String): List<String> =
        rule.onNodeWithText(title).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }

    private fun act(title: String, label: String) {
        val action = rule.onNodeWithText(title).fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == label }
        rule.runOnUiThread { action.action() }
    }

    @Test fun `the play order names each row, its mark and where songs come from when none has one`() {
        showList(state(qobuz, tidal, relay))
        rule.onNodeWithText("Add-ons").assertExists()
        rule.onNodeWithText("Your Qobuz account").assertExists()
        rule.onNodeWithText("Tidal").assertExists()
        rule.onNodeWithText("Official").assertExists()
        rule.onNodeWithText("Hi-Fi Relay").assertExists()
        // The address is always beside an add-on Stash doesn't run: its name is its own word.
        rule.onNodeWithText("Not made by Stash · relay.example.org").assertExists()
        rule.onNodeWithText("When none of these has a song: JioSaavn, then YouTube.").assertExists()
        rule.onNodeWithText("Add from a link").assertExists()
        rule.onNodeWithText("Paste · Scan").assertExists()
        // Your Qobuz account has no switch; the other two have one, on.
        rule.onNodeWithContentDescription("Use Tidal").assertIsOn()
        rule.onNodeWithContentDescription("Use Hi-Fi Relay").assertIsOn()
        rule.onAllNodesWithContentDescription("Use Your Qobuz account").assertCountEquals0()
    }

    @Test fun `TalkBack moves a row with Move up and Move down, one place at a time`() {
        showList(state(qobuz, tidal, relay))
        // The first row can only go down, the last only up, the middle one both ways.
        assertThat(actionsOf("Your Qobuz account")).containsExactly("Move Your Qobuz account down")
        assertThat(actionsOf("Tidal")).containsExactly("Move Tidal up", "Move Tidal down").inOrder()
        assertThat(actionsOf("Hi-Fi Relay")).containsExactly("Move Hi-Fi Relay up")
        act("Hi-Fi Relay", "Move Hi-Fi Relay up")
        act("Tidal", "Move Tidal down")
        assertThat(nudged).containsExactly("addon:hifi" to true, "tidal" to false).inOrder()
    }

    @Test fun `a row opens its page and its switch switches it`() {
        showList(state(tidal, relay))
        rule.onNodeWithText("Hi-Fi Relay").performClick()
        rule.onNodeWithContentDescription("Use Hi-Fi Relay").performClick()
        rule.onNodeWithText("Add from a link").performScrollTo().performClick()
        assertThat(opened).containsExactly("addon:hifi")
        assertThat(switched).containsExactly("addon:hifi" to false)
        assertThat(addFromLink).hasSize(1)
        assertThat(addFromLink.single()).isNull()
    }

    @Test fun `a row says what is wrong in the app's own words, and one Stash turned off has no switch`() {
        val blocked = relay.copy(status = AddonOverview.Status.TURNED_OFF_BY_STASH, blockReason = BlockReason.HARMFUL, on = false, canSwitch = false)
        val wantsMore = relay.copy(key = "addon:more", name = "More", status = AddonOverview.Status.WANTS_MORE)
        val newLink = relay.copy(key = "addon:link", name = "Link", status = AddonOverview.Status.NEEDS_NEW_LINK)
        val off = relay.copy(key = "addon:off", name = "Off one", status = AddonOverview.Status.OFF, on = false)
        showList(state(tidal, blocked, wantsMore, newLink, off))
        rule.onNodeWithText("Stash turned this add-on off. It was reported as harmful.").assertExists()
        rule.onAllNodesWithContentDescription("Use Hi-Fi Relay").assertCountEquals0()
        rule.onNodeWithText("Wants to see more: review").assertExists()
        rule.onNodeWithText("Needs a new link").assertExists()
        rule.onNodeWithContentDescription("Use Off one").assertIsOff()
    }

    @Test fun `an add-on Stash turned off has Remove on its row, and Remove asks first`() {
        val blocked = relay.copy(status = AddonOverview.Status.TURNED_OFF_BY_STASH, blockReason = BlockReason.BROKEN, on = false, canSwitch = false)
        showList(state(tidal, blocked))
        rule.onNodeWithText("Stash turned this add-on off. It was reported as not working.").assertExists()
        // Only that row has one: Tidal, and an add-on that is merely off, are switched, not removed, here.
        rule.onAllNodesWithContentDescription("Remove Tidal").assertCountEquals0()
        rule.onNodeWithContentDescription("Remove Hi-Fi Relay").performClick()
        // The tap asked; nothing was removed.
        assertThat(removed).isEmpty()
        rule.onNodeWithText("Remove Hi-Fi Relay?").assertExists()
        rule.onNodeWithText("Stash stops asking it for songs and forgets what it found there. Songs you already downloaded stay.").assertExists()
        rule.onNodeWithText("Cancel").performSemanticsAction(SemanticsActions.OnClick)
        assertThat(removed).isEmpty()
        rule.onAllNodesWithText("Remove Hi-Fi Relay?").assertCountEquals0()

        rule.onNodeWithContentDescription("Remove Hi-Fi Relay").performClick()
        // The dialog's own Remove: the one with no row name on it.
        rule.onNode(
            androidx.compose.ui.test.hasText("Remove") and androidx.compose.ui.test.hasClickAction() and
                !androidx.compose.ui.test.hasContentDescription("Remove Hi-Fi Relay"),
        ).performSemanticsAction(SemanticsActions.OnClick)
        assertThat(removed).containsExactly("addon:hifi")
    }

    @Test fun `add-ons that couldn't be read are said to be unread, not missing`() {
        showList(state(tidal, unreadable = true))
        rule.onNodeWithText("Stash couldn't read the add-ons you added just now, so they aren't shown here. They are still saved. Close Stash and open it again.").assertExists()
    }

    @Test fun `an official add-on not added yet is offered, and a lyrics add-on has its own group`() {
        val words = relay.copy(key = "addon:words", name = "Words", playsSongs = false)
        showList(state(tidal, others = listOf(words), official = listOf(OfficialEntry("radio", "Example Radio", "Live stations", manifestUrl = "https://addons.example.org/radio/manifest.json"))))
        // Section labels are drawn in capitals.
        rule.onNodeWithText("Other add-ons", ignoreCase = true).assertExists()
        rule.onNodeWithText("Words").assertExists()
        rule.onNodeWithText("Example Radio").performScrollTo().performClick()
        assertThat(addFromLink).containsExactly("https://addons.example.org/radio/manifest.json")
    }

    // ------------------------------------------------------------------------------------------ Add from a link

    private val added = mutableListOf<Triple<String, String?, Boolean>>()

    private fun preview(trust: Trust = Trust.UNOFFICIAL, needsToken: Boolean = false) = AddonLinks.Preview(
        manifestUrl = "https://relay.example.org/v1/s3cret-tok/manifest.json", id = "hifi", name = "Hi-Fi Relay", version = "1.2.0",
        description = null, host = "relay.example.org", uses = listOf(AddonLinks.Use.PLAYS_SONGS, AddonLinks.Use.LOSSLESS),
        needsToken = needsToken, trust = trust, sees = setOf(AddonSees.SEARCHES, AddonSees.PLAYS),
        needsConsent = trust == Trust.UNOFFICIAL, playsSongs = true,
    )

    private fun showAdd(step: AddFromLinkViewModel.Step, link: String = "") = rule.setContent {
        MaterialTheme {
            AddFromLinkContent(
                step = step, link = link, replacing = false, landsAfterTidal = true, clipboardEmpty = false,
                onBack = {}, onLinkChange = {}, onRead = {}, onPaste = {}, onScan = {},
                onAdd = { p, token, agreed -> added += Triple(p.id, token, agreed) },
                onCancel = {},
            )
        }
    }

    @Test fun `the preview shows the address only, what Stash will use and where it lands`() {
        showAdd(AddFromLinkViewModel.Step.Ready(preview()))
        rule.onNodeWithText("Hi-Fi Relay").assertExists()
        rule.onNodeWithText("Version 1.2.0").assertExists()
        rule.onNodeWithText("Not made by Stash · relay.example.org").assertExists()
        rule.onNodeWithText("Plays songs").assertExists()
        rule.onNodeWithText("Lossless").assertExists()
        rule.onNodeWithText("Stash asks it for songs Tidal doesn't have. Drag it up to ask it first.").assertExists()
        // The rest of the link (a token can be in it) is never on screen.
        rule.onAllNodesWithText("s3cret-tok", substring = true).assertCountEquals0()
    }

    @Test fun `an add-on Stash doesn't run is added only by Add anyway on the sheet`() {
        showAdd(AddFromLinkViewModel.Step.Ready(preview()))
        rule.onNodeWithText("Add").performScrollTo().performClick()
        // The tap opened the sheet; nothing was added.
        assertThat(added).isEmpty()
        rule.onNodeWithText("Its server will see").assertExists()
        rule.onNodeWithText("What you search for, the songs you play, and your internet address.").assertExists()
        // The button's own click, wherever the sheet has slid to in a test window.
        rule.onNodeWithText("Add anyway").performSemanticsAction(SemanticsActions.OnClick)
        assertThat(added).containsExactly(Triple("hifi", null, true))
    }

    @Test fun `an official add-on has no sheet`() {
        showAdd(AddFromLinkViewModel.Step.Ready(preview(trust = Trust.OFFICIAL)))
        rule.onNodeWithText("Official").assertExists()
        rule.onNodeWithText("Add").performScrollTo().performClick()
        assertThat(added).containsExactly(Triple("hifi", null, false))
        rule.onAllNodesWithText("Add anyway").assertCountEquals0()
    }

    @Test fun `every refusal has its own plain sentence`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val said = AddonLinks.Refusal.entries.associateWith { context.getString(refusalText(it)) }
        // The plan's words (§2.1).
        assertThat(said[AddonLinks.Refusal.NOT_AN_ADDON]).isEqualTo("That link isn't an add-on.")
        assertThat(said[AddonLinks.Refusal.ONLY_BITCHORD]).isEqualTo("This add-on only works in BitChord.")
        assertThat(said[AddonLinks.Refusal.NEEDS_NEWER_STASH]).isEqualTo("This add-on needs a newer Stash.")
        assertThat(said[AddonLinks.Refusal.BLOCKED_HARMFUL]).isEqualTo("Stash turned this add-on off. It was reported as harmful.")
        // A reason from a later list than this app knows: still refused, with the plain sentence.
        assertThat(said[AddonLinks.Refusal.BLOCKED]).isEqualTo("Stash turned this add-on off.")
        assertThat(context.getString(blockedText(BlockReason.OTHER))).isEqualTo("Stash turned this add-on off.")
        assertThat(said[AddonLinks.Refusal.UNREACHABLE]).isEqualTo("Couldn't reach that add-on. Check the link and your connection.")
        assertThat(said[AddonLinks.Refusal.NOTHING_TO_PLAY]).isEqualTo("This add-on offers nothing Stash can play yet.")
        assertThat(said[AddonLinks.Refusal.NOT_HTTPS]).isEqualTo("Add-on links must start with https://.")
        assertThat(said[AddonLinks.Refusal.LOCAL_ADDRESS]).isEqualTo("Add-ons can't use addresses on your own network.")
        assertThat(said[AddonLinks.Refusal.COULD_NOT_SAVE]).isEqualTo("Couldn't save. Try again.")
        // None of them shows machinery.
        for (text in said.values) for (word in listOf("manifest", "origin", "resolve", "token", "JSON", "401")) {
            assertThat(text.lowercase()).doesNotContain(word.lowercase())
        }
        showAdd(AddFromLinkViewModel.Step.Refused(AddonLinks.Refusal.LOCAL_ADDRESS), link = "https://192.168.1.10/manifest.json")
        rule.onNodeWithText("Add-ons can't use addresses on your own network.").assertExists()
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals0() {
    assertThat(fetchSemanticsNodes()).isEmpty()
}
