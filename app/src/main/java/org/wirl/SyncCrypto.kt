package org.wirl

import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Keys and encryption for the encrypted mailbox (docs/sync-protocol.md §3–§4).
 *
 * From the 32-byte group secret, HKDF-SHA256 (no salt, 32-byte output) derives the mailbox id ("wirl-mailbox-v2",
 * lowercase hex), the mailbox token ("wirl-token-v2", URL-safe base64 without padding) and the AES-256 key
 * ("wirl-sync-v2"). Values are sealed with AES-256-GCM: a fresh 12-byte nonce, a 16-byte tag, and the mailbox id
 * (ASCII) as additional authenticated data. Nonce and ciphertext are standard base64 with padding.
 */
object SyncCrypto {
    const val INFO_MAILBOX = "wirl-mailbox-v2"
    const val INFO_TOKEN = "wirl-token-v2"
    const val INFO_KEY = "wirl-sync-v2"
    const val SECRET_BYTES = 32
    const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    /** The values derived from a group secret. */
    class GroupKeys(val mailbox: String, val token: String, val key: ByteArray)

    class Sealed(val nonce: String, val ciphertext: String)

    /** The secret isn't 32 bytes of standard base64. */
    class BadSecretException : Exception("the sync secret is not valid")

    /** The value doesn't decrypt: wrong secret, or tampering. Means "pair again". */
    class AuthenticationException(cause: Throwable? = null) : Exception("couldn't decrypt the synced dictionary", cause)

    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text.trim())

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** 32 random bytes, base64. Only computers make secrets; this is for tests. */
    fun newSecret(): String = b64(ByteArray(SECRET_BYTES).also { random.nextBytes(it) })

    /** The 32 secret bytes, or null if [secretB64] isn't valid. */
    fun secretBytes(secretB64: String): ByteArray? =
        runCatching { unb64(secretB64) }.getOrNull()?.takeIf { it.size == SECRET_BYTES }

    /** Derives the mailbox id, token and key. Throws [BadSecretException] for anything but 32 base64 bytes. */
    fun derive(secretB64: String): GroupKeys {
        val ikm = secretBytes(secretB64) ?: throw BadSecretException()
        return GroupKeys(
            mailbox = hex(hkdf32(ikm, INFO_MAILBOX)),
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(hkdf32(ikm, INFO_TOKEN)),
            key = hkdf32(ikm, INFO_KEY),
        )
    }

    /** SHA-256 of the token, lowercase hex. The relay stores this, never the token. */
    fun tokenHash(token: String): String =
        hex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.US_ASCII)))

    /** HKDF-SHA256 (RFC 5869) with the default all-zero salt and a 32-byte output: one HMAC block. */
    private fun hkdf32(ikm: ByteArray, info: String): ByteArray {
        val extract = Mac.getInstance("HmacSHA256")
        extract.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = extract.doFinal(ikm)
        val expand = Mac.getInstance("HmacSHA256")
        expand.init(SecretKeySpec(prk, "HmacSHA256"))
        expand.update(info.toByteArray(Charsets.US_ASCII))
        expand.update(1.toByte())
        return expand.doFinal()
    }

    fun encrypt(keys: GroupKeys, plaintext: ByteArray): Sealed =
        encryptWithNonce(keys, ByteArray(NONCE_BYTES).also { random.nextBytes(it) }, plaintext)

    /** Deterministic form, for the test vector only. Never reuse a nonce. */
    fun encryptWithNonce(keys: GroupKeys, nonce: ByteArray, plaintext: ByteArray): Sealed {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(keys.mailbox.toByteArray(Charsets.US_ASCII))
        return Sealed(b64(nonce), b64(cipher.doFinal(plaintext)))
    }

    /** Throws [AuthenticationException] if the value doesn't decrypt with these keys. */
    fun decrypt(keys: GroupKeys, sealed: Sealed): ByteArray {
        try {
            val nonce = unb64(sealed.nonce)
            if (nonce.size != NONCE_BYTES) throw AuthenticationException()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(keys.mailbox.toByteArray(Charsets.US_ASCII))
            return cipher.doFinal(unb64(sealed.ciphertext))
        } catch (e: GeneralSecurityException) {
            throw AuthenticationException(e)
        } catch (e: IllegalArgumentException) {
            throw AuthenticationException(e)
        }
    }
}
