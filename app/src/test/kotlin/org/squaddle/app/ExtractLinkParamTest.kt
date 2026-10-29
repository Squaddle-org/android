package org.squaddle.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [extractLinkParam] is the one bit of pure logic in the Install Referrer path
 * (`MainActivity.kt`'s `SignedInApp`, M4b item 9) worth a direct test - everything else there is
 * either Android-framework plumbing or already covered by [org.squaddle.app.data.InviteLink]'s
 * own parse tests. */
class ExtractLinkParamTest {

    @Test
    fun extractsAndDecodesTheLinkParam() {
        val referrer = "utm_source=google-play&link=" + java.net.URLEncoder.encode("https://squaddle.org/j/relay/ROOM/INVITE#secret", "UTF-8")
        assertEquals("https://squaddle.org/j/relay/ROOM/INVITE#secret", extractLinkParam(referrer))
    }

    @Test
    fun handlesLinkAsTheOnlyParam() {
        val referrer = "link=" + java.net.URLEncoder.encode("https://squaddle.org/j/relay/ROOM/INVITE", "UTF-8")
        assertEquals("https://squaddle.org/j/relay/ROOM/INVITE", extractLinkParam(referrer))
    }

    @Test
    fun nullWhenNoLinkParamIsPresent() {
        assertNull(extractLinkParam("utm_source=google-play&utm_campaign=foo"))
    }

    @Test
    fun nullForAnEmptyString() {
        assertNull(extractLinkParam(""))
    }
}
