package org.wirl

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

class CleanupException(message: String) : Exception(message)

/**
 * Talks to the Anthropic Messages API. The endpoint and HTTP client are parameters so tests can point
 * them at a fake server.
 */
class ClaudeApi(
    private val endpoint: String = ENDPOINT,
    private val http: OkHttpClient = defaultHttp(),
) {
    /** Returns the cleaned text, or throws [CleanupException] with a short message for the user. */
    suspend fun clean(apiKey: String, systemPrompt: String, text: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoint)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(requestBody(systemPrompt, text).toRequestBody(JSON))
            .build()

        try {
            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw CleanupException(errorMessage(response.code, raw))
                parseResponse(raw)
            }
        } catch (e: CleanupException) {
            throw e
        } catch (e: InterruptedIOException) {
            throw CleanupException("Timed out, try again")
        } catch (e: IOException) {
            throw CleanupException("No connection")
        } catch (e: Exception) {
            throw CleanupException("Unexpected response")
        }
    }

    companion object {
        const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        const val MODEL = "claude-haiku-5-5"
        const val MAX_TOKENS = 1024
        private val JSON = "application/json".toMediaType()

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(15, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        fun requestBody(systemPrompt: String, text: String): String = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", MAX_TOKENS)
            .put("system", systemPrompt)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", text)))
            .toString()

        fun parseResponse(raw: String): String {
            val json = JSONObject(raw)
            if (json.optString("stop_reason") == "max_tokens") {
                // A truncated reply would silently drop part of the message.
                throw CleanupException("Text too long to clean up")
            }
            val content = json.getJSONArray("content")
            val out = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "text") out.append(block.optString("text"))
            }
            val cleaned = out.toString().trim()
            if (cleaned.isEmpty()) throw CleanupException("Empty response")
            return cleaned
        }

        fun errorMessage(code: Int, raw: String): String {
            val apiMessage = runCatching { JSONObject(raw).getJSONObject("error").getString("message") }.getOrNull()
            return when (code) {
                401 -> "Invalid API key"
                403 -> "API key not allowed"
                429 -> "Rate limited, try again"
                in 500..599 -> "Claude is unavailable, try again"
                else -> apiMessage?.take(80) ?: "Error $code"
            }
        }
    }
}
