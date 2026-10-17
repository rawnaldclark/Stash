package com.stash.feature.library.allsongs

import com.stash.core.data.db.dao.ScannedSongStamp
import com.stash.data.download.files.ImportedAudioType

/*
 * The All Songs scanner's decisions, kept free of Android so they are unit-tested (ScanRulesTest):
 * which files count as playable audio, which folders may be added, and what a rescan reads again.
 */

/**
 * A SAF tree as the scanner compares them: its provider [authority] and the [docId] of the folder
 * the tree was granted on (`DocumentsContract.getTreeDocumentId`).
 */
internal data class TreeRef(val authority: String, val docId: String)

/**
 * True when the document [child] is [parent] or lies under it, on the same provider.
 *
 * Path-shaped document ids (the external-storage provider's `primary:Music/Rock`, which is where
 * nearly every music folder lives) nest by prefix; the `/` boundary keeps `primary:Music2` out of
 * `primary:Music`. A volume root (`primary:`) ends in `:` and holds everything on the volume.
 * Providers with opaque ids (`msf:123`) can't be compared this way and never match unless equal:
 * the worst case there is a song listed twice, never a folder wrongly refused.
 */
internal fun TreeRef.contains(child: TreeRef): Boolean {
    if (authority != child.authority) return false
    if (docId == child.docId) return true
    val prefix = if (docId.endsWith(":") || docId.endsWith("/")) docId else "$docId/"
    return child.docId.startsWith(prefix)
}

/** Why a folder can't be added, as the message the user sees. */
internal sealed class FolderConflict(val message: String) {
    /** The tree is Stash's download folder, or inside it: those songs are the Library's already. */
    data object DownloadFolder : FolderConflict(
        "That's where Stash saves its downloads. Those songs are already in your Library.",
    )

    /** The tree is inside a folder All Songs already scans: every song in it is listed already. */
    data class InsideAdded(val name: String) : FolderConflict(
        "That folder is inside “$name”, which is already added.",
    )

    /** The tree holds a folder All Songs already scans: its songs would be listed twice. */
    data class ContainsAdded(val name: String) : FolderConflict(
        "“$name” is already added and is inside that folder. Remove it first to add this one.",
    )
}

/**
 * Whether [candidate] can join the scanned folders: not Stash's own download tree ([downloadTree],
 * null when downloads stay in app storage) or a folder inside it, and neither inside nor around a
 * folder already added ([added], each with its display name), since overlapping trees list the same
 * file twice (the document URIs embed the tree, so they don't dedupe). Null when it can be added.
 *
 * A folder that CONTAINS the download tree is allowed; the scan skips the download tree inside it
 * ([downloadSubtreeToSkip]).
 */
internal fun folderConflict(
    candidate: TreeRef,
    added: List<Pair<TreeRef, String>>,
    downloadTree: TreeRef?,
): FolderConflict? {
    if (downloadTree != null && downloadTree.contains(candidate)) return FolderConflict.DownloadFolder
    for ((tree, name) in added) {
        if (tree.contains(candidate)) return FolderConflict.InsideAdded(name)
        if (candidate.contains(tree)) return FolderConflict.ContainsAdded(name)
    }
    return null
}

/**
 * The document id of the download tree when it sits strictly inside [scanned], so the walk skips
 * it: those files are the Library's own downloads and would otherwise be listed again here.
 */
internal fun downloadSubtreeToSkip(scanned: TreeRef, downloadTree: TreeRef?): String? =
    downloadTree?.takeIf { it != scanned && scanned.contains(it) }?.docId

/**
 * The extension a listed file plays as, or null when it isn't audio the player plays (the import
 * allowlist, [ImportedAudioType.EXTENSIONS]). A provider's audio MIME type decides first; a
 * `video/...` or other specific non-audio type is refused whatever the name says (a `.mp4` music
 * video); a missing or generic type falls back to the file name.
 */
internal fun playableAudioExtension(name: String, mime: String?): String? {
    val byName = ImportedAudioType.forDisplayName(name)
    val byMime = ImportedAudioType.forMime(mime)
    if (byMime != null) return byName ?: byMime
    val generic = mime.isNullOrBlank() || mime == "application/octet-stream"
    return if (generic) byName else null
}

/** A playable file as the walk listed it. [size] 0 means the provider didn't say. */
internal data class ListedFile(val uri: String, val name: String, val ext: String, val size: Long, val lastModified: Long)

/** What one rescan of a folder does. */
internal data class RescanPlan(
    /** Files with no row yet: tags read, rows inserted. */
    val fresh: List<ListedFile>,
    /** Files whose size or modified time moved since the last read: tags re-read, rows updated in place. */
    val changed: List<Pair<ScannedSongStamp, ListedFile>>,
    /** Rows whose file the listing no longer has: deleted. Empty when the listing was incomplete. */
    val gone: List<String>,
)

/**
 * Compares a folder's rows ([known]) with what the walk found ([listed]).
 *
 * When [listingComplete] is false a subfolder couldn't be listed (a provider hiccup, a card
 * mid-mount), so a missing file is "unknown", not "deleted": nothing is removed this time. New and
 * changed files are still read.
 */
internal fun planRescan(known: List<ScannedSongStamp>, listed: List<ListedFile>, listingComplete: Boolean): RescanPlan {
    val byUri = known.associateBy { it.uri }
    val fresh = ArrayList<ListedFile>()
    val changed = ArrayList<Pair<ScannedSongStamp, ListedFile>>()
    for (f in listed) {
        val stamp = byUri[f.uri]
        when {
            stamp == null -> fresh += f
            stamp.fileSizeBytes != f.size || stamp.lastModified != f.lastModified -> changed += stamp to f
        }
    }
    val gone = if (!listingComplete) {
        emptyList()
    } else {
        val found = listed.mapTo(HashSet()) { it.uri }
        known.filter { it.uri !in found }.map { it.uri }
    }
    return RescanPlan(fresh = fresh, changed = changed, gone = gone)
}
