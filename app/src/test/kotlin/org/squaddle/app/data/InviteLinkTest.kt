package org.squaddle.app.data

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Plain JUnit, no Robolectric needed - [InviteLink] parses a [String], not an `android.net.Uri`. */
class InviteLinkTest {

    @Test
    fun openInviteRoundTripsThroughItsOwnUriString() {
        val link = InviteLink("relay.squaddle.org", ByteArray(32) { it.toByte() }, ByteArray(16) { (it + 1).toByte() }, ByteArray(32) { (it + 2).toByte() })
        val parsed = InviteLink.parse(link.toUriString())
        assertEquals(link.relayHost, parsed?.relayHost)
        assertContentEquals(link.roomId, parsed?.roomId)
        assertContentEquals(link.inviteId, parsed?.inviteId)
        assertContentEquals(link.inviteSecret, parsed?.inviteSecret)
    }

    @Test
    fun approvalInviteHasNoFragmentAndParsesWithNullSecret() {
        val link = InviteLink("relay.squaddle.org", ByteArray(32) { it.toByte() }, ByteArray(16) { (it + 1).toByte() }, inviteSecret = null)
        val uri = link.toUriString()
        assertEquals(-1, uri.indexOf('#'))
        val parsed = InviteLink.parse(uri)
        assertNull(parsed?.inviteSecret)
        assertContentEquals(link.roomId, parsed?.roomId)
    }

    @Test
    fun rejectsGarbage() {
        assertNull(InviteLink.parse("not a link at all"))
        assertNull(InviteLink.parse("https://squaddle.org/j/relay.example/not-base32!/also-not#secret"))
        assertNull(InviteLink.parse("https://squaddle.org/other/path/here"))
    }
}
