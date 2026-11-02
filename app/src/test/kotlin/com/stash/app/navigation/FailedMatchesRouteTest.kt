package com.stash.app.navigation

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FailedMatchesRoute]'s song: Now Playing's "Find a better match" opens Failed Matches on the
 * song it just flagged; the Sync tab opens it on nothing.
 */
class FailedMatchesRouteTest {

    @Test fun `the Sync tab's route carries no song`() {
        assertNull(FailedMatchesRoute().focusTrackId)
    }

    @Test fun `Now Playing's route carries the flagged song`() {
        assertEquals(42L, FailedMatchesRoute(focusTrackId = 42L).focusTrackId)
    }

    @Test fun `the song is the route's one argument, optional and nullable`() {
        // Type-safe navigation builds the route's pattern from this descriptor: an optional
        // element is a query argument ("?focusTrackId={focusTrackId}"), so FailedMatchesRoute()
        // with no song still matches the destination, and a nullable Long may be absent.
        val descriptor = FailedMatchesRoute.serializer().descriptor
        assertEquals(1, descriptor.elementsCount)
        val index = descriptor.getElementIndex("focusTrackId")
        assertTrue(descriptor.isElementOptional(index))
        assertTrue(descriptor.getElementDescriptor(index).isNullable)
    }

    @Test fun `the song survives the route's serializer both ways`() {
        for (route in listOf(FailedMatchesRoute(), FailedMatchesRoute(focusTrackId = 7L))) {
            val json = Json.encodeToString(FailedMatchesRoute.serializer(), route)
            assertEquals(route, Json.decodeFromString(FailedMatchesRoute.serializer(), json))
        }
    }
}
