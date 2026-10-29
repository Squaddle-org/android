package org.squaddle.app.data

import org.squaddle.core.sync.PushOutcome

/** Thrown by [RealSyncGateway] call sites that return `Result<T>` (not [PushOutcome] directly,
 * like [RealSyncGateway.createInvite]) when the relay returns a structured error - carries
 * [code] through so a ViewModel can key friendlier copy off it ([friendlyErrorMessage]) instead
 * of pattern-matching [Throwable.message] text. */
public class RelayErrorException(public val code: String, message: String) : Exception(message)

/** M4b item 8: "room full"/"quota exceeded" get clearer copy than the relay's own raw message
 * wherever a room-limits error (PROTOCOL.md §8.2) can surface - join, approve, and invite
 * creation. Every other code falls back to the relay's own message, which is already
 * reasonably user-facing (e.g. "invalid signature", "stale head"). */
public fun friendlyErrorMessage(code: String, fallbackMessage: String): String = when (code) {
    "room_full" -> "This room is full. Ask an admin to boost it for more space."
    "quota_exceeded" -> "This room has reached its invite limit. Ask an admin to boost it."
    else -> fallbackMessage
}

public fun PushOutcome.Error.friendlyMessage(): String = friendlyErrorMessage(code, message)
