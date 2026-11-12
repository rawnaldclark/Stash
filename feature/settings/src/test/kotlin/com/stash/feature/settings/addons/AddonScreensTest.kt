package com.stash.feature.settings.addons

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import com.stash.addons.contract.Quota
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
        showSheet(setOf(AddonSees.PLAYS, AddonSees.NEXT_UP, AddonSees.SAVES))
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertExists()
        rule.onNodeWithText("Not made by Stash").assertExists()
        rule.onNodeWithText("Hi-Fi Relay runs on relay.example.org. Stash doesn't run it or check it.").assertExists()
        rule.onNodeWithText("Its server will see").assertExists()
        rule.onNodeWithText("The name, artist, album, length and recording code of each song you play, of the next song in your queue and of each song you save, and your internet address.").assertExists()
        rule.onNodeWithText("It never gets").assertExists()
        rule.onNodeWithText("Your accounts, your passwords, or anything from your other add-ons.").assertExists()
        rule.onNodeWithText("Only add add-ons from people you trust.").assertExists()
        rule.onNodeWithText("Add anyway").assertExists()
        rule.onNodeWithText("Cancel").assertExists()
    }

    @Test fun `the will-see line says what Stash sends and about which songs, and never says more`() {
        // Every set has its own whole sentence; the internet address is in all of them.
        val lines = mapOf(
            setOf(AddonSees.PLAYS, AddonSees.NEXT_UP, AddonSees.SAVES) to "The name, artist, album, length and recording code of each song you play, of the next song in your queue and of each song you save, and your internet address.",
            setOf(AddonSees.PLAYS, AddonSees.NEXT_UP) to "The name, artist, album, length and recording code of each song you play and of the next song in your queue, and your internet address.",
            setOf(AddonSees.PLAYS, AddonSees.SAVES) to "The name, artist, album, length and recording code of each song you play and of each song you save, and your internet address.",
            setOf(AddonSees.PLAYS) to "The name, artist, album, length and recording code of each song you play, and your internet address.",
            setOf(AddonSees.SAVES) to "The name, artist, album, length and recording code of each song you save, and your internet address.",
            emptySet<AddonSees>() to "Your internet address.",
        )
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        for ((sees, line) in lines) assertThat(context.getString(seesText(sees))).isEqualTo(line)
        // All six sets are covered: none falls back to another's words.
        assertThat(lines.values.toSet()).hasSize(6)
        // Nothing typed in Search is sent to an add-on, so no sentence says it is.
        for (line in lines.values) assertThat(line.lowercase()).doesNotContain("search")
        // Every set an add-on can come to has one of these sentences (the next song up is never seen without the one playing).
        for (sees in listOf(setOf(AddonSees.NEXT_UP), setOf(AddonSees.NEXT_UP, AddonSees.SAVES))) {
            assertThat(context.getString(seesText(sees))).contains("the next song in your queue")
        }
    }

    @Test fun `only its buttons act - Add anyway confirms, Cancel dismisses`() {
        var confirmed = 0
        var dismissed = 0
        showSheet(setOf(AddonSees.PLAYS), onConfirm = { confirmed++ }, onDismiss = { dismissed++ })
        rule.onNodeWithText("The name, artist, album, length and recording code of each song you play, and your internet address.").assertExists()
        assertThat(confirmed).isEqualTo(0)
        rule.onNodeWithText("Cancel").performClick()
        assertThat(dismissed).isEqualTo(1)
        assertThat(confirmed).isEqualTo(0)
        rule.onNodeWithText("Add anyway").performClick()
        assertThat(confirmed).isEqualTo(1)
    }

    @Test fun `asking again because it wants more says so, with Allow`() {
        showSheet(setOf(AddonSees.PLAYS, AddonSees.NEXT_UP, AddonSees.SAVES), more = true)
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

    @Test fun `a long address on the list is never cut - its line wraps`() {
        // review-885 M13: one line with an ellipsis at its end showed "stashfm.app.login…" for an
        // address that ends in someone else's domain.
        val long = relay.copy(host = "stashfm.app.login.accounts.secure.example-hosting.net")
        showList(state(tidal, long))
        val line = "Not made by Stash · stashfm.app.login.accounts.secure.example-hosting.net"
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        rule.onNodeWithText(line, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val input = layouts.single().layoutInput
        assertThat(input.maxLines).isEqualTo(Int.MAX_VALUE)
        assertThat(input.softWrap).isTrue()
        assertThat(input.overflow).isNotEqualTo(androidx.compose.ui.text.style.TextOverflow.Ellipsis)
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
        // Three rows: the order can be changed, so the page says how, and each row has its handle.
        rule.onNodeWithText("Stash asks these for a song from top to bottom. Drag to change the order.").assertExists()
        assertThat(rule.onAllNodesWithTag(ADDON_DRAG_HANDLE_TAG).fetchSemanticsNodes()).hasSize(3)
        rule.onNodeWithText("Add from a link").assertExists()
        rule.onNodeWithText("Paste · Scan").assertExists()
        // And the list ends with space under its last card, as its pages do.
        val last = rule.onNodeWithText("Paste · Scan").getUnclippedBoundsInRoot()
        val end = rule.onNodeWithTag(PAGE_END_SPACE_TAG).getUnclippedBoundsInRoot()
        assertThat((end.top - last.bottom).value).isAtLeast(16f)
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

    @Test fun `a single source has nothing to be dragged past - no word about dragging, no handle, no moves`() {
        // What most people see: Tidal alone.
        showList(state(tidal))
        rule.onNodeWithText("Tidal").assertExists()
        rule.onNodeWithContentDescription("Use Tidal").assertIsOn()
        rule.onAllNodesWithText("Drag to change the order", substring = true).assertCountEquals0()
        assertThat(rule.onAllNodesWithTag(ADDON_DRAG_HANDLE_TAG).fetchSemanticsNodes()).isEmpty()
        assertThat(actionsOf("Tidal")).isEmpty()
        rule.onNodeWithText("When none of these has a song: JioSaavn, then YouTube.").assertExists()
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
        // Marked Official before it is added, with the one line the signed list says about it.
        rule.onNodeWithText("Official · Live stations").assertExists()
        rule.onNodeWithText("Example Radio").performScrollTo().performClick()
        assertThat(addFromLink).containsExactly("https://addons.example.org/radio/manifest.json")
    }

    // ------------------------------------------------------------------------------------------ an add-on's page

    private var pageRow by mutableStateOf(relay)
    private var pageLive by mutableStateOf(AddonPageViewModel.Live())
    private val pageRemoved = mutableListOf<String>()
    private val pageNudged = mutableListOf<Boolean>()
    private val pageSwitched = mutableListOf<Boolean>()
    private var pageAgreed = 0
    private var pageReplaced = 0

    /** The page of [row]. [place]: where it is asked, from 0; negative for a row with no place in the play order. */
    private fun showPage(
        row: AddonOverview.Row,
        place: Int = -1,
        places: Int = 0,
        live: AddonPageViewModel.Live = AddonPageViewModel.Live(),
        tidalToday: String? = null,
    ) {
        pageRow = row
        pageLive = live
        rule.setContent {
            MaterialTheme {
                AddonPageContent(
                    row = pageRow, place = place, places = places, live = pageLive, tidalToday = tidalToday,
                    onBack = {},
                    onSwitched = { pageSwitched += it },
                    onNudged = { pageNudged += it },
                    onReplaceLink = { pageReplaced++ },
                    onRemove = { pageRemoved += pageRow.key },
                    onAgreed = { pageAgreed++ },
                )
            }
        }
    }

    /** The button named [text] inside the dialog that is open, not the row under it with the same word. */
    private fun dialogButton(text: String) = rule.onNode(hasText(text) and hasClickAction() and hasAnyAncestor(isDialog()))

    @Test fun `an add-on's page says who made it, where it runs, how it is doing, what Stash uses and what its server sees`() {
        showPage(
            relay.copy(
                version = "1.2.0",
                uses = listOf(AddonLinks.Use.PLAYS_SONGS, AddonLinks.Use.LOSSLESS),
                sees = setOf(AddonSees.PLAYS, AddonSees.NEXT_UP),
            ),
        )
        rule.onNodeWithText("Version 1.2.0").assertExists()
        // The address is beside the mark here too, and the rest of the link is nowhere.
        rule.onNodeWithText("Not made by Stash · relay.example.org").assertExists()
        rule.onNodeWithText("Working").assertExists()
        rule.onNodeWithText("Plays songs").assertExists()
        rule.onNodeWithText("Lossless").assertExists()
        rule.onNodeWithText("Its server will see").assertExists()
        rule.onNodeWithText("The name, artist, album, length and recording code of each song you play and of the next song in your queue, and your internet address.").assertExists()
        rule.onNodeWithText("It never gets").assertExists()
        rule.onNodeWithText("Your accounts, your passwords, or anything from your other add-ons.").assertExists()
        rule.onNodeWithContentDescription("Use Hi-Fi Relay").assertIsOn().performClick()
        assertThat(pageSwitched).containsExactly(false)
        rule.onNodeWithText("Replace link").performScrollTo().performClick()
        assertThat(pageReplaced).isEqualTo(1)
    }

    @Test fun `the page of an add-on Stash is not asking says so, never that its server will see your songs`() {
        // review-885 N11: switched off, or turned off by Stash, the page still said "Its server will see…".
        val sees = setOf(AddonSees.PLAYS, AddonSees.NEXT_UP)
        val line = "The name, artist, album, length and recording code of each song you play and of the next song in your queue, and your internet address."
        val nothing = "Stash isn't asking this add-on for anything now, so its server sees nothing."

        // Switched off by the user: nothing now, and what it will see once it is back on.
        showPage(relay.copy(status = AddonOverview.Status.OFF, on = false, sees = sees))
        rule.onNodeWithText(nothing).assertExists()
        rule.onAllNodesWithText("Its server will see").assertCountEquals0()
        rule.onNodeWithText("When it is on, its server will see").assertExists()
        rule.onNodeWithText(line).assertExists()
        rule.onNodeWithText("It never gets").assertExists()
    }

    @Test fun `the page of an add-on Stash turned off, or one waiting for a review, says its server sees nothing`() {
        val sees = setOf(AddonSees.PLAYS, AddonSees.NEXT_UP)
        showPage(relay.copy(status = AddonOverview.Status.TURNED_OFF_BY_STASH, on = false, canSwitch = false, sees = sees, blockReason = BlockReason.HARMFUL))
        val nothing = "Stash isn't asking this add-on for anything now, so its server sees nothing."
        rule.onNodeWithText(nothing).assertExists()
        rule.onAllNodesWithText("Its server will see").assertCountEquals0()
        rule.onAllNodesWithText("When it is on, its server will see").assertCountEquals0()
        rule.onAllNodesWithText("each song you play", substring = true).assertCountEquals0()

        // Waiting for the user's review ("Wants to see more"): not used until they allow it.
        pageRow = relay.copy(status = AddonOverview.Status.WANTS_MORE, sees = sees)
        rule.waitForIdle()
        rule.onNodeWithText(nothing).assertExists()
        rule.onAllNodesWithText("Its server will see").assertCountEquals0()
        rule.onAllNodesWithText("When it is on, its server will see").assertCountEquals0()

        // Working again: the sentence is back.
        pageRow = relay.copy(sees = sees)
        rule.waitForIdle()
        rule.onAllNodesWithText(nothing).assertCountEquals0()
        rule.onNodeWithText("Its server will see").assertExists()
    }

    @Test fun `the sheet's bold address is the address, not the same words inside the add-on's name`() {
        // review-885 N11: the first place the address's text stood was made bold, and that can be in the name.
        rule.setContent {
            MaterialTheme { NotMadeByStashContent("relay.example.org radio", "relay.example.org", setOf(AddonSees.PLAYS), {}, {}) }
        }
        val intro = "relay.example.org radio runs on relay.example.org. Stash doesn't run it or check it."
        val text = rule.onNodeWithText(intro).fetchSemanticsNode().config[SemanticsProperties.Text].single()
        val bold = text.spanStyles.single()
        assertThat(text.text.substring(bold.start, bold.end)).isEqualTo("relay.example.org")
        assertThat(bold.start).isEqualTo(intro.lastIndexOf("relay.example.org"))
        assertThat(bold.start).isGreaterThan(0)
    }

    @Test fun `the page says what is wrong in plain words, and what to do about it`() {
        showPage(relay.copy(status = AddonOverview.Status.OFF, on = false))
        rule.onNodeWithText("Switched off").assertExists()
        rule.onNodeWithContentDescription("Use Hi-Fi Relay").assertIsOff()

        val said = listOf(
            AddonOverview.Status.NEEDS_NEW_LINK to listOf(
                "Needs a new link",
                "The link you added stopped working. Ask whoever runs this add-on for a new one, then replace it here.",
            ),
            AddonOverview.Status.NEEDS_NEWER_STASH to listOf(
                "Needs a newer Stash",
                "Its newest version needs a newer Stash than this one. Update Stash to keep using it.",
            ),
            AddonOverview.Status.WANTS_MORE to listOf(
                "Wants to see more: review",
                "It now asks for more than you agreed to. Stash won't use it until you review it.",
            ),
            AddonOverview.Status.NOT_REACHABLE to listOf("Not reachable right now", "Stash couldn't reach it. It will try again by itself."),
        )
        for ((status, lines) in said) {
            pageRow = relay.copy(status = status)
            rule.waitForIdle()
            for (line in lines) rule.onNodeWithText(line).assertExists()
        }

        // The ones Stash won't use: no switch to flip, and Remove is there.
        val notUsed = listOf(
            AddonOverview.Status.ONLY_BITCHORD to listOf("This add-on only works in BitChord.", "Stash stopped using it. You can remove it."),
            AddonOverview.Status.CHANGED to listOf(
                "No longer the add-on you added",
                "Its link now leads to a different add-on, so Stash stopped using it. Remove it, and add it again only if you trust where it comes from.",
            ),
            AddonOverview.Status.REPLACED to listOf(
                "Stash has this one built in",
                "Stash now has its own add-on by this name and uses that one. This copy isn't used; you can remove it.",
            ),
            AddonOverview.Status.TURNED_OFF_BY_STASH to listOf("Stash turned this add-on off. It was reported as a fake."),
        )
        for ((status, lines) in notUsed) {
            pageRow = relay.copy(status = status, blockReason = BlockReason.IMPERSONATION, on = false, canSwitch = false)
            rule.waitForIdle()
            for (line in lines) rule.onNodeWithText(line).assertExists()
            rule.onAllNodesWithContentDescription("Use Hi-Fi Relay").assertCountEquals0()
            rule.onNodeWithText("Remove").assertExists()
        }

        // On, with nothing wrong with it, but not being asked right now.
        pageRow = relay
        pageLive = AddonPageViewModel.Live(paused = true)
        rule.waitForIdle()
        rule.onNodeWithText("Paused for now").assertExists()
        rule.onNodeWithText("It asked Stash to wait, or it failed a few times in a row. Stash will try it again shortly.").assertExists()
    }

    @Test fun `Remove on the page asks first, and says what goes and what stays`() {
        showPage(relay)
        rule.onNodeWithText("Remove").performScrollTo().performClick()
        // The tap asked; nothing was removed.
        assertThat(pageRemoved).isEmpty()
        rule.onNodeWithText("Remove Hi-Fi Relay?").assertExists()
        rule.onNodeWithText("Stash stops asking it for songs and forgets what it found there. Songs you already downloaded stay.").assertExists()
        dialogButton("Cancel").performSemanticsAction(SemanticsActions.OnClick)
        assertThat(pageRemoved).isEmpty()
        rule.onAllNodesWithText("Remove Hi-Fi Relay?").assertCountEquals0()

        rule.onNodeWithText("Remove").performScrollTo().performClick()
        dialogButton("Remove").performSemanticsAction(SemanticsActions.OnClick)
        assertThat(pageRemoved).containsExactly("addon:hifi")
    }

    @Test fun `the user's own Tidal server shows its address, and giving it up asks first and says it may not come back`() {
        val own = tidal.copy(host = "tidal.example.org", mark = AddonOverview.Mark.NONE, canRemove = true, ownServer = true)
        showPage(own, live = AddonPageViewModel.Live(ownTokenSaved = true))
        rule.onNodeWithText("Tidal plays through your own server instead of Stash's.").assertExists()
        rule.onNodeWithText("Server").assertExists()
        // Under the name, and again beside "Server": the address, and only the address.
        assertThat(rule.onAllNodesWithText("tidal.example.org").fetchSemanticsNodes()).hasSize(2)
        rule.onNodeWithText("Token saved").assertExists()
        // It has no add-on link to replace, and no "Official" mark: it is the user's own.
        rule.onAllNodesWithText("Replace link").assertCountEquals0()
        rule.onAllNodesWithText("Official").assertCountEquals0()

        rule.onNodeWithText("Use Stash's Tidal instead").performScrollTo().performClick()
        assertThat(pageRemoved).isEmpty()
        rule.onNodeWithText("Use Stash's Tidal instead?").assertExists()
        rule.onNodeWithText(
            "Stash forgets your server and its token, and Tidal goes back to Stash's own. To use your server again you would add it " +
                "from its add-on link. A server that has no add-on link can't be added back in this version.",
        ).assertExists()
        dialogButton("Cancel").performSemanticsAction(SemanticsActions.OnClick)
        assertThat(pageRemoved).isEmpty()

        rule.onNodeWithText("Use Stash's Tidal instead").performScrollTo().performClick()
        dialogButton("Use Stash's Tidal instead").performSemanticsAction(SemanticsActions.OnClick)
        assertThat(pageRemoved).containsExactly("tidal")
    }

    @Test fun `Stash's own Tidal has its allowance and a switch, and nothing to remove`() {
        showPage(tidal, tidalToday = "12 of 300 songs today")
        rule.onNodeWithText("Official").assertExists()
        rule.onNodeWithText("Stash's own Tidal source. Lossless and Hi-Res for everyone, with a daily allowance.").assertExists()
        rule.onNodeWithText("12 of 300 songs today").assertExists()
        rule.onNodeWithContentDescription("Use Tidal").assertIsOn()
        rule.onAllNodesWithText("Remove").assertCountEquals0()
        rule.onAllNodesWithText("Use Stash's Tidal instead").assertCountEquals0()
        rule.onAllNodesWithText("Replace link").assertCountEquals0()
        // It is not an add-on someone else runs: nothing about what "its server" sees.
        rule.onAllNodesWithText("Its server will see").assertCountEquals0()
    }

    @Test fun `an add-on that wants to see more is reviewed on the sheet, and only Allow agrees`() {
        showPage(relay.copy(status = AddonOverview.Status.WANTS_MORE, sees = setOf(AddonSees.PLAYS, AddonSees.NEXT_UP, AddonSees.SAVES)))
        rule.onNodeWithText("Review").performClick()
        assertThat(pageAgreed).isEqualTo(0)
        rule.onNodeWithText("Hi-Fi Relay, on relay.example.org, now asks for more than you agreed to. Stash doesn't run it or check it.").assertExists()
        // The button's own click, wherever the sheet has slid to in a test window.
        rule.onNodeWithText("Allow").performSemanticsAction(SemanticsActions.OnClick)
        assertThat(pageAgreed).isEqualTo(1)
    }

    @Test fun `the page says where the add-on is asked, and moves it one place at a time`() {
        showPage(relay, place = 2, places = 3)
        rule.onNodeWithText("Asked 3rd of 3").assertExists()
        // The last one can only go up.
        rule.onNodeWithText("Move down").assertIsNotEnabled()
        rule.onNodeWithText("Move up").assertIsEnabled().performScrollTo().performClick()
        assertThat(pageNudged).containsExactly(true)
    }

    @Test fun `the last card of each page keeps clear of what lies under it`() {
        // Seen on a phone: the page's last card (Replace link, Remove) ended hard against the mini player.
        showPage(relay)
        val remove = rule.onNodeWithText("Remove").getUnclippedBoundsInRoot()
        val end = rule.onNodeWithTag(PAGE_END_SPACE_TAG).getUnclippedBoundsInRoot()
        // The Settings hub keeps 16 dp there.
        assertThat((end.top - remove.bottom).value).isAtLeast(16f)
    }

    @Test fun `alone in the play order, the page has no place to state and no moves to offer`() {
        showPage(relay, place = 0, places = 1)
        rule.onNodeWithText("Working").assertExists()
        rule.onAllNodesWithText("Move up").assertCountEquals0()
        rule.onAllNodesWithText("Move down").assertCountEquals0()
        rule.onAllNodesWithText("Asked", substring = true).assertCountEquals0()
    }

    @Test fun `a metered add-on says how much of its allowance is used`() {
        showPage(relay, live = AddonPageViewModel.Live(quota = Quota(used = 12, limit = 300, resetsAt = 0L)))
        rule.onNodeWithText("12 of 300 used today").assertExists()
        pageLive = AddonPageViewModel.Live(quota = Quota(used = 7, limit = null, resetsAt = 0L))
        rule.waitForIdle()
        rule.onNodeWithText("7 used today").assertExists()
    }

    // ------------------------------------------------------------------------------------------ Add from a link

    private val added = mutableListOf<Triple<String, String?, Boolean>>()
    private var reads = 0

    private fun preview(trust: Trust = Trust.UNOFFICIAL, needsToken: Boolean = false) = AddonLinks.Preview(
        manifestUrl = "https://relay.example.org/v1/s3cret-tok/manifest.json", id = "hifi", name = "Hi-Fi Relay", version = "1.2.0",
        description = null, host = "relay.example.org", uses = listOf(AddonLinks.Use.PLAYS_SONGS, AddonLinks.Use.LOSSLESS),
        needsToken = needsToken, trust = trust, sees = setOf(AddonSees.PLAYS, AddonSees.NEXT_UP),
        needsConsent = trust == Trust.UNOFFICIAL, playsSongs = true,
    )

    private fun showAdd(
        step: AddFromLinkViewModel.Step,
        link: String = "",
        lands: AddFromLinkViewModel.Lands = AddFromLinkViewModel.Lands.AFTER_TIDAL,
    ) = rule.setContent {
        MaterialTheme {
            AddFromLinkContent(
                step = step, link = link, replacing = false, lands = lands, clipboardEmpty = false,
                onBack = {}, onLinkChange = {}, onRead = { reads++ }, onPaste = {}, onScan = {},
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

    @Test fun `with no other source switched on, the preview says the add-on is asked for every song`() {
        // No Qobuz account, and Tidal switched off or not there: "after your other sources" would be untrue.
        showAdd(AddFromLinkViewModel.Step.Ready(preview()), lands = AddFromLinkViewModel.Lands.ALONE)
        rule.onNodeWithText("No other source is switched on, so Stash asks it for every song you stream or download.").assertExists()
        rule.onAllNodesWithText("Drag it up", substring = true).assertCountEquals0()
    }

    @Test fun `after another source that is on, the preview says so`() {
        showAdd(AddFromLinkViewModel.Step.Ready(preview()), lands = AddFromLinkViewModel.Lands.AFTER_OTHERS)
        rule.onNodeWithText("Stash asks it after your other sources. Drag it up to ask it first.").assertExists()
    }

    @Test fun `an add-on Stash doesn't run is added only by Add anyway on the sheet`() {
        showAdd(AddFromLinkViewModel.Step.Ready(preview()))
        rule.onNodeWithText("Add").performScrollTo().performClick()
        // The tap opened the sheet; nothing was added.
        assertThat(added).isEmpty()
        rule.onNodeWithText("Its server will see").assertExists()
        rule.onNodeWithText("The name, artist, album, length and recording code of each song you play and of the next song in your queue, and your internet address.").assertExists()
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

    @Test fun `Couldn't save is said once for each failure, and not again when the screen comes back`() {
        // review-885 N8: the count lives in the view model, so every return to the screen said it again.
        org.robolectric.shadows.ShadowToast.reset()
        var failures by mutableStateOf(1)
        val screen = androidx.compose.ui.test.junit4.StateRestorationTester(rule)
        screen.setContent { SaveFailedToast(failures) }
        rule.waitForIdle()
        assertThat(org.robolectric.shadows.ShadowToast.shownToastCount()).isEqualTo(1)
        assertThat(org.robolectric.shadows.ShadowToast.getTextOfLatestToast()).isEqualTo("Couldn't save. Try again.")
        // The phone is turned, or the screen is left and come back to: the same view model, the same count.
        screen.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertThat(org.robolectric.shadows.ShadowToast.shownToastCount()).isEqualTo(1)
        // Another change that could not be saved is said.
        failures = 2
        rule.waitForIdle()
        assertThat(org.robolectric.shadows.ShadowToast.shownToastCount()).isEqualTo(2)
    }

    @Test fun `after a refusal the same link can be read again`() {
        // review-885 M9: "Couldn't reach that add-on" on a shared link had no button left to try again.
        showAdd(AddFromLinkViewModel.Step.Refused(AddonLinks.Refusal.UNREACHABLE), link = "https://relay.example.org/")
        rule.onNodeWithText("Couldn't reach that add-on. Check the link and your connection.").assertExists()
        rule.onNodeWithText("Read link").assertIsEnabled().performClick()
        assertThat(reads).isEqualTo(1)
    }

    @Test fun `Add waits for the password an add-on asks for`() {
        // review-885 M9: Add with no password could only be refused, after the sheet had been agreed to.
        showAdd(AddFromLinkViewModel.Step.Ready(preview(trust = Trust.OFFICIAL, needsToken = true)))
        rule.onNodeWithText("Add").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag(ADDON_PASSWORD_FIELD_TAG).performScrollTo().performTextInput("s3cret")
        rule.onNodeWithText("Add").performScrollTo().assertIsEnabled().performClick()
        assertThat(added).containsExactly(Triple("hifi", "s3cret", false))
    }

    @Test fun `a refusal that comes from Add is said on the preview, which stays`() {
        showAdd(AddFromLinkViewModel.Step.Ready(preview(), refused = AddonLinks.Refusal.COULD_NOT_SAVE))
        rule.onNodeWithText("Couldn't save. Try again.").assertExists()
        rule.onNodeWithText("Hi-Fi Relay").assertExists()
        rule.onNodeWithText("Add").performScrollTo().assertIsEnabled()
    }

    @Test fun `on Replace link the sheet's button says Replace anyway`() {
        // review-885 N13.
        showAdd(AddFromLinkViewModel.Step.Ready(preview().copy(replacesKey = "k1")))
        rule.onNodeWithText("Replace").performScrollTo().performClick()
        assertThat(added).isEmpty()
        rule.onAllNodesWithText("Add anyway").assertCountEquals0()
        rule.onNodeWithText("Replace anyway").performSemanticsAction(SemanticsActions.OnClick)
        assertThat(added).containsExactly(Triple("hifi", null, true))
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
