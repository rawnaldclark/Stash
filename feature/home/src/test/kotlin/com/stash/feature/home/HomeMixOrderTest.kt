package com.stash.feature.home

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.MusicSource
import org.junit.Test

/**
 * Ordering contract for Home's mix rails.
 *
 * Home used to render them in the DAO's `ORDER BY p.name ASC`, so the head of
 * every rail was a fixed alphabetical prefix — on a real library the first cards
 * were "'00s R&B", "'70s Lite Hits", "'70s Rock" and stayed that way forever.
 * A sync could add hundreds of songs and Home looked identical, because the
 * mixes that actually changed sorted into the middle of an uncapped rail.
 *
 * These pin the replacement: freshest first, missing recency last, name as a
 * stable tie-break.
 */
class HomeMixOrderTest {

    private fun mix(id: Long, title: String) =
        HomeMix(id = id, title = title, artUrl = null, source = MusicSource.SPOTIFY, trackCount = 10)

    @Test
    fun `the mix that most recently gained a track leads the rail`() {
        val rail = listOf(
            mix(1, "'00s R&B"),      // alphabetically first, stalest
            mix(2, "Soul Mix"),
            mix(3, "Your Top Songs 2025"),
        )
        val recency = mapOf(1L to 1_000L, 2L to 5_000L, 3L to 9_000L)

        assertThat(rail.freshestFirst(recency).map { it.title })
            .containsExactly("Your Top Songs 2025", "Soul Mix", "'00s R&B")
            .inOrder()
    }

    /**
     * The reported symptom, directly: alphabetical order must NOT survive when
     * the alphabetically-first mix is the one nothing was added to.
     */
    @Test
    fun `alphabetically first mix does not lead when it is stale`() {
        val rail = listOf(mix(1, "'00s R&B"), mix(2, "Zydeco Mix"))
        val recency = mapOf(1L to 1L, 2L to 2L)

        assertThat(rail.freshestFirst(recency).first().title).isEqualTo("Zydeco Mix")
    }

    /** A mix with no live memberships has no recency row — it must not jump the queue. */
    @Test
    fun `mix with no recency sorts last`() {
        val rail = listOf(mix(1, "Empty Mix"), mix(2, "Soul Mix"))
        val recency = mapOf(2L to 5_000L)

        assertThat(rail.freshestFirst(recency).map { it.title })
            .containsExactly("Soul Mix", "Empty Mix")
            .inOrder()
    }

    /**
     * A sync writes many memberships in the same millisecond, so ties are the
     * common case, not an edge one. Without a tie-break the rail would reshuffle
     * on every emission.
     */
    @Test
    fun `equal timestamps fall back to name so the rail is stable`() {
        val rail = listOf(mix(1, "Soul Mix"), mix(2, "Ambient Mix"), mix(3, "Focus Mix"))
        val recency = mapOf(1L to 7_000L, 2L to 7_000L, 3L to 7_000L)

        assertThat(rail.freshestFirst(recency).map { it.title })
            .containsExactly("Ambient Mix", "Focus Mix", "Soul Mix")
            .inOrder()
    }

    // -- Pinned mixes (2026-09-07: "Shown on Home" must mean shown on Home) ----------

    /**
     * Cup Noodle Radio, Pixel 6, 2026-09-07: the user switched it to "Shown on
     * Home" and never saw it — its tracks were last added on 2 August, so it sat
     * 36th of 63 radios behind a 12-card cut. A mix the user pins leads the rail
     * no matter how stale its tracks are.
     */
    @Test
    fun `a pinned mix leads the rail regardless of freshness`() {
        val rail = listOf(
            mix(1, "Cup Noodle Radio").copy(pinnedToHomeAt = 100L), // stalest tracks, pinned
            mix(2, "T. Rex Radio"),
            mix(3, "The Doors Radio"),
        )
        val recency = mapOf(1L to 1_000L, 2L to 9_000L, 3L to 5_000L)
        assertThat(rail.pinnedThenFreshest(recency).map { it.title })
            .containsExactly("Cup Noodle Radio", "T. Rex Radio", "The Doors Radio")
            .inOrder()
    }

    @Test
    fun `pinned mixes keep their pin order, first pinned first, ahead of the fresh ones`() {
        val rail = listOf(
            mix(1, "Zach Top Radio").copy(pinnedToHomeAt = 200L),
            mix(2, "Beck Radio").copy(pinnedToHomeAt = 100L),
            mix(3, "The Cure Radio"),
        )
        val recency = mapOf(1L to 1L, 2L to 1L, 3L to 9_000L)
        assertThat(rail.pinnedThenFreshest(recency).map { it.title })
            .containsExactly("Beck Radio", "Zach Top Radio", "The Cure Radio")
            .inOrder()
    }

    @Test
    fun `the rail cut keeps every pinned mix even past the limit`() {
        val pinned = (1L..13L).map { mix(it, "Pinned $it").copy(pinnedToHomeAt = it) }
        val fresh = (14L..30L).map { mix(it, "Fresh $it") }
        val cut = (pinned + fresh).railCut(limit = 12)
        assertThat(cut).hasSize(13)
        assertThat(cut.all { it.pinnedToHomeAt != null }).isTrue()
    }

    @Test
    fun `the rail cut fills the limit with fresh mixes after the pinned ones`() {
        val pinned = (1L..2L).map { mix(it, "Pinned $it").copy(pinnedToHomeAt = it) }
        val fresh = (3L..30L).map { mix(it, "Fresh $it") }
        val cut = (pinned + fresh).railCut(limit = 12)
        assertThat(cut).hasSize(12)
        assertThat(cut.take(2).map { it.title }).containsExactly("Pinned 1", "Pinned 2").inOrder()
    }
}
