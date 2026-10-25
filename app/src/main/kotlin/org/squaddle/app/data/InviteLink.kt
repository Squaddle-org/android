@file:OptIn(ExperimentalEncodingApi::class)

package org.squaddle.app.data

import org.squaddle.core.encoding.Base32
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** PROTOCOL.md §3.5: `https://<linkHost>/j/<relayHost>/<b32 roomId>/<b32 inviteId>#<b64 inviteSecret>`.
 * [inviteSecret] is `null` for an approval-required invite - §3.5 is explicit that no envelope
 * exists for those at all ("For approval invites, no envelope exists; the key arrives in
 * approve"), so there's no secret to carry in the link's fragment; joining one only ever needs
 * [inviteId] itself. Deliberately parses a plain [String], not an `android.net.Uri` - keeps this
 * testable in a plain JUnit test with no Android framework/Robolectric needed. */
public data class InviteLink(
    public val relayHost: String,
    public val roomId: ByteArray,
    public val inviteId: ByteArray,
    public val inviteSecret: ByteArray?,
) {
    public fun toUriString(linkHost: String = "squaddle.org"): String {
        val base = "https://$linkHost/j/$relayHost/${Base32.encode(roomId)}/${Base32.encode(inviteId)}"
        return if (inviteSecret != null) "$base#${b64UrlEncode(inviteSecret)}" else base
    }

    public companion object {
        private val SHAPE = Regex("^https://[^/]+/j/([^/]+)/([^/]+)/([^/]+)$")

        /** `null` on anything that doesn't parse or decode cleanly - never throws. [text]'s
         * fragment, when present, is an invite secret and is never logged by any caller of this
         * (CLAUDE.md). */
        public fun parse(text: String): InviteLink? {
            val hashIndex = text.indexOf('#')
            val withoutFragment = if (hashIndex >= 0) text.substring(0, hashIndex) else text
            val fragment = if (hashIndex >= 0) text.substring(hashIndex + 1) else null
            val match = SHAPE.matchEntire(withoutFragment) ?: return null
            val (relayHost, roomIdB32, inviteIdB32) = match.destructured
            val roomId = Base32.decode(roomIdB32) ?: return null
            val inviteId = Base32.decode(inviteIdB32) ?: return null
            val inviteSecret = if (fragment.isNullOrEmpty()) null else (b64UrlDecode(fragment) ?: return null)
            return InviteLink(relayHost, roomId, inviteId, inviteSecret)
        }

        private fun b64UrlEncode(bytes: ByteArray): String = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)
        private fun b64UrlDecode(text: String): ByteArray? = try {
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(text)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
