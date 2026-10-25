package org.squaddle.app.di

import org.squaddle.core.crypto.Crypto
import org.squaddle.core.crypto.KeyPair
import org.squaddle.core.identity.DeviceCert
import org.squaddle.core.identity.UserIdentity
import org.squaddle.core.store.LocalStore
import org.squaddle.core.sync.SyncClient

/**
 * Everything that needs an existing identity to construct - built once onboarding finishes (or
 * once at process start if [org.squaddle.app.crypto.IdentityVault] already has one). A single
 * [LocalStore]/[UserIdentity]/device [KeyPair]/[DeviceCert] serve every room regardless of relay,
 * but [SyncClient] is bound to one relay host per instance - a user can be a member of rooms
 * hosted on different relays (ARCHITECTURE.md's federation-ready design), so this keeps one
 * [SyncClient] per distinct relay host actually in use rather than assuming a single relay.
 */
public class AppSession(
    public val crypto: Crypto,
    public val userIdentity: UserIdentity,
    public val device: KeyPair,
    public val deviceCert: DeviceCert,
    public val store: LocalStore,
) {
    private val syncClients = mutableMapOf<String, SyncClient>()

    public fun syncClientFor(relayHost: String): SyncClient =
        syncClients.getOrPut(relayHost) { SyncClient(crypto, store, relayHost, userIdentity, device, deviceCert) }
}
