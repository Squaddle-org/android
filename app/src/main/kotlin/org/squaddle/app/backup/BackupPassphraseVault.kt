package org.squaddle.app.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore-at-rest storage for the auto-backup passphrase only - needed because periodic
 * auto-backup (M4b item 7) must write without a prompt each time, so the passphrase has to be
 * readable by [AutoBackupWorker] unattended. Same AES-256-GCM-via-Android-Keystore trust model
 * [org.squaddle.app.crypto.IdentityVault] already uses for this app's single most sensitive
 * secret (the seed itself) - this is that same already-accepted trust boundary reused for a
 * second secret, not a new, weaker one. A plain `SharedPreferences` string (like
 * [org.squaddle.app.settings.DeveloperSettingsStore]'s non-secret settings) would defeat the
 * entire point of a user-chosen backup passphrase.
 */
public interface BackupPassphraseVault {
    public fun save(passphrase: String)
    public fun load(): String?
    public fun clear()
}

public class AndroidBackupPassphraseVault(private val context: Context) : BackupPassphraseVault {
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun vaultFile(): File = File(context.filesDir, VAULT_FILE_NAME)

    override fun save(passphrase: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(passphrase.encodeToByteArray())
        vaultFile().writeBytes(byteArrayOf(iv.size.toByte()) + iv + ciphertext)
    }

    override fun load(): String? {
        val file = vaultFile()
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            val ivLength = bytes[0].toInt() and 0xFF
            val iv = bytes.copyOfRange(1, 1 + ivLength)
            val ciphertext = bytes.copyOfRange(1 + ivLength, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            cipher.doFinal(ciphertext).decodeToString()
        } catch (e: Exception) {
            null
        }
    }

    override fun clear() {
        vaultFile().delete()
    }

    private companion object {
        const val KEY_ALIAS = "squaddle_backup_passphrase_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
        const val VAULT_FILE_NAME = "backup_passphrase_vault.bin"
    }
}
