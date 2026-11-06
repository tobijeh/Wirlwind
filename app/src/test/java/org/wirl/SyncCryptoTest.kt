package org.wirl

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Encryption (docs/sync-protocol.md §5). Plain JUnit: javax.crypto only. */
class SyncCryptoTest {
    private val secret = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=" // bytes 0x00..0x1f
    private val phoneId = "0b7c6f7e-1d2a-4b5c-9d8e-7f6a5b4c3d2e"

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test fun keyMatchesTheProtocolTestVector() {
        assertEquals("f2b4a1a6ce55a07aea8ccb22e73d9ad6f36e4e177601c4e0d1be7ad47e6eb66c", hex(SyncCrypto.deriveKey(secret)))
    }

    @Test fun ciphertextMatchesTheProtocolTestVector() {
        val nonce = ByteArray(12) { it.toByte() }
        val ct = SyncCrypto.encryptWithNonce(SyncCrypto.deriveKey(secret), phoneId, nonce, """{"entries":[]}""".toByteArray())
        assertEquals("T4GGLLyKJaiy63rNMucjUgABVlyT/DGPB7LUjk1Z", ct)
        assertEquals("AAECAwQFBgcICQoL", SyncCrypto.b64(nonce))
    }

    @Test fun roundTrip() {
        val key = SyncCrypto.deriveKey(SyncCrypto.newSecret())
        val plain = """{"entries":[{"term":"Sela"}]}""".toByteArray()
        val sealed = SyncCrypto.encrypt(key, phoneId, plain)
        assertEquals(12, SyncCrypto.unb64(sealed.nonce).size)
        assertArrayEquals(plain, SyncCrypto.decrypt(key, phoneId, sealed.nonce, sealed.ciphertext))
    }

    @Test fun noncesAreNotReused() {
        val key = SyncCrypto.deriveKey(secret)
        val nonces = (1..50).map { SyncCrypto.encrypt(key, phoneId, ByteArray(1)).nonce }.toSet()
        assertEquals(50, nonces.size)
    }

    @Test fun wrongSecretIsRejected() {
        val sealed = SyncCrypto.encrypt(SyncCrypto.deriveKey(secret), phoneId, "hi".toByteArray())
        assertThrows(SyncCrypto.AuthenticationException::class.java) {
            SyncCrypto.decrypt(SyncCrypto.deriveKey(SyncCrypto.newSecret()), phoneId, sealed.nonce, sealed.ciphertext)
        }
    }

    @Test fun wrongPhoneIdIsRejected() {
        val key = SyncCrypto.deriveKey(secret)
        val sealed = SyncCrypto.encrypt(key, phoneId, "hi".toByteArray())
        assertThrows(SyncCrypto.AuthenticationException::class.java) {
            SyncCrypto.decrypt(key, "11111111-1111-4111-8111-111111111111", sealed.nonce, sealed.ciphertext)
        }
    }

    @Test fun tamperedCiphertextIsRejected() {
        val key = SyncCrypto.deriveKey(secret)
        val sealed = SyncCrypto.encrypt(key, phoneId, "hello".toByteArray())
        val bytes = SyncCrypto.unb64(sealed.ciphertext).also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThrows(SyncCrypto.AuthenticationException::class.java) {
            SyncCrypto.decrypt(key, phoneId, sealed.nonce, SyncCrypto.b64(bytes))
        }
    }

    @Test fun garbageIsRejectedNotCrashed() {
        assertThrows(SyncCrypto.AuthenticationException::class.java) {
            SyncCrypto.decrypt(SyncCrypto.deriveKey(secret), phoneId, "not base64!", "AAAA")
        }
    }
}
