package com.stash.feature.settings.addons

import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.common.truth.Truth.assertThat
import com.stash.core.addons.Trust
import com.stash.data.download.lossless.addon.AddonLinks
import com.stash.data.download.lossless.addon.AddonOverview
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Add from a link opened the way a link shared to Stash opens it: in a real NavHost, with its view
 * model kept by its back stack entry (as `hiltViewModel()` keeps it), and a second link shared while
 * it is on top navigated to single top, as StashScaffold does.
 *
 * Seen on the Pixel 5: the second link never reached the field and was never read. Navigation keeps
 * the entry for a single-top navigation and draws the screen again from that entry's saved state, so
 * the first link's field and its "already read" mark came back (b485edbb had keyed them with
 * `rememberSaveable(initialLink)`, which a restored value ignores).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class AddFromLinkSharedTest {

    @get:Rule
    val rule = createComposeRule()

    private val overview: AddonOverview = mockk(relaxed = true) { every { state } returns MutableStateFlow(AddonOverview.State(loaded = true)) }
    private val links: AddonLinks = mockk(relaxed = true) { every { lastAdded } returns MutableStateFlow<String?>(null) }

    private fun preview(name: String, host: String) = AddonLinks.Preview(
        manifestUrl = "https://$host/manifest.json", id = name.lowercase(), name = name, version = "1.0.0",
        description = null, host = host, uses = listOf(AddonLinks.Use.PLAYS_SONGS), needsToken = false,
        trust = Trust.UNOFFICIAL, sees = emptySet(), needsConsent = true, playsSongs = true,
    )

    private val first = "https://one.example.org/manifest.json"
    private val second = "https://two.example.org/tok-b/manifest.json"

    /** The link field holding exactly [text]: the field, not a line that happens to contain it. */
    private fun fieldHolding(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text))

    /** The app's SettingsAddonAddRoute(link, replace), as Navigation writes a typed route out. */
    private val addRoute = "settings_addon_add?link={link}&replace={replace}"
    private fun addRouteFor(link: String) = "settings_addon_add?link=" + Uri.encode(link)

    @Test fun `a second link shared while Add from a link is open replaces the first, and only the second is read`() {
        // The first link's host is slow: its read is still under way when the second link comes.
        var firstCancelled = false
        coEvery { links.preview(first, any()) } coAnswers {
            try {
                awaitCancellation()
            } finally {
                firstCancelled = true
            }
        }
        coEvery { links.preview(second, any()) } returns AddonLinks.Outcome.Ready(preview("Two", "two.example.org"))
        lateinit var nav: NavHostController
        rule.setContent {
            nav = rememberNavController()
            MaterialTheme {
                NavHost(nav, startDestination = "settings_addons") {
                    composable("settings_addons") { Text("Add-ons") }
                    composable(
                        addRoute,
                        arguments = listOf(
                            navArgument("link") { type = NavType.StringType; nullable = true; defaultValue = null },
                            navArgument("replace") { type = NavType.StringType; nullable = true; defaultValue = null },
                        ),
                    ) { entry ->
                        AddFromLinkScreen(
                            onBack = {},
                            onAdded = {},
                            initialLink = entry.arguments?.getString("link"),
                            replacingKey = entry.arguments?.getString("replace"),
                            viewModel = viewModel { AddFromLinkViewModel(links, overview) },
                        )
                    }
                }
            }
        }

        // Share → Stash, as StashScaffold opens it.
        rule.runOnIdle { nav.navigate(addRouteFor(first)) { launchSingleTop = true } }
        rule.onNode(fieldHolding(first)).assertExists()
        rule.onNodeWithText("Reading the add-on…").assertExists()
        val opened = rule.runOnIdle { nav.currentBackStackEntry!!.id }

        // Another link shared to Stash while the screen is on top.
        rule.runOnIdle { nav.navigate(addRouteFor(second)) { launchSingleTop = true } }
        rule.waitForIdle()

        // Still one screen, the same entry (single top): the new link took it over.
        assertThat(rule.runOnIdle { nav.currentBackStackEntry!!.id }).isEqualTo(opened)
        assertThat(rule.runOnIdle { nav.currentBackStack.value.count { it.destination.route == addRoute } }).isEqualTo(1)
        rule.onNode(fieldHolding(second)).assertExists()
        rule.onAllNodesWithText(first).assertCountEquals0()
        // The second link was read and its preview is the one shown; the first link's read was dropped.
        rule.onNodeWithText("Two").assertExists()
        rule.onNodeWithText("Not made by Stash · two.example.org").assertExists()
        coVerify(exactly = 1) { links.preview(second, null) }
        coVerify(exactly = 1) { links.preview(first, null) }
        assertThat(firstCancelled).isTrue()
    }

    @Test fun `the same link drawn again keeps what was typed, and is not read again`() {
        // A rotation, or a return to Stash after it was put away: the screen is drawn again for the same link.
        coEvery { links.preview(any(), any()) } returns AddonLinks.Outcome.Refused(AddonLinks.Refusal.UNREACHABLE)
        val vm = AddFromLinkViewModel(links, overview)
        val screen = StateRestorationTester(rule)
        screen.setContent {
            MaterialTheme { AddFromLinkScreen(onBack = {}, onAdded = {}, initialLink = first, viewModel = vm) }
        }
        rule.onNode(fieldHolding(first)).performTextReplacement("https://one.example.org/edited/manifest.json")

        screen.emulateSavedInstanceStateRestore()
        rule.waitForIdle()

        rule.onNode(fieldHolding("https://one.example.org/edited/manifest.json")).assertExists()
        coVerify(exactly = 1) { links.preview(first, null) }
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals0() {
    assertThat(fetchSemanticsNodes()).isEmpty()
}
