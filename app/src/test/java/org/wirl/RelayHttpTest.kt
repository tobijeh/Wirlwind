package org.wirl

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** [HttpRelay] speaks the relay API (docs/sync-protocol.md §5): method, path, bearer token, JSON, status codes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RelayHttpTest {
    private lateinit var server: MockWebServer
    private val keys = SyncCrypto.derive("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=")
    private val path = "/v2/m/edaba0f8f5a6b41660b29dfc43e0c1f9c3067ab5d6cef6c3564a014f7752ee28"
    private val auth = "Bearer AJz6SLfpq2jeFQJFKtPqDMJjLGr4HmRTVsv4YRMp_Cw"
    private val sealed = SyncCrypto.Sealed("AAECAwQFBgcICQoL", "UcqqKN+j0S2YVtLI2StUerwpqF/KNKjI1Hzcac50dzPtEw5tYv5gpse67A==")

    @Before fun start() {
        server = MockWebServer().also { it.start() }
    }

    @After fun stop() = server.shutdown()

    /** The base URL with a trailing slash, which the client trims. */
    private fun relay(client: OkHttpClient = OkHttpClient()) = HttpRelay(server.url("/").toString(), client)

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test fun getSendsTheTokenAndReadsTheValue() {
        server.enqueue(json(200, """{"version":3,"nonce":"${sealed.nonce}","ciphertext":"${sealed.ciphertext}"}"""))
        val remote = relay().get(keys)!!
        assertEquals(Remote(3, sealed.nonce, sealed.ciphertext), remote)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals(path, request.path)
        assertEquals(auth, request.getHeader("Authorization"))
    }

    @Test fun getOfAnEmptyMailboxIsNull() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(relay().get(keys))
    }

    @Test fun putSendsBaseVersionNonceAndCiphertext() {
        server.enqueue(json(200, """{"version":4}"""))
        val result = relay().put(keys, 3, sealed)
        assertEquals(PutResult.Stored(4), result)
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals(path, request.path)
        assertEquals(auth, request.getHeader("Authorization"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals(3L, body.getLong("baseVersion"))
        assertEquals(sealed.nonce, body.getString("nonce"))
        assertEquals(sealed.ciphertext, body.getString("ciphertext"))
        assertEquals(3, body.length())
    }

    @Test fun aConflictReturnsTheCurrentValue() {
        server.enqueue(json(409, """{"version":5,"nonce":"${sealed.nonce}","ciphertext":"${sealed.ciphertext}"}"""))
        assertEquals(PutResult.Conflict(Remote(5, sealed.nonce, sealed.ciphertext)), relay().put(keys, 3, sealed))
    }

    @Test fun statusCodesMapToTheRightErrors() {
        fun errorFor(code: Int, put: Boolean): RelayError {
            server.enqueue(MockResponse().setResponseCode(code))
            try {
                if (put) relay().put(keys, 0, sealed) else relay().get(keys)
            } catch (e: RelayError) {
                return e
            }
            fail("expected an error for $code")
            throw AssertionError()
        }
        assertTrue(errorFor(410, put = false) is RelayError.Closed)
        assertTrue(errorFor(410, put = true) is RelayError.Closed)
        assertTrue(errorFor(401, put = false) is RelayError.Unauthorized)
        assertEquals(SyncProblem.NEEDS_PAIRING, errorFor(401, put = true).problem)
        assertEquals(500, (errorFor(500, put = false) as RelayError.Server).code)
        assertEquals(SyncProblem.FAILED, errorFor(413, put = true).problem)
        assertEquals(SyncProblem.FAILED, errorFor(400, put = true).problem)
    }

    @Test fun noConnectionIsANetworkError() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val client = OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
        try {
            relay(client).get(keys)
            fail("expected a network error")
        } catch (e: RelayError) {
            assertTrue(e is RelayError.Network)
            assertEquals(SyncProblem.UNREACHABLE, e.problem)
        }
    }

    @Test fun theSharedClientUsesTheProtocolTimeouts() {
        assertEquals(10_000, HttpRelay.sharedHttp.connectTimeoutMillis)
        assertEquals(20_000, HttpRelay.sharedHttp.callTimeoutMillis)
    }
}
