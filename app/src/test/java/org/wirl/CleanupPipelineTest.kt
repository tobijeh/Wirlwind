package org.wirl

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private val styleNotes = ""

    @Before fun start() {
        server = MockWebServer().also { it.start() }
    }

    @After fun stop() {
        server.shutdown()
    }

    private fun pipeline() = CleanupPipeline(
        api = ClaudeApi(server.url("/v1/messages").toString(), OkHttpClient()),
        apiKey = { "sk-test" },
        styleNotes = { styleNotes },
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

        assertTrue(system.startsWith(CleanupPrompt.CORE))
        assertTrue(system.contains("\"Sela\" (mishearings: salad)"))
    }

    @Test fun bothPathsUseTheSameModel() = runBlocking<Unit> {
        reply("ok")
        pipeline().clean("hold this text")
        assertEquals("claude-haiku-5-5", JSONObject(server.takeRequest().body.readUtf8()).getString("model"))
        reply("ok")
        pipeline().clean("dictated text", alternatives = listOf("dictated text two"))
        assertEquals("claude-haiku-5-5", JSONObject(server.takeRequest().body.readUtf8()).getString("model"))
    }

    @Test fun holdToCleanSendsNoAlternatives() = runBlocking<Unit> {
        reply("ok")
        pipeline().clean("hold this text")
        val system = JSONObject(server.takeRequest().body.readUtf8()).getString("system")
        assertFalse(system.contains("alternative transcriptions"))
    }

    @Test fun dictationSendsAlternativesWithTheInstruction() = runBlocking<Unit> {
        reply("ok")
        pipeline().clean("I'm meeting Sella", alternatives = listOf("I'm meeting Stella"))
        val system = JSONObject(server.takeRequest().body.readUtf8()).getString("system")
        assertTrue(system.contains(CleanupPrompt.ALTERNATIVES_INSTRUCTION))
        assertTrue(system.contains("Alternative 1: I'm meeting Stella"))
    }

    @Test fun unrelatedDictionaryEntriesAreNotSent() = runBlocking<Unit> {
        reply("ok")
        pipeline().clean("let's get lunch")
        val system = JSONObject(server.takeRequest().body.readUtf8()).getString("system")

        assertEquals(CleanupPrompt.CORE, system)
    }
}
