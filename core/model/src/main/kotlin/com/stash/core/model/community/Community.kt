package com.stash.core.model.community

import com.stash.core.model.Track
import com.stash.core.model.share.SharedTrack
import kotlinx.serialization.Serializable

/** What any "Post to Community" entry point asks to post (spec 2026-09-26 §3). */
sealed interface PostTarget {
    data class Song(val track: Track) : PostTarget
    data class Playlist(val playlistId: Long) : PostTarget
}

/**
 * A Community post as the Worker sends it (spec §2 Routes). Lists leave [tracks] and [track] empty; an
 * opened post fills [tracks] (a playlist or mix) or [track] (a song). [myVote] and [mine] are only set when
 * the request carried this phone's key; [hidden] and [expiresAt] only in Mine.
 */
@Serializable
data class CommunityPost(
    val id: String,
    /** "playlist", "mix" or "song". */
    val kind: String,
    val title: String,
    /** The poster's "Show my name as". */
    val name: String,
    val count: Int,
    val covers: List<String> = emptyList(),
    val art: String? = null,
    val artist: String? = null,
    val createdAt: Long,
    val up: Int,
    val down: Int,
    val myVote: Int = 0,
    val mine: Boolean = false,
    val hidden: Boolean = false,
    val expiresAt: Long? = null,
    val tracks: List<SharedTrack> = emptyList(),
    val track: SharedTrack? = null,
)

/** `GET /v1/community/me`: what the confirm sheet shows before posting. */
@Serializable
data class CommunityMe(val postsLeftToday: Int, val spotsFree: Int, val blocked: Boolean)
