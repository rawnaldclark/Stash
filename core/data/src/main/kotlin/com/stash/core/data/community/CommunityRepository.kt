package com.stash.core.data.community

import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.mapper.toDomain
import com.stash.core.data.prefs.HomeSectionsPreference
import com.stash.core.data.share.SharePreference
import com.stash.core.data.share.SharedMixRepository
import com.stash.core.data.share.cut
import com.stash.core.data.share.withinLimits
import com.stash.core.model.PlaylistType
import com.stash.core.model.Track
import com.stash.core.model.community.CommunityMe
import com.stash.core.model.community.CommunityPost
import com.stash.core.model.community.PostTarget
import com.stash.core.model.share.SharedTrack
import com.stash.core.model.share.toSharedTrackWithArt
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** What posting would send, or why it can't be posted (spec 2026-09-26 §3 Posting). */
sealed interface Draft {
    /** [kind] is "playlist", "mix" or "song"; [covers] are the playlist's, or the song's art; a song has one track. */
    data class Ready(val kind: String, val title: String, val covers: List<String>, val tracks: List<SharedTrack>) : Draft

    data class Problem(val message: String) : Draft
}

/**
 * Stash Community (spec 2026-09-26 §3): the lists, votes, take-downs, and building and sending a post.
 * The network calls return a [CommunityResult] and never throw; [draft], [post] and [recentSongs] can throw
 * on a database or DataStore failure.
 * While Community is off, the network calls send nothing and change nothing: each returns `Rejected("off")`.
 */
@Singleton
class CommunityRepository @Inject constructor(
    private val api: CommunityApiClient,
    private val keys: CommunityKeyStore,
    private val sharedMixRepository: SharedMixRepository,
    private val playlistDao: PlaylistDao,
    private val trackDao: TrackDao,
    private val sharePreference: SharePreference,
    private val homeSections: HomeSectionsPreference,
) {
    /** Home's last loaded list, shown at once on the next Home open while it reloads. */
    @Volatile var lastHome: List<CommunityPost>? = null

    private val _revision = MutableStateFlow(0)

    /** Goes up after a post or a take-down, so lists on screen reload. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** When this phone last sent a vote. Home reloads on its next showing after one (CommunityViewModel.onShown). */
    @Volatile var lastVoteAt: Long = 0L
        private set

    /** The ranked list. It carries the key only once this phone has one: reading never makes one. */
    suspend fun feed(limit: Int): CommunityResult<List<CommunityPost>> = whileOn { api.feed(limit, keys.existingKey()) }

    /** Your live posts, hidden ones included; none, without asking, before this phone has a key. */
    suspend fun mine(): CommunityResult<List<CommunityPost>> = whileOn {
        keys.existingKey()?.let { api.mine(it) } ?: CommunityResult.Ok(emptyList())
    }

    /**
     * The confirm sheet's limits. A phone with no key has posted nothing and can't be blocked, so it gets the
     * full limits without a request: opening the sheet never makes a key (the README says "the first time you
     * post or vote").
     */
    suspend fun me(): CommunityResult<CommunityMe> = whileOn {
        keys.existingKey()?.let { api.me(it) } ?: CommunityResult.Ok(CommunityMe(DAILY_POSTS, LIVE_POSTS, blocked = false))
    }

    suspend fun open(id: String): CommunityResult<CommunityPost> = whileOn { api.open(id, keys.existingKey()) }

    suspend fun vote(id: String, value: Int): CommunityResult<VoteCounts> = whileOn {
        // Before sending: a screen closed mid-vote cancels this call, maybe after the vote has landed.
        lastVoteAt = System.currentTimeMillis()
        api.vote(id, value, keys.key())
    }

    /** A post that's already gone counts as taken down: it's what the person wanted (e.g. a retry after a lost 204). */
    suspend fun takeDown(id: String): CommunityResult<Unit> = whileOn {
        api.takeDown(id, keys.key())
            .let { if (it is CommunityResult.Rejected && it.code == "gone") CommunityResult.Ok(Unit) else it }
            .also { if (it is CommunityResult.Ok) _revision.update { n -> n + 1 } }
    }

    suspend fun displayName(): String? = sharePreference.displayName()

    /** The picker's recent songs: what this phone actually played, newest first. */
    suspend fun recentSongs(): List<Track> = trackDao.getRecentlyPlayed(RECENT_SONGS).map { it.toDomain() }

    suspend fun draft(target: PostTarget): Draft = when (target) {
        is PostTarget.Song -> {
            // The queue and Now Playing hold a copy rebuilt from the player, without ISRC or Spotify id and
            // sometimes with a local art path. Post the library row, as Share does (NowPlayingViewModel.onShareCurrent).
            val track = trackDao.getById(target.track.id)?.toDomain() ?: target.track
            val song = track.toSharedTrackWithArt().withinLimits()
            Draft.Ready("song", song.title.trim().cut(100), listOfNotNull(song.artUrl), listOf(song))
        }
        is PostTarget.Playlist -> playlistDraft(target.playlistId)
    }

    private suspend fun playlistDraft(playlistId: Long): Draft {
        val playlist = playlistDao.getById(playlistId) ?: return Draft.Problem("This playlist is empty.")
        // It maps every song and checks each art link, and the confirm sheet (PostComposerViewModel.open) calls
        // draft() from the main thread.
        val doc = withContext(Dispatchers.Default) {
            sharedMixRepository.buildDocument(playlistId, playlist.name, sharedBy = null, withArt = true)
        }
        return when {
            doc.tracks.isEmpty() -> Draft.Problem("This playlist is empty.")
            doc.tracks.size > MAX_POST_TRACKS -> Draft.Problem("Too many songs to post ($MAX_POST_TRACKS at most).")
            else -> Draft.Ready(
                kind = if (playlist.type in MIX_TYPES) "mix" else "playlist",
                title = doc.name.ifBlank { "Playlist" },
                covers = doc.covers,
                tracks = doc.tracks,
            )
        }
    }

    /** Sends [draft] under [name], cut to the Worker's 40 characters and remembered as "Show my name as". */
    suspend fun post(draft: Draft.Ready, name: String): CommunityResult<String> = whileOn {
        val poster = name.trim().cut(40)
        // The Worker refuses a blank name too, and saving "" would wipe the name mixes and Listen Together use.
        if (poster.isEmpty()) return@whileOn CommunityResult.Rejected("bad_request")
        sharePreference.setDisplayName(poster)
        val body = if (draft.kind == "song") NewPost.Body(track = draft.tracks.single())
        else NewPost.Body(covers = draft.covers, tracks = draft.tracks)
        api.create(NewPost(draft.kind, poster, draft.title, body), keys.key())
            .also { if (it is CommunityResult.Ok) _revision.update { n -> n + 1 } }
    }

    /** Community off: no request leaves the phone (spec §3), whichever screen asks. */
    private suspend fun <T> whileOn(call: suspend () -> CommunityResult<T>): CommunityResult<T> =
        if (homeSections.communityOn.first()) call() else CommunityResult.Rejected("off")

    companion object {
        const val HOME_SIZE = 5
        const val ALL_SIZE = 100
        private const val MAX_POST_TRACKS = 500
        /** The Worker's per-phone limits (DAILY_POSTS, LIVE_POSTS in infra/share-worker/src/community.js). */
        private const val DAILY_POSTS = 2
        private const val LIVE_POSTS = 5
        private const val RECENT_SONGS = 20
        /** Posted as "mix". The picker labels playlists from this too, so the label and the kind can't drift. */
        val MIX_TYPES = setOf(PlaylistType.DAILY_MIX, PlaylistType.STASH_MIX)
    }
}
