package com.stash.feature.community

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.stash.core.model.community.CommunityPost
import com.stash.core.ui.ambient.contrastRatio
import com.stash.feature.community.CommunityViewModel.Tab

/*
 * What the Community screens show, worked out from the view model's state with no Android in it, so the
 * rules are unit-tested (CommunityUiTest). The composables only draw these.
 */

/**
 * See all's three lists. Top and New come from one Worker list (`/feed`, up to 100 posts), ordered here: the
 * Worker's own order is "hot" (votes plus age, 3.6 h a vote), which is neither a top nor a newest. So no API
 * change, and no second request when switching between the two. Your posts is `/mine`.
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

    /**
     * [hero] is the #1 (Home and Top), shown as a card; [rows] are the rest. [thisWeek] is false when the chart had
     * to reach past the last 7 days (fewer than [TOP_WEEK_MIN] posts in them): it then says "Top" and "#1", not
     * "this week".
     */
    data class Ready(val hero: CommunityPost?, val rows: List<RankedPost>, val thisWeek: Boolean = true) : ListUi
}

/** A week: what "this week" counts back from now. */
internal const val WEEK_MS = 7 * 86_400_000L

/** Fewer posts than this in the last week, and Top shows every live post instead (and stops saying "this week"). */
internal const val TOP_WEEK_MIN = 5

/** Home's rail: #2 to #5 after the hero. */
internal const val HOME_RAIL = 4

/** The chart: [posts] by score (up − down), the newer first between equals; [thisWeek] when it's the last 7 days' only. */
internal data class Chart(val posts: List<CommunityPost>, val thisWeek: Boolean)

/**
 * Top this week: the posts made in the last 7 days by score, then newest. With fewer than [TOP_WEEK_MIN] of
 * them it's every live post by score (the Worker keeps a post 30 days) and [Chart.thisWeek] is false. A post
 * dated after [now] (this phone's clock behind the server's) counts as this week.
 */
internal fun topChart(posts: List<CommunityPost>, now: Long): Chart {
    val byScore = compareByDescending<CommunityPost> { it.up - it.down }.thenByDescending { it.createdAt }
    val week = posts.filter { now - it.createdAt < WEEK_MS }
    return if (week.size >= TOP_WEEK_MIN) Chart(week.sortedWith(byScore), thisWeek = true) else Chart(posts.sortedWith(byScore), thisWeek = false)
}

/**
 * The list [state] shows on [segment] (null: Home's section), with "this week" counted back from [now]. A state
 * still holding another tab's list (the frame between a segment tap and the load it starts) reads as loading, so
 * Your posts never flashes the chart's posts.
 */
internal fun listUi(state: CommunityViewModel.UiState, segment: Segment?, now: Long): ListUi {
    val tab = segment?.tab ?: Tab.HOME
    val posts = state.posts
    return when {
        state.tab != tab -> ListUi.Loading
        posts == null -> if (state.failed) ListUi.Failed else ListUi.Loading
        posts.isEmpty() -> ListUi.Empty
        segment == Segment.NEW -> ListUi.Ready(hero = null, rows = posts.sortedByDescending { it.createdAt }.map { RankedPost(null, it) })
        segment == Segment.MINE -> ListUi.Ready(hero = null, rows = posts.map { RankedPost(null, it) })
        // Home and Top: the chart. The #1 is the hero; the rest keep their places from 2 (Home: up to #5).
        else -> {
            val chart = topChart(posts, now)
            val rest = chart.posts.drop(1).let { if (segment == null) it.take(HOME_RAIL) else it }
            ListUi.Ready(hero = chart.posts.first(), rows = rest.mapIndexed { i, p -> RankedPost(i + 2, p) }, thisWeek = chart.thisWeek)
        }
    }
}

/** Play all / Shuffle (a taste of the chart) belongs to the chart: Top only, once it has posts. */
internal fun showsChartPlay(segment: Segment, ui: ListUi): Boolean = segment == Segment.TOP && ui is ListUi.Ready

/** The chart in its order, hero first: what Play all plays. */
internal fun ListUi.Ready.chartPosts(): List<CommunityPost> = listOfNotNull(hero) + rows.map { it.post }

/*
 * The hero's ink. Its backdrop is the cover blurred, so a white cover makes a white backdrop: an even black layer
 * over it keeps the white text at 4.5:1 or more over any cover (CommunityUiTest checks white and sampled covers).
 */

/** The even black layer over the hero's blurred cover. */
internal const val HERO_SCRIM_ALPHA = 0.7f

/** The hero's quieter lines: what the post is, and who posted it and when (white at these alphas). */
internal const val HERO_FACTS_ALPHA = 0.8f
internal const val HERO_POSTER_ALPHA = 0.72f

/** WCAG contrast of white text at [textAlpha] on the hero over a blurred cover of [backdrop]'s colour. */
internal fun heroTextContrast(backdrop: Color, textAlpha: Float): Double {
    val ground = Color.Black.copy(alpha = HERO_SCRIM_ALPHA).compositeOver(backdrop)
    return contrastRatio(Color.White.copy(alpha = textAlpha).compositeOver(ground), ground)
}

/**
 * The vote pill as drawn: the score, which arrow is lit (this phone's vote), and whether it takes taps (not on your own
 * post). Until the post is heard ([listenFirst]: 30 s played from it) an arrow that would cast a vote is [upDimmed] or
 * [downDimmed], and a "Listen first" hint shows; your current vote stays lit and can still be taken back.
 */
internal data class VotePillUi(
    val score: Int,
    val upLit: Boolean,
    val downLit: Boolean,
    val enabled: Boolean,
    val listenFirst: Boolean = false,
    val upDimmed: Boolean = false,
    val downDimmed: Boolean = false,
)

internal fun CommunityPost.votePill(): VotePillUi {
    val listenFirst = !mine && !heard
    return VotePillUi(
        score = up - down,
        upLit = myVote == 1,
        downLit = myVote == -1,
        enabled = !mine,
        listenFirst = listenFirst,
        upDimmed = listenFirst && myVote != 1,
        downDimmed = listenFirst && myVote != -1,
    )
}

/** [this] as heard when this phone's own marks ([heardHere], by post id) say so; the server's `heard` counts too. */
internal fun CommunityPost.heardFrom(heardHere: Set<String>): CommunityPost = if (!heard && id in heardHere) copy(heard = true) else this

/** A tap of ▲ (1) or ▼ (-1) waits for a listen: it would cast a vote (not take one back) on a post not heard yet. */
internal fun CommunityPost.needsListen(tapped: Int): Boolean = !heard && nextVote(tapped) != 0

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
