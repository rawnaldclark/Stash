package com.stash.core.data.weblibrary

import com.stash.core.data.weblink.handoff.WireSong
import com.stash.core.model.weblink.SongIdentity
import com.stash.core.model.weblink.SongIndex
import com.stash.core.model.weblink.SongKey

/**
 * What to take from a file or a send (link-sync spec §2.3, the import picker): likes yes/no, plays yes/no, and which of its
 * playlists by their id in the file. [playlistIds] null means all of them.
 */
data class ImportSelection(
    val likes: Boolean = true,
    val plays: Boolean = true,
    val playlistIds: Set<String>? = null,
) {
    fun includes(playlistId: String): Boolean = playlistIds == null || playlistId in playlistIds

    val isEmpty: Boolean get() = !likes && !plays && playlistIds?.isEmpty() == true

    companion object {
        val ALL = ImportSelection()
    }
}

/** A playlist of this phone that an incoming one merges into (its songs as identities), found by the importer. */
data class LocalPlaylist(val id: Long, val items: List<SongIdentity>)

/**
 * The library as the merge needs it: what is liked here (Stash likes and Liked Songs), the plays already here
 * (`textKey|playedAt`), the playlists the incoming ones map to (by the file's id), and the shared mixes this phone follows
 * or owns (by share id).
 */
data class LocalLibrary(
    val likes: List<SongIdentity>,
    val playKeys: Set<String>,
    val targets: Map<String, LocalPlaylist>,
    val followedShareIds: Set<String>,
)

/** What happens to one incoming playlist. */
sealed interface PlaylistAction {
    val playlist: ImportedPlaylist

    /** New here: added whole. */
    data class Create(override val playlist: ImportedPlaylist) : PlaylistAction

    /** Already here ([localId]): gains [songs], the ones it doesn't have, at the end (none: nothing changes). */
    data class Append(override val playlist: ImportedPlaylist, val localId: Long, val songs: List<WireSong>) : PlaylistAction

    /** A shared mix someone else owns, not followed here yet: followed (or, if the share service can't be reached, copied). */
    data class Follow(override val playlist: ImportedPlaylist) : PlaylistAction

    /** A shared mix already followed (or owned) here: left alone, its owner's next version brings it up to date. */
    data class Skip(override val playlist: ImportedPlaylist) : PlaylistAction
}

/** The merge, decided before anything is written: adds only, never removes or reorders (library-file v1 "Merging"). */
data class MergePlan(
    val likes: List<ImportedLike>,
    val likesSkipped: Int,
    val playlists: List<PlaylistAction>,
    val plays: List<ImportedPlay>,
    val playsSkipped: Int,
)

/**
 * The importer's rules, pure (library-file v1 "Merging", spec §3 identity):
 * - **Likes** by identity (`sameSong`): a song already liked here, under either key, is skipped; a song liked twice in the file
 *   is taken once.
 * - **Playlists** by id (a followed mix by its share id). A new one is added whole; one already here gains only the songs it
 *   doesn't have (by `sameSong`, occurrence by occurrence), at the end; a followed mix already here is left alone.
 * - **Plays** by the song's words and `playedAt`: the same play is never added twice.
 */
object WebLibraryMergePlanner {
    fun playKey(song: SongIdentity, playedAt: Long): String = "${SongKey.textKey(song)}|$playedAt"

    fun plan(content: WebLibraryContent, selection: ImportSelection, local: LocalLibrary): MergePlan {
        val likes = ArrayList<ImportedLike>()
        var likesSkipped = 0
        if (selection.likes) {
            val liked = SongIndex<Boolean>().apply { local.likes.forEach { add(it, true) } }
            for (l in content.likes) {
                if (liked.has(l.song)) {
                    likesSkipped++
                } else {
                    likes += l
                    liked.add(l.song, true)
                }
            }
        }

        val playlists = content.playlists.filter { selection.includes(it.id) }.map { p ->
            val follow = p.follow
            val target = local.targets[p.id]
            when {
                follow != null && follow.id in local.followedShareIds -> PlaylistAction.Skip(p)
                follow != null -> PlaylistAction.Follow(p)
                target != null -> PlaylistAction.Append(p, target.id, missingSongs(target.items, p.items))
                else -> PlaylistAction.Create(p)
            }
        }

        val plays = ArrayList<ImportedPlay>()
        var playsSkipped = 0
        if (selection.plays) {
            val seen = HashSet(local.playKeys)
            for (h in content.history) {
                if (seen.add(playKey(h.song, h.playedAt))) plays += h else playsSkipped++
            }
        }
        return MergePlan(likes, likesSkipped, playlists, plays, playsSkipped)
    }

    /**
     * The songs of [incoming] that [have] doesn't hold, in order. Matched occurrence by occurrence (sync-v1 §2.2): a song twice
     * in the file and once here adds one more.
     */
    fun missingSongs(have: List<SongIdentity>, incoming: List<WireSong>): List<WireSong> {
        // Keys worked out once and indexed, so two 10,000-song lists don't take 10⁸ comparisons.
        val keys = have.map(SongKey::keyOf)
        val texts = have.map(SongKey::textKey)
        val isrcs = have.map(SongKey::isrcOf)
        val byKey = HashMap<String, MutableList<Int>>()
        val byText = HashMap<String, MutableList<Int>>()
        have.indices.forEach { i ->
            byKey.getOrPut(keys[i]) { mutableListOf() } += i
            byText.getOrPut(texts[i]) { mutableListOf() } += i
        }
        val used = BooleanArray(have.size)
        val out = ArrayList<WireSong>()
        for (s in incoming) {
            val k = SongKey.keyOf(s)
            val t = SongKey.textKey(s)
            val isrc = SongKey.isrcOf(s)
            // The lowest-index occurrence here that is the same song (sameSong: same key, or same words with at most one ISRC).
            val i = (byKey[k].orEmpty() + byText[t].orEmpty()).filter { j ->
                !used[j] && (keys[j] == k || (texts[j] == t && (isrcs[j] == null || isrc == null)))
            }.minOrNull()
            if (i != null) used[i] = true else out += s
        }
        return out
    }
}
