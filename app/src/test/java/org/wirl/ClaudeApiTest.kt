package org.wirl

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClaudeApiTest {
    private lateinit var server: MockWebServer

    @Before fun startServer() {
        server = MockWebServer().also { it.start() }
    }

    @After fun stopServer() {
        server.shutdown()
    }

    private fun api(client: OkHttpClient = OkHttpClient()) = ClaudeApi(server.url("/v1/messages").toString(), client)

    private fun reply(code: Int, body: String) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    private fun textReply(text: String) = """{"content":[{"type":"text","text":"$text"}],"stop_reason":"end_turn"}"""

    /** Runs a cleanup that must fail and returns the user-facing message. A successful result fails the test. */
    private suspend fun expectFailure(api: ClaudeApi): String = try {
        api.clean("sk-test", "system", "text")
        fail("Expected a CleanupException")
        ""
    } catch (e: CleanupException) {
        e.message.orEmpty()
    }

    private fun errorFor(code: Int, body: String = "{}") = runBlocking<String> {
        reply(code, body)
        expectFailure(api())
    }

    @Test fun sendsKeyVersionAndCleanupRequest() = runBlocking<Unit> {
        reply(200, textReply("Hi."))
        api().clean("sk-test", "SYSTEM PROMPT", "um hi")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("sk-test", request.getHeader("x-api-key"))
        assertEquals("2023-06-01", request.getHeader("anthropic-version"))
        assertTrue(request.getHeader("content-type")!!.startsWith("application/json"))

        val body = JSONObject(request.body.readUtf8())
        assertEquals("claude-haiku-5-5", body.getString("model"))
        assertEquals(1024, body.getInt("max_tokens"))
        assertEquals("SYSTEM PROMPT", body.getString("system"))
        val message = body.getJSONArray("messages").getJSONObject(0)
        assertEquals("user", message.getString("role"))
        assertEquals("um hi", message.getString("content"))
    }

    @Test fun returnsTrimmedTextBlocksOnly() = runBlocking<Unit> {
        reply(200, """{"content":[{"type":"thinking","text":"x"},{"type":"text","text":"  Hi there.  \n"}],"stop_reason":"end_turn"}""")
        assertEquals("Hi there.", api().clean("sk-test", "system", "text"))
    }

    @Test fun badKeyIsReported() {
        assertEquals("Invalid API key", errorFor(401, """{"error":{"message":"invalid x-api-key"}}"""))
    }

    @Test fun forbiddenKeyIsReported() {
        assertEquals("API key not allowed", errorFor(403))
    }

    @Test fun rateLimitIsReported() {
        assertEquals("Rate limited, try again", errorFor(429))
    }

    @Test fun serverErrorIsReported() {
        assertEquals("Claude is unavailable, try again", errorFor(503))
    }

    @Test fun otherErrorShowsTheApiMessage() {
        assertEquals("bad request", errorFor(400, """{"error":{"message":"bad request"}}"""))
    }

    @Test fun emptyAnswerIsAnError() {
        val message = runBlocking<String> {
            reply(200, textReply("   "))
            expectFailure(api())
        }
        assertEquals("Empty response", message)
    }

    @Test fun truncatedAnswerIsAnError() {
        val message = runBlocking<String> {
            reply(200, """{"content":[{"type":"text","text":"partial"}],"stop_reason":"max_tokens"}""")
            expectFailure(api())
        }
        assertEquals("Text too long to clean up", message)
    }

    @Test fun nonJsonAnswerIsAnError() {
        assertEquals("Unexpected response", errorFor(200, "<html>oops</html>"))
    }

    @Test fun noInternetIsReported() = runBlocking<Unit> {
        // Port 1 has nothing listening, so the connection is refused straight away.
        val offline = ClaudeApi("http://127.0.0.1:1/v1/messages", OkHttpClient())
        assertEquals("No connection", expectFailure(offline))
    }

    @Test fun slowAnswerTimesOut() = runBlocking<Unit> {
        server.enqueue(MockResponse().setHeadersDelay(2, TimeUnit.SECONDS).setBody(textReply("late")))
        val impatient = api(OkHttpClient.Builder().callTimeout(300, TimeUnit.MILLISECONDS).build())
        assertEquals("Timed out, try again", expectFailure(impatient))
    }
}
