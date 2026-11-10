package org.wirl

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.Assert.fail

/** Keys and encryption for the mailbox (docs/sync-protocol.md §3–§4). Plain JUnit: javax.crypto only. */
class SyncCryptoTest {
    private val secret = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="

    private fun unhex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun theProtocolTestVectorMatchesExactly() {
        val keys = SyncCrypto.derive(secret)
        assertEquals("edaba0f8f5a6b41660b29dfc43e0c1f9c3067ab5d6cef6c3564a014f7752ee28", keys.mailbox)
        assertEquals("AJz6SLfpq2jeFQJFKtPqDMJjLGr4HmRTVsv4YRMp_Cw", keys.token)
        assertEquals("81d430e62ca3afa96592b7a7c50f6213ccbde0fd21a6cebfa6b0d5038facb16c", SyncCrypto.tokenHash(keys.token))
        assertArrayEquals(unhex("beccb5b399d59b7385c4d5d5df2090be773deaf000144a2b2caa2a567232e841"), keys.key)

        val sealed = SyncCrypto.encryptWithNonce(keys, SyncCrypto.unb64("AAECAwQFBgcICQoL"), """{"entries":[],"devices":[]}""".toByteArray())
        assertEquals("AAECAwQFBgcICQoL", sealed.nonce)
        assertEquals("UcqqKN+j0S2YVtLI2StUerwpqF/KNKjI1Hzcac50dzPtEw5tYv5gpse67A==", sealed.ciphertext)
        assertEquals("""{"entries":[],"devices":[]}""", String(SyncCrypto.decrypt(keys, sealed)))
    }

    @Test fun theTokenIsUrlSafeWithoutPadding() {
        repeat(20) {
            val token = SyncCrypto.derive(SyncCrypto.newSecret()).token
            assertEquals(43, token.length)
            assertEquals(token, token.filter { it.isLetterOrDigit() || it == '-' || it == '_' })
        }
    }

    @Test fun aRoundTripUsesAFreshNonceEachTime() {
        val keys = SyncCrypto.derive(SyncCrypto.newSecret())
        val a = SyncCrypto.encrypt(keys, "hello".toByteArray())
        val b = SyncCrypto.encrypt(keys, "hello".toByteArray())
        assertNotEquals(a.nonce, b.nonce)
        assertEquals("hello", String(SyncCrypto.decrypt(keys, a)))
        assertEquals("hello", String(SyncCrypto.decrypt(keys, b)))
    }

    @Test fun theWrongSecretIsRejected() {
        val sealed = SyncCrypto.encrypt(SyncCrypto.derive(secret), "x".toByteArray())
        expectAuthFailure { SyncCrypto.decrypt(SyncCrypto.derive(SyncCrypto.newSecret()), sealed) }
    }

    @Test fun anotherMailboxIdAsAadIsRejected() {
        val keys = SyncCrypto.derive(secret)
        val sealed = SyncCrypto.encrypt(keys, "x".toByteArray())
        val sameKeyOtherMailbox = SyncCrypto.GroupKeys("0".repeat(64), keys.token, keys.key)
        expectAuthFailure { SyncCrypto.decrypt(sameKeyOtherMailbox, sealed) }
    }

    @Test fun tamperingIsRejected() {
        val keys = SyncCrypto.derive(secret)
        val sealed = SyncCrypto.encrypt(keys, "a longer message".toByteArray())
        val bytes = SyncCrypto.unb64(sealed.ciphertext).also { it[0] = (it[0].toInt() xor 1).toByte() }
        expectAuthFailure { SyncCrypto.decrypt(keys, SyncCrypto.Sealed(sealed.nonce, SyncCrypto.b64(bytes))) }
        expectAuthFailure { SyncCrypto.decrypt(keys, SyncCrypto.Sealed("AAAA", sealed.ciphertext)) }
        expectAuthFailure { SyncCrypto.decrypt(keys, SyncCrypto.Sealed(sealed.nonce, "not base64!")) }
    }

    @Test fun onlyA32ByteSecretIsAccepted() {
        assertNull(SyncCrypto.secretBytes("c2hvcnQ="))
        assertNull(SyncCrypto.secretBytes("not base64!"))
        try {
            SyncCrypto.derive("c2hvcnQ=")
            fail("a short secret must be rejected")
        } catch (e: SyncCrypto.BadSecretException) {
            // expected
        }
    }

    private fun expectAuthFailure(block: () -> Unit) {
        try {
            block()
            fail("expected AuthenticationException")
        } catch (e: SyncCrypto.AuthenticationException) {
            // expected
        }
    }
}
