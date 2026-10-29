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

    @Serializable
    public data object Settings : Route

    @Serializable
    public data object DeveloperSettings : Route

    /** Reached from an App Link, a pasted/referred invite link, or (today) never from a
     * notification tap - `SyncAllRoomsWorker`'s notifications don't deep-link into a specific
     * room (`RoomSyncNotifier`'s own doc comment calls this out as a deliberate M4b scope cut),
     * so they just open the app at [RoomsList], already correctly "the room's parent" once
     * notification-driven room deep-linking is ever added. [linkUri] is
     * [org.squaddle.app.data.InviteLink.toUriString] - re-parsed on arrival the same way a
     * pasted clipboard link already is, not a new encoding. */
    @Serializable
    public data class Join(public val linkUri: String) : Route
}
