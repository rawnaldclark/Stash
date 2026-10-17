package com.stash.feature.library.allsongs

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.ScannedSongStamp
import com.stash.core.data.db.entity.ScannedSongEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.isScannedSong
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * The All Songs scanner's rules: no overlapping folders and not Stash's own download folder (review C6),
 * files re-read when they change (C7), a failed listing never deletes (N3), the playable allowlist.
 */
class ScanRulesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val covers by lazy { ScannedCoverStore(tmp.newFolder("scanned_art")) }

    private val ext = "com.android.externalstorage.documents"
    private fun tree(docId: String, authority: String = ext) = TreeRef(authority, docId)

    // ── C6: folders ──────────────────────────────────────────────────────

    @Test fun `nesting follows the path, with a boundary at the slash`() {
        assertThat(tree("primary:Music").contains(tree("primary:Music/Rock"))).isTrue()
        assertThat(tree("primary:Music").contains(tree("primary:Music"))).isTrue()
        assertThat(tree("primary:").contains(tree("primary:Music"))).isTrue()
        assertThat(tree("primary:Music").contains(tree("primary:Music2"))).isFalse()
        assertThat(tree("primary:Music/Rock").contains(tree("primary:Music"))).isFalse()
        assertThat(tree("primary:Music").contains(tree("1234-ABCD:Music"))).isFalse()
        assertThat(tree("primary:Music").contains(tree("primary:Music", authority = "other"))).isFalse()
    }

    @Test fun `a folder inside an added one is refused, naming it`() {
        val conflict = folderConflict(tree("primary:Music/Rock"), listOf(tree("primary:Music") to "Music"), null)
        assertThat(conflict).isEqualTo(FolderConflict.InsideAdded("Music"))
    }

    @Test fun `a folder around an added one is refused`() {
        val conflict = folderConflict(tree("primary:"), listOf(tree("primary:Music/Rock") to "Rock"), null)
        assertThat(conflict).isEqualTo(FolderConflict.ContainsAdded("Rock"))
    }

    @Test fun `the download folder and anything inside it are refused`() {
        val downloads = tree("primary:Music/Stash")
        assertThat(folderConflict(tree("primary:Music/Stash"), emptyList(), downloads)).isEqualTo(FolderConflict.DownloadFolder)
        assertThat(folderConflict(tree("primary:Music/Stash/Artist"), emptyList(), downloads)).isEqualTo(FolderConflict.DownloadFolder)
    }

    @Test fun `a folder holding the download folder is allowed and skips it`() {
        val downloads = tree("primary:Music/Stash")
        assertThat(folderConflict(tree("primary:Music"), emptyList(), downloads)).isNull()
        assertThat(downloadSubtreeToSkip(tree("primary:Music"), downloads)).isEqualTo("primary:Music/Stash")
        assertThat(downloadSubtreeToSkip(tree("primary:Podcasts"), downloads)).isNull()
        assertThat(downloadSubtreeToSkip(tree("primary:Music"), null)).isNull()
    }

    @Test fun `separate folders are allowed side by side`() {
        val added = listOf(tree("primary:Music") to "Music", tree("1234-ABCD:Music") to "SD Music")
        assertThat(folderConflict(tree("primary:Podcasts"), added, tree("primary:Download/Stash"))).isNull()
    }

    // ── Playable allowlist ───────────────────────────────────────────────

    @Test fun `audio the player plays is listed, by MIME type first`() {
        assertThat(playableAudioExtension("Song.flac", "audio/flac")).isEqualTo("flac")
        assertThat(playableAudioExtension("Song.mp3", "audio/mpeg")).isEqualTo("mp3")
        assertThat(playableAudioExtension("Song", "audio/mp4")).isEqualTo("m4a")
        assertThat(playableAudioExtension("Song.opus", null)).isEqualTo("opus")
        assertThat(playableAudioExtension("Song.ogg", "application/octet-stream")).isEqualTo("ogg")
    }

    @Test fun `other audio types, videos and documents are not`() {
        assertThat(playableAudioExtension("Song.wma", "audio/x-ms-wma")).isNull()
        assertThat(playableAudioExtension("Song.mid", "audio/midi")).isNull()
        assertThat(playableAudioExtension("Clip.mp4", "video/mp4")).isNull()
        assertThat(playableAudioExtension("cover.jpg", "image/jpeg")).isNull()
        assertThat(playableAudioExtension("notes.txt", null)).isNull()
    }

    // ── C7 / N3: rescans ─────────────────────────────────────────────────

    private fun stamp(id: Long, uri: String, size: Long = 100, modified: Long = 10) =
        ScannedSongStamp(id = id, uri = uri, fileSizeBytes = size, lastModified = modified, dateAdded = 1)

    private fun file(uri: String, size: Long = 100, modified: Long = 10) =
        ListedFile(uri = uri, name = "$uri.mp3", ext = "mp3", size = size, lastModified = modified)

    @Test fun `a new file is read, an unchanged one is not, a gone one is removed`() {
        val plan = planRescan(
            known = listOf(stamp(1, "a"), stamp(2, "gone")),
            listed = listOf(file("a"), file("new")),
            listingComplete = true,
        )
        assertThat(plan.fresh.map { it.uri }).containsExactly("new")
        assertThat(plan.changed).isEmpty()
        assertThat(plan.gone).containsExactly("gone")
    }

    @Test fun `a file whose size or modified time moved is re-read in place`() {
        val retagged = stamp(1, "retagged")
        val replaced = stamp(2, "replaced")
        val plan = planRescan(
            known = listOf(retagged, replaced, stamp(3, "same")),
            listed = listOf(file("retagged", modified = 99), file("replaced", size = 200), file("same")),
            listingComplete = true,
        )
        assertThat(plan.fresh).isEmpty()
        assertThat(plan.changed.map { it.first }).containsExactly(retagged, replaced).inOrder()
        assertThat(plan.changed.map { it.second.uri }).containsExactly("retagged", "replaced").inOrder()
        assertThat(plan.gone).isEmpty()
    }

    @Test fun `an incomplete listing removes nothing but still reads new and changed files`() {
        val plan = planRescan(
            known = listOf(stamp(1, "in-unreadable-subfolder"), stamp(2, "changed")),
            listed = listOf(file("changed", size = 5), file("new")),
            listingComplete = false,
        )
        assertThat(plan.gone).isEmpty()
        assertThat(plan.fresh.map { it.uri }).containsExactly("new")
        assertThat(plan.changed.map { it.second.uri }).containsExactly("changed")
    }

    // ── Row → player track ───────────────────────────────────────────────

    @Test fun `a scanned row plays as a local file in the scanned id range`() {
        val row = ScannedSongEntity(
            id = 7, folderId = 1, uri = "content://x/tree/t/document/a.flac", title = "T", artist = "A", album = "",
            durationMs = 1000, fileFormat = "flac", fileSizeBytes = 5, lastModified = 9, dateAdded = 3,
        )
        val track = row.toTrack(covers)
        assertThat(track.isScannedSong).isTrue()
        assertThat(track.source).isEqualTo(MusicSource.LOCAL)
        assertThat(track.isDownloaded).isTrue()
        assertThat(track.filePath).isEqualTo(row.uri)
        assertThat(track.fileFormat).isEqualTo("flac")
        assertThat(row.copy(id = 8).toTrack(covers).id).isNotEqualTo(track.id)
    }

    @Test fun `a stored cover name resolves to a path in the cover directory`() {
        val row = ScannedSongEntity(
            id = 1, folderId = 1, uri = "content://x/a.mp3", title = "T", artist = "A", album = "",
            durationMs = 1, fileFormat = "mp3", fileSizeBytes = 1, lastModified = 1, dateAdded = 1,
            albumArtPath = "scanned_abc.jpg",
        )
        assertThat(row.toTrack(covers).albumArtPath).isEqualTo(covers.file("scanned_abc.jpg").absolutePath)
        assertThat(row.copy(albumArtPath = "").toTrack(covers).albumArtPath).isNull()
        assertThat(row.copy(albumArtPath = null).toTrack(covers).albumArtPath).isNull()
    }
}
