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
 * The Cantonese and Mandarin cleanup sets (evals/manual/cantonese_cases.json, mandarin_cases.json), run by hand with
 * your own key. Never in CI: it runs only
 * when WIRL_CANTONESE_EVAL=1 AND ANTHROPIC_API_KEY are both set, and no workflow sets WIRL_CANTONESE_EVAL.
 *
 *     WIRL_CANTONESE_EVAL=1 ANTHROPIC_API_KEY=sk-... ./gradlew testDebugUnitTest --tests org.wirl.CantoneseEvalTest
 *
 * Inputs look like SenseVoice output (simplified characters, no punctuation, English in capitals). Each goes through the
 * same prompt the app sends in that language's mode, and Haiku judges the output. Results: app/build/cantonese-eval/.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CantoneseEvalTest {
    private data class Case(val id: String, val input: String, val expected: String, val dictionary: List<DictionaryEntry>)

    @Test fun cantoneseCleanupMeetsTheQualityBar() =
        runSet("cantonese", File("../evals/manual/cantonese_cases.json"), CleanupMode.CANTONESE, CANTONESE_JUDGE)

    @Test fun mandarinCleanupMeetsTheQualityBar() =
        runSet("mandarin", File("../evals/manual/mandarin_cases.json"), CleanupMode.MANDARIN, MANDARIN_JUDGE)

    private fun runSet(name: String, file: File, mode: CleanupMode, judgeSystem: String) {
        val key = System.getenv("ANTHROPIC_API_KEY")
        Assume.assumeTrue("Manual only: set WIRL_CANTONESE_EVAL=1 to run it", System.getenv("WIRL_CANTONESE_EVAL") == "1")
        Assume.assumeTrue("ANTHROPIC_API_KEY is not set", !key.isNullOrBlank())

        val cases = loadCases(file)
        val api = ClaudeApi()
        val results = JSONArray()
        var passed = 0
        for (case in cases) {
            val system = CleanupPrompt.build("", case.dictionary, case.input, mode = mode)
            val output = try {
                runBlocking { api.clean(key!!, system, case.input) }
            } catch (e: CleanupException) {
                "(cleanup failed: ${e.message})"
            }
            val (pass, reason) = judge(key!!, judgeSystem, case, output)
            if (pass) passed++
            println("${if (pass) "PASS" else "FAIL"} ${case.id}: $output\n  $reason")
            results.put(JSONObject().put("id", case.id).put("pass", pass).put("reason", reason).put("output", output))
        }
        File("build/cantonese-eval").mkdirs()
        File("build/cantonese-eval/$name-results.json").writeText(results.toString(2))
        val rate = passed.toDouble() / cases.size
        println("$name eval: $passed of ${cases.size} passed (${"%.0f".format(rate * 100)}%)")
        assertTrue("$name cleanup pass rate ${"%.0f".format(rate * 100)}% is below the 90% bar", rate >= 0.9)
    }

    private fun judge(key: String, judgeSystem: String, case: Case, output: String): Pair<Boolean, String> {
        val user = "Spoken input (speech recognition):\n${case.input}\n\nExpected:\n${case.expected}\n\nActual output:\n$output"
        val body = JSONObject()
            .put("model", ClaudeApi.MODEL)
            .put("max_tokens", 400)
            .put("thinking", JSONObject().put("type", "disabled"))
            .put("system", judgeSystem)
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
            Case(c.getString("id"), c.getString("input"), c.getString("expected"), dictionary)
        }
    }

    private companion object {
        const val CANTONESE_JUDGE = """You grade a Cantonese dictation cleanup. You get the speech recognizer's text, what's expected, and the actual output.

PASS only if the output: keeps the expected meaning; is written in spoken Cantonese (口語) as the speaker said it, not formal written Chinese (書面語) or Mandarin phrasing; uses traditional characters only (Hong Kong style), with no simplified characters; keeps English words in English where they were spoken; removes filler sounds and false starts but keeps sentence-final particles; and uses full-width punctuation in Chinese text. Ignore small differences in wording choice that keep the meaning, and in the casing of English words.

Reply with only a JSON object: {"verdict": "PASS" or "FAIL", "reason": "one short sentence"}."""

        const val MANDARIN_JUDGE = """You grade a Mandarin Chinese dictation cleanup. You get the speech recognizer's text, what's expected, and the actual output.

PASS only if the output: keeps the expected meaning; is natural written Mandarin as the speaker said it; uses simplified characters only, with no traditional characters and no Cantonese words or characters; keeps English words in English where they were spoken; removes filler sounds and false starts; and uses full-width punctuation in Chinese text. Ignore small differences in wording choice that keep the meaning, and in the casing of English words.

Reply with only a JSON object: {"verdict": "PASS" or "FAIL", "reason": "one short sentence"}."""
    }
}
