package org.wirl

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sync encryption (docs/sync-protocol.md §5): AES-256-GCM with a 12-byte nonce and 16-byte tag. The key is
 * HKDF-SHA256 of the 32 pairing-secret bytes, no salt, info "wirl-sync-v1". The additional authenticated data
 * is the phoneId. All base64 is standard with padding.
 */
object SyncCrypto {
    const val HKDF_INFO = "wirl-sync-v1"
    const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    class Sealed(val nonce: String, val ciphertext: String)

    /** Thrown when data can't be decrypted: wrong secret, wrong phoneId, or tampering. */
    class AuthenticationException(cause: Throwable? = null) : Exception("decryption failed", cause)

    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text.trim())

    /** 32 random bytes, base64. Only the computer makes secrets; this is for tests. */
    fun newSecret(): String = b64(ByteArray(32).also { random.nextBytes(it) })

    /** HKDF-SHA256 (RFC 5869) with the default all-zero salt. Returns 32 bytes. */
    fun deriveKey(secretB64: String): ByteArray {
        val ikm = unb64(secretB64)
        val extract = Mac.getInstance("HmacSHA256")
        extract.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = extract.doFinal(ikm)
        val expand = Mac.getInstance("HmacSHA256")
        expand.init(SecretKeySpec(prk, "HmacSHA256"))
        // 32 bytes is one HMAC block: T(1) = HMAC(PRK, info || 0x01).
        expand.update(HKDF_INFO.toByteArray(Charsets.US_ASCII))
        expand.update(1.toByte())
        return expand.doFinal()
    }

    fun encrypt(key: ByteArray, phoneId: String, plaintext: ByteArray): Sealed {
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        return Sealed(b64(nonce), encryptWithNonce(key, phoneId, nonce, plaintext))
    }

    /** Deterministic form, for the test vector only. Never reuse a nonce. */
    fun encryptWithNonce(key: ByteArray, phoneId: String, nonce: ByteArray, plaintext: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(phoneId.toByteArray(Charsets.UTF_8))
        return b64(cipher.doFinal(plaintext))
    }

    /** Throws [AuthenticationException] if the data doesn't decrypt with this key and phoneId. */
    fun decrypt(key: ByteArray, phoneId: String, nonceB64: String, ciphertextB64: String): ByteArray {
        try {
            val nonce = unb64(nonceB64)
            if (nonce.size != NONCE_BYTES) throw AuthenticationException()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(phoneId.toByteArray(Charsets.UTF_8))
            return cipher.doFinal(unb64(ciphertextB64))
        } catch (e: GeneralSecurityException) {
            throw AuthenticationException(e)
        } catch (e: IllegalArgumentException) {
            throw AuthenticationException(e)
        }
    }
}
