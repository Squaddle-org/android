package org.squaddle.app.data

import org.squaddle.core.crypto.Crypto
import org.squaddle.core.crypto.KeyPair

/** Minimal [Crypto] test double - deterministic, not actually secure, just enough for any test
 * that needs *a* [Crypto] to exercise core's own pure logic (BIP39 bit-slicing, CBOR encoding,
 * MembershipState replay, ...) without pulling in real libsodium. */
public object FakeCrypto : Crypto {
    override fun randomBytes(n: Int): ByteArray = ByteArray(n)
    override fun blake2b256(vararg parts: ByteArray): ByteArray = ByteArray(32)
    override fun sha256(data: ByteArray): ByteArray = ByteArray(32)
    override fun signSeedKeypair(seed32: ByteArray): KeyPair = KeyPair(ByteArray(32), ByteArray(64))
    override fun sign(secretKey: ByteArray, message: ByteArray): ByteArray = ByteArray(64)
    override fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = true
    override fun boxSeedKeypair(seed32: ByteArray): KeyPair = KeyPair(ByteArray(32), ByteArray(32))
    override fun sealBox(recipientPk: ByteArray, plaintext: ByteArray): ByteArray = plaintext
    override fun openSealedBox(recipient: KeyPair, ciphertext: ByteArray): ByteArray? = ciphertext
    override fun aeadEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, ad: ByteArray): ByteArray = plaintext
    override fun aeadDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, ad: ByteArray): ByteArray? = ciphertext
    override fun kdf(master32: ByteArray, id: Long, context: String): ByteArray = ByteArray(32)
    override fun argon2id(password: ByteArray, salt16: ByteArray, opsLimit: Long, memLimit: Int): ByteArray = ByteArray(32)
}
