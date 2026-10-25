package org.squaddle.app.di

import android.content.Context
import org.squaddle.app.crypto.AndroidIdentityVault
import org.squaddle.app.crypto.IdentityVault
import org.squaddle.app.store.AppLocalStore
import org.squaddle.core.crypto.Crypto
import org.squaddle.core.crypto.KeyPair
import org.squaddle.core.crypto.LibsodiumCrypto
import org.squaddle.core.identity.DeviceCert
import org.squaddle.core.identity.UserIdentity
import org.squaddle.core.store.LocalStore
import org.squaddle.core.store.createSqlDriver

/**
 * Manual DI container - one instance per process (see [SquaddleApplication]). The real object
 * graph here is small and almost entirely singleton-scoped (one [Crypto], one identity, one
 * [AppSession]), so a hand-rolled container covers it completely with no reflection-based
 * framework. ViewModels are constructed per-screen via the AndroidX `viewModelFactory { }`
 * builder DSL (androidx.lifecycle.viewmodel), reading whatever they need from here directly -
 * no separate generated/hand-written `ViewModelFactory` class needed on top of that.
 */
public class AppContainer(private val context: Context) {
    public val identityVault: IdentityVault = AndroidIdentityVault(context)
    public val appLocalStore: AppLocalStore = AppLocalStore(context)

    public lateinit var crypto: Crypto
        private set

    public var session: AppSession? = null
        private set

    /** Must run once before anything else touches [crypto] - `LibsodiumCrypto.create()` itself
     * suspends (libsodium init). Call from a brief loading state before rendering any real
     * screen. */
    public suspend fun initialize() {
        crypto = LibsodiumCrypto.create()
    }

    /** `false` if no identity has been saved yet (fresh install - onboarding needed) or the vault
     * couldn't be decrypted (e.g. the Keystore key is gone). Never throws. */
    public fun restoreSessionIfPossible(): Boolean {
        val record = identityVault.load() ?: return false
        val identity = UserIdentity.fromEntropy(crypto, record.entropy)
        val device = KeyPair(record.devicePublicKey, record.deviceSecretKey)
        val cert = DeviceCert(
            user = record.deviceCertUser, userBox = record.deviceCertUserBox, device = record.devicePublicKey,
            label = record.deviceCertLabel, issuedAt = record.deviceCertIssuedAt, sig = record.deviceCertSig,
        )
        openSession(identity, device, cert)
        return true
    }

    /** Called once right after a brand-new (or restored-from-mnemonic) identity has been saved to
     * [identityVault]. */
    public fun openSession(identity: UserIdentity, device: KeyPair, deviceCert: DeviceCert): AppSession {
        val store = LocalStore(createSqlDriver(CORE_DB_NAME))
        return AppSession(crypto, identity, device, deviceCert, store).also { session = it }
    }

    public companion object {
        /** The only relay host baked into the app - what "create room" uses. Joining a room via
         * a received invite link talks to whatever relay host that link names instead
         * (ARCHITECTURE.md's federation-ready design never assumes a single relay per user). */
        public const val DEFAULT_RELAY_HOST: String = "relay.squaddle.org"
        private const val CORE_DB_NAME = "squaddle_core.db"
    }
}
