package com.stash.data.ytmusic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #664: the accounts_list response, in both of the shapes YouTube has used. */
class YouTubeProfilesTest {

    private fun parse(json: String) = YouTubeProfiles.parse(Json.parseToJsonElement(json) as JsonObject)

    // The music.youtube.com multi-page menu: accountItem, runs text, a pageIdToken on the brand.
    private val menu = """
    {"actions":[{"getMultiPageMenuAction":{"menu":{"multiPageMenuRenderer":{"sections":[{"accountSectionListRenderer":{"contents":[{"accountItemSectionRenderer":{"contents":[
      {"accountItem":{"accountName":{"runs":[{"text":"Sam Lee"}]},"channelHandle":{"runs":[{"text":"@samlee"}]},
        "accountPhoto":{"thumbnails":[{"url":"https://yt3/s48"},{"url":"https://yt3/s88"}]},"isSelected":true,
        "serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[
          {"accountStateToken":{"hasChannel":true}},
          {"accountSigninToken":{"signinUrl":"https://accounts.google.com/ServiceLogin?authuser=0&continue=x"}}]}}}},
      {"accountItem":{"accountName":{"runs":[{"text":"Sam's Music"}]},"accountByline":{"simpleText":"412 subscribers"},
        "accountPhoto":{"thumbnails":[{"url":"https://yt3/b88"}]},"isSelected":false,
        "serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[
          {"pageIdToken":{"pageId":"112233445566778899001"}},
          {"accountSigninToken":{"signinUrl":"https://accounts.google.com/ServiceLogin?passive=1&authuser=0&pageid=112233445566778899001"}}]}}}}
    ]}}]}}]}}}}]}
    """

    @Test fun `the account's own profile and its brand account`() {
        val profiles = parse(menu)
        assertEquals(2, profiles.size)
        val own = profiles[0]
        assertEquals("Sam Lee", own.name)
        assertEquals("@samlee", own.handle)
        assertEquals("https://yt3/s88", own.photoUrl)
        assertNull(own.pageId)
        assertTrue(own.selected)
        val brand = profiles[1]
        assertEquals("Sam's Music", brand.name)
        assertEquals("412 subscribers", brand.handle)
        assertEquals("112233445566778899001", brand.pageId)
        assertEquals(0, brand.authUser)
    }

    // The www.youtube.com channel switcher: accountItemRenderer, simpleText, a second Google account.
    @Test fun `the channel switcher shape and a second signed-in account's index`() {
        val profiles = parse(
            """
            {"data":{"actions":[{"updateChannelSwitcherPageAction":{"page":{"channelSwitcherPageRenderer":{"contents":[
              {"accountItemRenderer":{"accountName":{"simpleText":"Work Brand"},
                "serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[
                  {"pageIdToken":{"pageId":"998877665544332211000"}},
                  {"accountSigninToken":{"signinUrl":"/signin?action_handle_signin=true&authuser=2&pageid=998877665544332211000"}}]}}}}
            ]}}}}]}}
            """,
        )
        assertEquals(1, profiles.size)
        assertEquals("Work Brand", profiles[0].name)
        assertEquals("998877665544332211000", profiles[0].pageId)
        assertEquals(2, profiles[0].authUser)
    }

    @Test fun `items without a name are skipped and a repeated profile counts once`() {
        val item = """{"accountItem":{"accountName":{"runs":[{"text":"A"}]},"serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[{"pageIdToken":{"pageId":"123456789012345678901"}}]}}}}"""
        val profiles = parse("""{"a":[$item,$item,{"accountItem":{"isSelected":true}}]}""")
        assertEquals(1, profiles.size)
    }

    @Test fun `an unexpected response is no profiles, not a crash`() {
        assertEquals(emptyList<YouTubeProfile>(), parse("""{"responseContext":{}}"""))
    }
}
