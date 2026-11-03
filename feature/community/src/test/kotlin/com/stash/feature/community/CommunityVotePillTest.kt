package com.stash.feature.community

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.stash.core.model.community.CommunityPost
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The vote pill on a post that isn't heard yet (contract 2026-11-03), as the owner asked on 2026-11-03: no "Listen first"
 * words by it (the tap's note says why), the arrows dimmed but still taking the tap, TalkBack still told why on the
 * arrow, and the pill the same size as a heard post's, so nothing moves when a post wakes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommunityVotePillTest {
    @get:Rule val rule = createComposeRule()

    private fun post(heard: Boolean, myVote: Int = 0) = CommunityPost(
        id = if (heard) "HEARD001" else "WAITING1", kind = "song", title = "garden", name = "Maya", count = 1,
        createdAt = 1, up = 9, down = 0, myVote = myVote, heard = heard,
    )

    /** The arrow named [label] inside the pill tagged [tag]. */
    private fun arrow(tag: String, label: String) =
        rule.onNode(hasContentDescription(label) and hasAnyAncestor(hasTestTag(tag)))

    private val waitingWord = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Listen first")
    private val noWord = SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription)

    @Test fun `a post not heard yet shows no Listen first words, in either size`() {
        rule.setContent {
            MaterialTheme {
                Column {
                    VotePill(post(heard = false), onVote = {})
                    VotePill(post(heard = false), onVote = {}, compact = true)
                }
            }
        }
        // Neither as text on screen nor hiding in the tree (the old hint was cleared from TalkBack, so look unmerged too).
        rule.onAllNodesWithText("Listen first").assertCountEquals(0)
        rule.onAllNodesWithText("Listen first", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun `TalkBack still hears why on a dimmed arrow, and nothing extra once heard`() {
        rule.setContent {
            MaterialTheme {
                Column {
                    Box(Modifier.testTag("waiting")) { VotePill(post(heard = false), onVote = {}) }
                    Box(Modifier.testTag("heard")) { VotePill(post(heard = true), onVote = {}) }
                    // Your vote from before stays lit and can be taken back: only the other arrow waits.
                    Box(Modifier.testTag("voted")) { VotePill(post(heard = false, myVote = 1), onVote = {}) }
                }
            }
        }
        arrow("waiting", "Vote up").assert(waitingWord)
        arrow("waiting", "Vote down").assert(waitingWord)
        arrow("heard", "Vote up").assert(noWord)
        arrow("heard", "Vote down").assert(noWord)
        arrow("voted", "Vote up").assert(noWord)
        arrow("voted", "Vote down").assert(waitingWord)
    }

    @Test fun `a dimmed arrow still takes the tap, so the view model can say why`() {
        val votes = mutableListOf<Int>()
        rule.setContent { MaterialTheme { Box(Modifier.testTag("waiting")) { VotePill(post(heard = false), onVote = { votes += it }) } } }
        arrow("waiting", "Vote up").performClick()
        arrow("waiting", "Vote down").performClick()
        assertThat(votes).containsExactly(1, -1).inOrder()
    }

    @Test fun `a waiting pill is the same size as a heard one, so nothing shifts when it wakes`() {
        rule.setContent {
            MaterialTheme {
                Column {
                    Box(Modifier.testTag("waiting")) { VotePill(post(heard = false), onVote = {}) }
                    Box(Modifier.testTag("heard")) { VotePill(post(heard = true), onVote = {}) }
                    Box(Modifier.testTag("waitingCompact")) { VotePill(post(heard = false), onVote = {}, compact = true) }
                    Box(Modifier.testTag("heardCompact")) { VotePill(post(heard = true), onVote = {}, compact = true) }
                }
            }
        }
        fun size(tag: String) = rule.onNode(hasTestTag(tag)).getUnclippedBoundsInRoot().let { it.width to it.height }
        assertThat(size("waiting")).isEqualTo(size("heard"))
        assertThat(size("waitingCompact")).isEqualTo(size("heardCompact"))
        // The pill alone: 36 dp high, 32 dp compact (no line of words under it).
        assertThat(size("waiting").second.value).isWithin(0.5f).of(36f)
        assertThat(size("waitingCompact").second.value).isWithin(0.5f).of(32f)
    }
}
