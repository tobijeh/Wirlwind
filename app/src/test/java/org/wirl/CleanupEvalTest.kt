package org.wirl

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Runs the cleanup quality set in evals/cleanup_cases.json against the real Claude API, with the same prompt the app
 * sends. Claude Haiku then judges each output against the expected meaning. Skipped unless ANTHROPIC_API_KEY is set,
 * so normal test runs never call the API. The workflow in .github/workflows/cleanup-eval.yml sets the key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CleanupEvalTest {
    private data class Case(
        val id: String,
        val input: String,
        val expected: String,
        val dictionary: List<DictionaryEntry>,
        val alternatives: List<String>,
    )

    private data class Result(val id: String, val pass: Boolean, val reason: String, val output: String)

    @Test fun cleanupMeetsTheQualityBar() {
        val key = System.getenv("ANTHROPIC_API_KEY")
        Assume.assumeTrue("ANTHROPIC_API_KEY is not set, so the cleanup eval is skipped", !key.isNullOrBlank())

        val cases = loadCases(File("../evals/cleanup_cases.json"))
        val api = ClaudeApi()
        val results = cases.map { evaluate(api, key!!, it) }
        writeResults(results)

        val passed = results.count { it.pass }
        val rate = passed.toDouble() / results.size
        println("cleanup eval: $passed of ${results.size} passed (${"%.0f".format(rate * 100)}%)")
        results.filterNot { it.pass }.forEach { println("FAIL ${it.id}: ${it.reason}\n  output: ${it.output}") }
        assertTrue("cleanup pass rate ${"%.0f".format(rate * 100)}% is below the 90% bar", rate >= 0.9)
    }

    private fun evaluate(api: ClaudeApi, key: String, case: Case): Result {
        val system = CleanupPrompt.build("", case.dictionary, case.input, case.alternatives)
        val output = try {
            runBlocking { api.clean(key, system, case.input) }
        } catch (e: CleanupException) {
            return Result(case.id, false, "cleanup failed: ${e.message}", "")
        }
        val (pass, reason) = judge(key, case, output)
        return Result(case.id, pass, reason, output)
    }

    private fun judge(key: String, case: Case, output: String): Pair<Boolean, String> {
        val user = "Spoken input:\n${case.input}\n\nExpected meaning:\n${case.expected}\n\nActual output:\n$output"
        val body = JSONObject()
            .put("model", ClaudeApi.MODEL)
            .put("max_tokens", 400)
            .put("system", JUDGE_SYSTEM)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
            .toString()
        val request = Request.Builder()
            .url(ClaudeApi.ENDPOINT)
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        OkHttpClient().newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) return false to "judge request failed: HTTP ${response.code}"
            val text = JSONObject(raw).optJSONArray("content")?.let { content ->
                (0 until content.length()).joinToString("") { content.getJSONObject(it).optString("text") }
            }.orEmpty()
            val verdict = runCatching {
                JSONObject(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1))
            }.getOrNull() ?: return false to "judge reply unreadable"
            return verdict.optString("verdict").equals("PASS", ignoreCase = true) to verdict.optString("reason")
        }
    }

    private fun loadCases(file: File): List<Case> {
        val array = JSONArray(file.readText())
        return (0 until array.length()).map { i ->
            val c = array.getJSONObject(i)
            val dictionary = c.optJSONArray("dictionary")?.let { entries ->
                (0 until entries.length()).map { j ->
                    val e = entries.getJSONObject(j)
                    DictionaryEntry(
                        term = e.getString("term"),
                        soundsLike = e.optJSONArray("soundsLike")?.let { s -> (0 until s.length()).map { s.getString(it) } }.orEmpty(),
                        note = e.optString("note"),
                    )
                }
            }.orEmpty()
            val alternatives = c.optJSONArray("alternatives")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            Case(c.getString("id"), c.getString("input"), c.getString("expected"), dictionary, alternatives)
        }
    }

    private fun writeResults(results: List<Result>) {
        val out = File("build/cleanup-eval/results.json")
        out.parentFile?.mkdirs()
        val array = JSONArray()
        results.forEach {
            array.put(JSONObject().put("id", it.id).put("pass", it.pass).put("reason", it.reason).put("output", it.output))
        }
        out.writeText(array.toString(2))
    }

    private companion object {
        const val JUDGE_SYSTEM = """You grade a dictation cleanup. You get the spoken input, the expected meaning, and the actual output.

PASS only if the output carries the expected meaning and does what the expected meaning says: self-corrections resolved, false starts and repeats removed, spoken commands carried out and their words removed, content that is part of the message kept, dictionary terms used, sound-alike errors fixed, casual tone kept. Ignore small differences in punctuation and capitalization.

Reply with only a JSON object: {"verdict": "PASS" or "FAIL", "reason": "one short sentence"}."""
    }
}
