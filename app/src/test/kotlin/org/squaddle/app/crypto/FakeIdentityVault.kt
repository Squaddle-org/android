package org.squaddle.app.crypto

/** In-memory [IdentityVault] test double - no Android Keystore, no disk. */
public class FakeIdentityVault : IdentityVault {
    private var stored: IdentityRecord? = null

    override fun hasIdentity(): Boolean = stored != null
    override fun save(record: IdentityRecord) {
        stored = record
    }
    override fun load(): IdentityRecord? = stored
    override fun clear() {
        stored = null
    }
}
