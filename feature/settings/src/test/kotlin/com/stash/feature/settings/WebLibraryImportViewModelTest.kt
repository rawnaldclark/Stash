package com.stash.feature.settings

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.weblibrary.ImportSelection
import com.stash.core.data.weblibrary.ImportedLike
import com.stash.core.data.weblibrary.ImportedPlaylist
import com.stash.core.data.weblibrary.WebLibraryContent
import com.stash.core.data.weblibrary.WebLibraryFile
import com.stash.core.data.weblibrary.WebLibraryImportResult
import com.stash.core.data.weblink.handoff.WireSong
import org.junit.Test

/** The import picker's words and choice (link-sync spec §2.3). */
class WebLibraryImportViewModelTest {
    private val content = WebLibraryContent(
        likes = List(1_204) { ImportedLike(WireSong("S$it", "A"), 1) },
        playlists = listOf(
            ImportedPlaylist("a", "Gym", listOf(WireSong("x", "y")), 1, 1),
            ImportedPlaylist("b", "Robin's mix", emptyList(), 1, 1, WebLibraryFile.Follow("Fw12ab34", 3)),
        ),
        history = emptyList(),
    )

    @Test fun `the picker starts with everything the file holds, and a part with nothing in it can't be taken`() {
        val pick = WebLibraryImportViewModel.pickOf(content)
        assertThat(pick.playlists.map { it.sharedMix }).containsExactly(false, true).inOrder()
        assertThat(pick.takesLikes).isTrue()
        assertThat(pick.takesPlays).isFalse()
        assertThat(WebLibraryImportViewModel.selectionOf(pick.copy(ticked = setOf("b"))))
            .isEqualTo(ImportSelection(likes = true, plays = false, playlistIds = setOf("b")))
        assertThat(pick.copy(likesOn = false, ticked = emptySet()).isEmpty).isTrue()
    }

    @Test fun `the words`() {
        assertThat(WebLibraryImportViewModel.introOf(content)).isEqualTo("This file has 1,204 likes, 0 plays and 2 playlists.")
        assertThat(WebLibraryImportViewModel.resultText(WebLibraryImportResult(likesAdded = 40, playlistsAdded = 3, playlistsUpdated = 1, playsAdded = 1)))
            .isEqualTo("Added 40 likes, 3 playlists, new songs in 1 playlist you had and 1 play.")
        assertThat(WebLibraryImportViewModel.resultText(WebLibraryImportResult(likesSkipped = 9))).isEqualTo("Nothing new: you already had all of it.")
    }
}
