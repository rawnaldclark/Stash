package com.stash.feature.community

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.model.community.CommunityPost
import com.stash.core.model.share.SharedTrack
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Community's words as the phone reads them: the module's real strings and plurals (English), through the same
 * Resources functions the screens call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CommunityTextTest {
    private val res: Resources = ApplicationProvider.getApplicationContext<Context>().resources

    private val minute = 60_000L
    private val day = 86_400_000L
    private val now = 40 * day

    private fun post(
        kind: String = "playlist",
        count: Int = 2,
        age: Long = 3 * day,
        mine: Boolean = false,
        minutes: List<Long?> = emptyList(),
    ) = CommunityPost(
        id = "AAAAAAAA", kind = kind, title = "sad boy hours", name = "Maya", count = count,
        artist = "Death Plus".takeIf { kind == "song" }, createdAt = now - age, up = 3, down = 0, mine = mine,
        expiresAt = now - age + 30 * day, tracks = minutes.map { SharedTrack("T", "A", durationMs = it?.times(minute)) },
    )

    @Test fun `a playlist's page reads its kind, its songs and its length`() {
        assertThat(res.postHeadline(post(minutes = listOf(60, 5)))).isEqualTo("Playlist · 2 songs · 1 h 5 min")
        assertThat(res.postHeadline(post(minutes = listOf(30, 30)))).isEqualTo("Playlist · 2 songs · 1 h")
        assertThat(res.postHeadline(post(minutes = listOf(5, null)))).isEqualTo("Playlist · 2 songs · 5 min")
        assertThat(res.postHeadline(post(kind = "mix", count = 34, minutes = listOf(null)))).isEqualTo("Mix · 34 songs")
    }

    @Test fun `a length no song gives is left out`() {
        assertThat(res.postHeadline(post())).isEqualTo("Playlist · 2 songs")
        assertThat(res.postLength(0)).isNull()
    }

    @Test fun `a song's page reads its artist`() {
        assertThat(res.postHeadline(post(kind = "song"))).isEqualTo("Death Plus")
        val withTrack = post(kind = "song").copy(track = SharedTrack("Babydoll", "Dominic Fike"))
        assertThat(res.postHeadline(withTrack)).isEqualTo("Dominic Fike")
    }

    @Test fun `a row's facts read the kind and the songs, or a song's artist`() {
        assertThat(res.postFacts(post(kind = "song"))).isEqualTo("Song · Death Plus")
        assertThat(res.postFacts(post(count = 1))).isEqualTo("Playlist · 1 song")
        assertThat(res.postFacts(post(kind = "song").copy(artist = null))).isEqualTo("Song")
    }

    @Test fun `ages are short in lists and spelled out for TalkBack`() {
        assertThat(res.ageText(Age.Days(3))).isEqualTo("3d")
        assertThat(res.ageText(Age.Hours(4))).isEqualTo("4h")
        assertThat(res.ageText(Age.Minutes(5))).isEqualTo("5m")
        assertThat(res.ageText(Age.JustNow)).isEqualTo("just now")
        assertThat(res.ageText(Age.Days(3), long = true)).isEqualTo("3 days ago")
        assertThat(res.ageText(Age.Days(1), long = true)).isEqualTo("1 day ago")
        assertThat(res.ageText(Age.Minutes(1), long = true)).isEqualTo("1 minute ago")
        assertThat(res.ageText(Age.JustNow, long = true)).isEqualTo("just now")
    }

    @Test fun `the poster line names who posted it and when`() {
        assertThat(res.posterLine(post(), now)).isEqualTo("Maya · 3d")
        assertThat(res.posterLine(post(), now, long = true)).isEqualTo("Maya · 3 days ago")
    }

    @Test fun `your own post drops the name`() {
        assertThat(res.posterLine(post(mine = true, age = 0), now)).isEqualTo("just now")
        assertThat(res.posterLine(post(mine = true), now)).isEqualTo("3d")
    }

    @Test fun `Your posts add how long a post has left`() {
        assertThat(res.posterLine(post(mine = true), now, ownList = true)).isEqualTo("3d · Leaves in 27 days")
        assertThat(res.posterLine(post(mine = true, age = 29 * day), now, ownList = true)).isEqualTo("29d · Leaves in 1 day")
        assertThat(res.posterLine(post(mine = true, age = 30 * day - minute), now, ownList = true)).isEqualTo("29d · Leaves today")
        // A list that isn't yours never counts down.
        assertThat(res.posterLine(post(), now, ownList = false)).isEqualTo("Maya · 3d")
    }
}
