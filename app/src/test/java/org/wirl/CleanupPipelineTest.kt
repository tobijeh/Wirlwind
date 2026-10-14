package org.wirl

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Hold-to-clean and dictation both use [CleanupPipeline], so the same transcript must give the same request and result. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CleanupPipelineTest {
    private lateinit var server: MockWebServer
    private val dictionary = listOf(DictionaryEntry("Sela", listOf("salad"), "my web app"))
    private val instructions = "You clean up dictated text."

    @Before fun start() {
        server = MockWebServer().also { it.start() }
    }

    @After fun stop() {
        server.shutdown()
    }

    private fun pipeline() = CleanupPipeline(
        api = ClaudeApi(server.url("/v1/messages").toString(), OkHttpClient()),
        apiKey = { "sk-test" },
        instructions = { instructions },
        dictionary = { dictionary },
    )

    private fun reply(text: String) {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"$text"}],"stop_reason":"end_turn"}"""))
    }

    @Test fun dictationGivesTheSameOutputAsHoldToClean() = runBlocking<Unit> {
        val transcript = "um i'm meeting salad at noon like tomorrow"
        reply("I'm meeting Sela at noon tomorrow.")
        val holdResult = pipeline().clean(transcript)
        val holdRequest = server.takeRequest().body.readUtf8()

        reply("I'm meeting Sela at noon tomorrow.")
        val dictationResult = pipeline().clean(transcript)
        val dictationRequest = server.takeRequest().body.readUtf8()

        assertEquals(holdResult, dictationResult)
        assertEquals(holdRequest, dictationRequest)
    }

    @Test fun cleanupSendsTheInstructionsAndTheRelevantDictionary() = runBlocking<Unit> {
        reply("ok")
        pipeline().clean("I'm meeting salad at noon")
        val system = JSONObject(server.takeRequest().body.readUtf8()).getString("system")

        assertTrue(system.startsWith(instructions))
        assertTrue(system.contains("\"Sela\" (mishearings: salad)"))
    }

    @Test fun unrelatedDictionaryEntriesAreNotSent() = runBlocking<Unit> {
        reply("ok")
        pipeline().clean("let's get lunch")
        val system = JSONObject(server.takeRequest().body.readUtf8()).getString("system")

        assertEquals(instructions, system)
    }
}
