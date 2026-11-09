package com.stash.feature.settings

import android.content.res.Resources
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.weblibrary.ImportSelection
import com.stash.core.data.weblibrary.ImportedLike
import com.stash.core.data.weblibrary.ImportedPlaylist
import com.stash.core.data.weblibrary.WebLibraryContent
import com.stash.core.data.weblibrary.WebLibraryFile
import com.stash.core.data.weblibrary.WebLibraryImportResult
import com.stash.core.data.weblink.handoff.WireSong
import io.mockk.every
import io.mockk.mockk
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

    private val res: Resources = mockk {
        every { getString(R.string.web_library_import_intro_this_file_has) } returns "This file has"
        every { getString(R.string.web_library_import_intro_body, any(), any(), any(), any()) } answers {
            val a = args[1] as Array<*>
            "${a[0]} ${a[1]}, ${a[2]} and ${a[3]}."
        }
        every { getString(R.string.web_library_import_result_added, any()) } answers {
            "Added ${(args[1] as Array<*>)[0]}."
        }
        every { getString(R.string.web_library_import_result_nothing_new) } returns "Nothing new: you already had all of it."
        every { getQuantityString(R.plurals.web_library_export_likes, any(), any()) } answers {
            val count = (args[2] as Array<*>)[0]
            val fmt = if (count is Number) "%,d".format(count) else count.toString()
            if (args[1] == 1) "1 like" else "$fmt likes"
        }
        every { getQuantityString(R.plurals.web_library_export_plays, any(), any()) } answers {
            val count = (args[2] as Array<*>)[0]
            val fmt = if (count is Number) "%,d".format(count) else count.toString()
            if (args[1] == 1) "1 play" else "$fmt plays"
        }
        every { getQuantityString(R.plurals.web_library_export_playlists, any(), any()) } answers {
            val count = (args[2] as Array<*>)[0]
            val fmt = if (count is Number) "%,d".format(count) else count.toString()
            if (args[1] == 1) "1 playlist" else "$fmt playlists"
        }
        every { getQuantityString(R.plurals.web_library_import_playlists_updated, any(), any()) } answers {
            val count = (args[2] as Array<*>)[0]
            val fmt = if (count is Number) "%,d".format(count) else count.toString()
            if (args[1] == 1) "new songs in 1 playlist you had" else "new songs in $fmt playlists you had"
        }
    }

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
        assertThat(WebLibraryImportViewModel.introOf(content, res)).isEqualTo("This file has 1,204 likes, 0 plays and 2 playlists.")
        assertThat(WebLibraryImportViewModel.resultText(WebLibraryImportResult(likesAdded = 40, playlistsAdded = 3, playlistsUpdated = 1, playsAdded = 1), res))
            .isEqualTo("Added 40 likes, 3 playlists, new songs in 1 playlist you had and 1 play.")
        assertThat(WebLibraryImportViewModel.resultText(WebLibraryImportResult(likesSkipped = 9), res)).isEqualTo("Nothing new: you already had all of it.")
    }
}
