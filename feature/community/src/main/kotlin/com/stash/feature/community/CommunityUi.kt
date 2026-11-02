package com.stash.feature.community

import androidx.annotation.StringRes
import com.stash.core.model.community.CommunityPost
import com.stash.feature.community.CommunityViewModel.Tab

/*
 * What the Community screens show, worked out from the view model's state with no Android in it, so the
 * rules are unit-tested (CommunityUiTest). The composables only draw these.
 */

/**
 * See all's three lists. Top and New are the same Worker list (`/feed`, ranked by votes with age, up to 100
 * posts): New is it newest first, so no API change and no second request when switching between the two.
 * Your posts is `/mine`.
 */
internal enum class Segment(val tab: Tab, @param:StringRes val label: Int) {
    TOP(Tab.ALL, R.string.community_segment_top),
    NEW(Tab.ALL, R.string.community_segment_new),
    MINE(Tab.MINE, R.string.community_segment_mine),
}

/** One post in a list: its [rank] on the chart, or null where ranks mean nothing (New, Your posts). */
internal data class RankedPost(val rank: Int?, val post: CommunityPost)

/** What a Community list shows instead of, or with, its posts. */
internal sealed interface ListUi {
    /** Nothing loaded yet: skeletons. */
    data object Loading : ListUi

    /** Nothing loaded and the last try failed: an error card with Retry. */
    data object Failed : ListUi

    /** Loaded, and there's nothing to show. */
    data object Empty : ListUi

    /** [hero] is the #1 (Home and Top), shown as a card; [rows] are the rest. */
    data class Ready(val hero: CommunityPost?, val rows: List<RankedPost>) : ListUi
}

/**
 * The list [state] shows on [segment] (null: Home's section). A state still holding another tab's list (the frame
 * between a segment tap and the load it starts) reads as loading, so Your posts never flashes the chart's posts.
 */
internal fun listUi(state: CommunityViewModel.UiState, segment: Segment?): ListUi {
    val tab = segment?.tab ?: Tab.HOME
    val posts = state.posts
    return when {
        state.tab != tab -> ListUi.Loading
        posts == null -> if (state.failed) ListUi.Failed else ListUi.Loading
        posts.isEmpty() -> ListUi.Empty
        segment == Segment.NEW -> ListUi.Ready(hero = null, rows = posts.sortedByDescending { it.createdAt }.map { RankedPost(null, it) })
        segment == Segment.MINE -> ListUi.Ready(hero = null, rows = posts.map { RankedPost(null, it) })
        // Home and Top: the chart. The #1 is the hero; the rest keep their places from 2.
        else -> ListUi.Ready(hero = posts.first(), rows = posts.drop(1).mapIndexed { i, p -> RankedPost(i + 2, p) })
    }
}

/** Play all / Shuffle (a taste of the chart) belongs to the chart: Top only, once it has posts. */
internal fun showsChartPlay(segment: Segment, ui: ListUi): Boolean = segment == Segment.TOP && ui is ListUi.Ready

/** The vote pill as drawn: the score, which arrow is lit (this phone's vote), and whether it takes taps (not on your own post). */
internal data class VotePillUi(val score: Int, val upLit: Boolean, val downLit: Boolean, val enabled: Boolean)

internal fun CommunityPost.votePill() = VotePillUi(score = up - down, upLit = myVote == 1, downLit = myVote == -1, enabled = !mine)

/** How long ago a post went up, in the unit a list shows it in. */
internal sealed interface Age {
    data object JustNow : Age
    data class Minutes(val n: Int) : Age
    data class Hours(val n: Int) : Age
    data class Days(val n: Int) : Age
}

/** The age of something made at [then], counted to [now]. A clock behind [then] reads as just now. */
internal fun ageOf(then: Long, now: Long): Age {
    val minutes = ((now - then).coerceAtLeast(0) / 60_000).toInt()
    return when {
        minutes < 1 -> Age.JustNow
        minutes < 60 -> Age.Minutes(minutes)
        minutes < 24 * 60 -> Age.Hours(minutes / 60)
        else -> Age.Days(minutes / (24 * 60))
    }
}

/** Whole days until [expiresAt] (Your posts' "Leaves in 26 days"); 0 is today, null when the Worker didn't say. */
internal fun daysLeft(expiresAt: Long?, now: Long): Int? = expiresAt?.let { ((it - now).coerceAtLeast(0) / 86_400_000L).toInt() }

@StringRes
internal fun kindLabel(kind: String): Int = when (kind) {
    "mix" -> R.string.community_kind_mix
    "song" -> R.string.community_kind_song
    else -> R.string.community_kind_playlist
}

/** A playlist or mix post's length in whole minutes (0 when no song says how long it is). */
internal fun CommunityPost.lengthMinutes(): Long = tracks.sumOf { it.durationMs ?: 0L } / 60_000

internal fun CommunityPost.coverUrls(): List<String> = if (kind == "song") listOfNotNull(art) else covers
