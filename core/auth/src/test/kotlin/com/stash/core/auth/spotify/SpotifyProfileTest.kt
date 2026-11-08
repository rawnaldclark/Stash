package com.stash.core.auth.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The account-swap guard rests on two things: reading whose account a sign-in is from Spotify's
 * `profileAttributes` answer, and telling two accounts apart by user id only.
 */
class SpotifyProfileTest {

    // The answer's shape, as web-player.c263f3db.js reads it (`t?.data?.me?.profile`).
    private fun answer(profile: String) = """{"data":{"me":{"profile":$profile}},"extensions":{}}"""

    @Test fun `reads the user id and display name`() {
        val profile = SpotifyProfile.parseProfileAttributes(
            answer("""{"avatar":null,"name":"Rawn","uri":"spotify:user:rawnclark","username":"rawnclark"}"""),
        )

        assertEquals(SpotifyProfile(userId = "rawnclark", displayName = "Rawn"), profile)
    }

    @Test fun `falls back to the id in the uri when username is missing`() {
        val profile = SpotifyProfile.parseProfileAttributes(
            answer("""{"name":"Sam","uri":"spotify:user:lm7d11nsf65n11sojl4twwr6t","username":null}"""),
        )

        assertEquals("lm7d11nsf65n11sojl4twwr6t", profile?.userId)
        assertEquals("Sam", profile?.displayName)
    }

    @Test fun `an account without a display name is named by its id`() {
        val profile = SpotifyProfile.parseProfileAttributes(answer("""{"name":"","username":"rawnclark"}"""))

        assertEquals(SpotifyProfile(userId = "rawnclark", displayName = "rawnclark"), profile)
    }

    @Test fun `answers that name no account are null`() {
        val noAccount = listOf(
            // A persisted-query miss, as Spotify answers one.
            """{"errors":[{"message":"PersistedQueryNotFound"}]}""",
            // An anonymous session.
            """{"data":{"me":null}}""",
            answer("null"),
            answer("""{"name":"Rawn"}"""),
            answer("""{"name":"Rawn","uri":"spotify:user:"}"""),
            // Not a user uri: an id must not be cut out of something else.
            answer("""{"name":"Rawn","uri":"spotify:user:rawnclark:collection"}"""),
            answer("""{"name":"Rawn","username":"   "}"""),
            "not json",
            "",
        )

        noAccount.forEach { assertNull(it, SpotifyProfile.parseProfileAttributes(it)) }
    }

    @Test fun `the same user id is the same account, whatever the names`() {
        val a = SpotifyProfile(userId = "rawnclark", displayName = "Rawn")

        assertTrue(a.isSameAccount(SpotifyProfile(userId = "rawnclark", displayName = "Rawn Clark")))
        // One answer's uri form and another's username form, and Spotify's mixed-case old ids.
        assertTrue(a.isSameAccount(SpotifyProfile(userId = "spotify:user:RawnClark", displayName = "Rawn")))
    }

    @Test fun `two accounts with one display name are still two accounts`() {
        val a = SpotifyProfile(userId = "rawnclark", displayName = "Rawn")

        assertFalse(a.isSameAccount(SpotifyProfile(userId = "lm7d11nsf65n11sojl4twwr6t", displayName = "Rawn")))
    }

    // -- The one normaliser the database keys accounts by --------------------

    @Test fun `every shape of one user id normalises to one key`() {
        val shapes = listOf("rawnclark", "RawnClark", "  rawnclark ", "spotify:user:rawnclark", "SPOTIFY:USER:RawnClark")

        assertEquals(setOf("rawnclark"), shapes.map(SpotifyProfile::accountKeyOf).toSet())
        assertEquals("rawnclark", SpotifyProfile(userId = "Spotify:User:RAWNCLARK", displayName = "Rawn").accountKey)
        assertTrue(SpotifyProfile("RawnClark", "Rawn").isSameAccount(SpotifyProfile("spotify:user:rawnclark", "x")))
    }

    @Test fun `only an id already in key form, and not blank, is a key`() {
        assertTrue(SpotifyProfile.isAccountKey("rawnclark"))
        assertTrue(SpotifyProfile.isAccountKey("lm7d11nsf65n11sojl4twwr6t"))
        listOf("RawnClark", " rawnclark", "spotify:user:rawnclark", "", "  ").forEach {
            assertFalse("'$it' is not a key", SpotifyProfile.isAccountKey(it))
        }
    }
}
