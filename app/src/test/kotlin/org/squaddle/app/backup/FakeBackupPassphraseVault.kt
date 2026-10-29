package org.squaddle.app.backup

/** In-memory [BackupPassphraseVault] test double - no Android Keystore. */
public class FakeBackupPassphraseVault : BackupPassphraseVault {
    private var stored: String? = null

    override fun save(passphrase: String) {
        stored = passphrase
    }

    override fun load(): String? = stored

    override fun clear() {
        stored = null
    }
}
