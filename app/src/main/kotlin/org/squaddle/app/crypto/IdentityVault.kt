@file:OptIn(ExperimentalEncodingApi::class)

package org.squaddle.app.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** The only secret material this app ever persists: seed entropy (recovers the user's
 * signing/box keys deterministically) plus this specific device's own Ed25519 keypair and the
 * [org.squaddle.core.identity.DeviceCert] signed for it - the device key is NOT derived from the
 * seed (it's random per device, PROTOCOL.md §2.1), so losing it without a backup means minting a
 * new device identity. Display name / room list are NOT secret and are never passed through this
 * vault - see [org.squaddle.app.store.AppLocalStore].
 *
 * The cert is stored as its individual public fields, not a CBOR blob - [DeviceCert.toFullCbor]
 * is `internal` to `core` (not visible across the module boundary); [DeviceCert]'s own public
 * data class constructor reconstructs an equivalent, already-signed cert directly from these. */
public data class IdentityRecord(
    public val entropy: ByteArray,
    public val deviceSecretKey: ByteArray,
    public val devicePublicKey: ByteArray,
    public val deviceCertUser: ByteArray,
    public val deviceCertUserBox: ByteArray,
    public val deviceCertLabel: String,
    public val deviceCertIssuedAt: Long,
    public val deviceCertSig: ByteArray,
)

@Serializable
private data class IdentityRecordDto(
    val entropy: String,
    val deviceSecretKey: String,
    val devicePublicKey: String,
    val deviceCertUser: String,
    val deviceCertUserBox: String,
    val deviceCertLabel: String,
    val deviceCertIssuedAt: Long,
    val deviceCertSig: String,
)

/** Abstraction over [AndroidIdentityVault] so ViewModels (starting with onboarding) can depend on
 * this instead of the concrete Keystore-backed class - a [FakeIdentityVault] test double backs
 * ViewModel unit tests, the same "depend on an interface, not the Android-specific concrete
 * class" shape [org.squaddle.app.data.SyncGateway] already uses. */
public interface IdentityVault {
    public fun hasIdentity(): Boolean
    public fun save(record: IdentityRecord)
    public fun load(): IdentityRecord?
    public fun clear()
}

/**
 * Encrypts [IdentityRecord] at rest with an Android Keystore-backed AES-256-GCM key - the key
 * material never leaves the secure keystore, and the plaintext never touches disk. Raw
 * Keystore+Cipher, not `androidx.security:security-crypto` (see the plan's own justification:
 * that library has never left pre-1.0 and is just a thin wrapper over this same pattern).
 * Never logs anything it touches (CLAUDE.md: "Never log keys, seeds, invite secrets").
 */
public class AndroidIdentityVault(private val context: Context) : IdentityVault {

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

    override fun hasIdentity(): Boolean = vaultFile().exists()

    override fun save(record: IdentityRecord) {
        val dto = IdentityRecordDto(
            entropy = b64(record.entropy),
            deviceSecretKey = b64(record.deviceSecretKey),
            devicePublicKey = b64(record.devicePublicKey),
            deviceCertUser = b64(record.deviceCertUser),
            deviceCertUserBox = b64(record.deviceCertUserBox),
            deviceCertLabel = record.deviceCertLabel,
            deviceCertIssuedAt = record.deviceCertIssuedAt,
            deviceCertSig = b64(record.deviceCertSig),
        )
        val plaintext = Json.encodeToString(dto).encodeToByteArray()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        // [iv length byte][iv][ciphertext] - GCM IVs are always well under 256 bytes (12 in practice).
        vaultFile().writeBytes(byteArrayOf(iv.size.toByte()) + iv + ciphertext)
    }

    /** `null` if no identity has been saved yet, or the vault can't be decrypted (e.g. the
     * Keystore key is gone - a "clear app storage"/restore-to-different-device case). Never
     * throws, same posture as every other AEAD-gated open in `core`. */
    override fun load(): IdentityRecord? {
        val file = vaultFile()
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            val ivLength = bytes[0].toInt() and 0xFF
            val iv = bytes.copyOfRange(1, 1 + ivLength)
            val ciphertext = bytes.copyOfRange(1 + ivLength, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            val plaintext = cipher.doFinal(ciphertext)
            val dto = Json.decodeFromString<IdentityRecordDto>(plaintext.decodeToString())
            IdentityRecord(
                unb64(dto.entropy), unb64(dto.deviceSecretKey), unb64(dto.devicePublicKey),
                unb64(dto.deviceCertUser), unb64(dto.deviceCertUserBox), dto.deviceCertLabel, dto.deviceCertIssuedAt, unb64(dto.deviceCertSig),
            )
        } catch (e: Exception) {
            null
        }
    }

    override fun clear() {
        vaultFile().delete()
    }

    private fun b64(bytes: ByteArray): String = Base64.Default.encode(bytes)
    private fun unb64(text: String): ByteArray = Base64.Default.decode(text)

    private companion object {
        const val KEY_ALIAS = "squaddle_identity_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
        const val VAULT_FILE_NAME = "identity_vault.bin"
    }
}
