package org.squaddle.app.ui.nav

import kotlinx.serialization.Serializable

/**
 * Navigation Compose's type-safe routes (`androidx.navigation:navigation-compose` 2.8+) - the
 * single source of truth for which screen is showing in the signed-in app. Room-scoped routes
 * carry the room id as its already-everywhere-used Base32 string form
 * ([org.squaddle.core.encoding.Base32]), not a raw `ByteArray` - Navigation's safe-args
 * serialization wants a primitive/String-shaped argument, and this app already encodes room ids
 * as Base32 strings for every other purpose (testTags, `AppLocalStore` keys, `InviteLink`), so
 * reusing that convention here avoids inventing a second encoding just for nav args.
 *
 * Onboarding is deliberately NOT one of these routes - see `MainActivity.kt`'s own doc comment
 * on why its multi-step wizard stays a local state machine (`OnboardingViewModel`) with its own
 * `BackHandler`, not a nested nav graph: it has to disappear completely (not just get popped)
 * once sign-in finishes, which a fresh `SignedInApp`/`NavHost` recomposition does automatically
 * and "being its own graph with a `popUpTo` pop" would only approximate.
 */
public sealed interface Route {
    @Serializable
    public data object RoomsList : Route

    @Serializable
    public data class Room(public val roomIdB32: String) : Route

    @Serializable
    public data class Members(public val roomIdB32: String) : Route

    @Serializable
    public data class Invite(public val roomIdB32: String) : Route

    @Serializable
    public data class VoucherRedeem(public val roomIdB32: String) : Route

    /** Always navigated to with a [Room] of the same [roomIdB32] already pushed directly
     * beneath it (both in-app, tapping an idea/event from [Room]'s own list, and from
     * [parseInternalDeepLink] below) - so back from here lands on the room, never skips past it,
     * the same "proper synthetic back stack" posture as [Join] below. [ideaIdB32] is the idea's
     * id, Base32-encoded the same way [roomIdB32] is. */
    @Serializable
    public data class Idea(public val roomIdB32: String, public val ideaIdB32: String) : Route

    /** Same posture as [Idea] - see that one's doc comment. */
    @Serializable
    public data class Event(public val roomIdB32: String, public val eventIdB32: String) : Route

    @Serializable
    public data object Settings : Route

    @Serializable
    public data object DeveloperSettings : Route

    /** Reached from an App Link or a pasted/referred invite link. [linkUri] is
     * [org.squaddle.app.data.InviteLink.toUriString] - re-parsed on arrival the same way a
     * pasted clipboard link already is, not a new encoding. */
    @Serializable
    public data class Join(public val linkUri: String) : Route
}

/** `squaddle://room/{roomIdB32}` (the room itself) / `.../idea/{ideaIdB32}` / `.../event/{eventIdB32}`
 * / `.../members` - this app's own internal-only deep-link convention for [Route.Room]/
 * [Route.Idea]/[Route.Event]/[Route.Members] (not an `AndroidManifest.xml` intent filter / App
 * Link: nothing outside this app is meant to construct or resolve one of these, so there is
 * deliberately no `<data android:scheme="squaddle">` for the OS to match against). It exists so a
 * `PendingIntent` built entirely inside this app - a notification tap
 * ([org.squaddle.app.push.RoomSyncNotifier]) - can carry a `Uri` an explicit
 * `Intent(context, MainActivity::class.java).setData(...)` delivers straight to
 * `MainActivity.onCreate`/`onNewIntent`'s existing `pendingDeepLink` handling, same as today's
 * `https://squaddle.org/j/...` App Link, with zero manifest wiring needed since an explicit
 * intent never goes through intent-filter resolution. A notification with nothing room-specific
 * to point at (e.g. "you were removed from this room") skips this entirely and just opens the app
 * plainly instead - landing on [Route.RoomsList], its own start destination, needs no URI at all. */
public fun Route.Room.toDeepLinkUri(): String = "squaddle://room/$roomIdB32"

/** See [Route.Room.toDeepLinkUri]. */
public fun Route.Idea.toDeepLinkUri(): String = "squaddle://room/$roomIdB32/idea/$ideaIdB32"

/** See [Route.Room.toDeepLinkUri]. */
public fun Route.Event.toDeepLinkUri(): String = "squaddle://room/$roomIdB32/event/$eventIdB32"

/** See [Route.Room.toDeepLinkUri]. */
public fun Route.Members.toDeepLinkUri(): String = "squaddle://room/$roomIdB32/members"

/** Parses a `squaddle://room/...` URI built by one of the `toDeepLinkUri()` functions above back
 * into the route it names - `null` for anything else (including a malformed `squaddle://` URI),
 * the same "not a match, not an error" contract `RealSyncGateway.parseInviteLink` already uses
 * for `https://squaddle.org/j/...` links, so a caller can simply try this first and fall through
 * to the invite-link parse otherwise. */
public fun parseInternalDeepLink(uri: String): Route? {
    val parsed = runCatching { java.net.URI(uri) }.getOrNull() ?: return null
    if (parsed.scheme != "squaddle" || parsed.host != "room") return null
    val segments = parsed.path.trim('/').split("/").filter { it.isNotEmpty() }
    return when (segments.size) {
        1 -> Route.Room(segments[0])
        2 -> if (segments[1] == "members") Route.Members(segments[0]) else null
        3 -> when (segments[1]) {
            "idea" -> Route.Idea(segments[0], segments[2])
            "event" -> Route.Event(segments[0], segments[2])
            else -> null
        }
        else -> null
    }?.takeIf { route ->
        when (route) {
            is Route.Room -> route.roomIdB32.isNotBlank()
            is Route.Members -> route.roomIdB32.isNotBlank()
            is Route.Idea -> route.roomIdB32.isNotBlank() && route.ideaIdB32.isNotBlank()
            is Route.Event -> route.roomIdB32.isNotBlank() && route.eventIdB32.isNotBlank()
            else -> false
        }
    }
}
