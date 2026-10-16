package org.wirl

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.EventListener
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
 * them at a fake server. The default client is shared, so its connections stay open between dictations.
 */
class ClaudeApi(
    private val endpoint: String = ENDPOINT,
    http: OkHttpClient = sharedHttp,
) {
    /** The same connection pool as [http], plus per-request timing. */
    private val client: OkHttpClient = http.newBuilder()
        .eventListenerFactory { call -> call.request().tag(CallTimer::class.java) ?: EventListener.NONE }
        .build()

    /** Returns the cleaned text, or throws [CleanupException] with a short message for the user. [onTiming] gets the timing of the request, success or not. */
    suspend fun clean(
        apiKey: String,
        systemPrompt: String,
        text: String,
        onTiming: (ClaudeCallTiming) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val timer = CallTimer()
        val request = Request.Builder()
            .url(endpoint)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(requestBody(systemPrompt, text).toRequestBody(JSON))
            .tag(CallTimer::class.java, timer)
            .build()

        var outputTokens: Int? = null
        var thinking = false
        try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw CleanupException(errorMessage(response.code, raw))
                parseUsage(raw).let { (tokens, usedThinking) ->
                    outputTokens = tokens
                    thinking = usedThinking
                }
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
        } finally {
            onTiming(timer.result(outputTokens, thinking))
        }
    }

    /**
     * Opens the connection to the API before the user needs it, so the first real request doesn't pay for the TLS handshake.
     * Any failure is ignored: this is only a warm-up.
     */
    fun warmUp(url: String = ORIGIN) {
        runCatching {
            client.newCall(Request.Builder().url(url).head().build()).execute().use { }
        }
    }

    /** Times one request: from the call starting, to the first byte, to the end. */
    private class CallTimer : EventListener() {
        private var startNs = 0L
        private var connecting = false
        private var firstByteNs = 0L
        private var endNs = 0L

        override fun callStart(call: Call) {
            startNs = System.nanoTime()
        }

        override fun connectStart(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) {
            connecting = true
        }

        override fun responseHeadersStart(call: Call) {
            if (firstByteNs == 0L) firstByteNs = System.nanoTime()
        }

        override fun callEnd(call: Call) {
            endNs = System.nanoTime()
        }

        override fun callFailed(call: Call, ioe: IOException) {
            endNs = System.nanoTime()
        }

        fun result(outputTokens: Int?, thinkingUsed: Boolean): ClaudeCallTiming {
            val end = if (endNs == 0L) System.nanoTime() else endNs
            val firstByte = if (firstByteNs == 0L) end else firstByteNs
            return ClaudeCallTiming(
                firstByteMs = (firstByte - startNs) / 1_000_000,
                totalMs = (end - startNs) / 1_000_000,
                connectionReused = !connecting,
                outputTokens = outputTokens,
                thinkingUsed = thinkingUsed,
            )
        }
    }

    companion object {
        const val ORIGIN = "https://api.anthropic.com/"
        const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        const val MODEL = "claude-haiku-5-5"
        const val MAX_TOKENS = 1024
        private val JSON = "application/json".toMediaType()

        /** One client for the whole app, so its connections are kept and reused. */
        val sharedHttp: OkHttpClient by lazy { defaultHttp() }

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

        /** The output token count and whether the reply used thinking, from a reply body. */
        fun parseUsage(raw: String): Pair<Int?, Boolean> {
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null to false
            val tokens = json.optJSONObject("usage")?.takeIf { it.has("output_tokens") }?.optInt("output_tokens")
            val content = json.optJSONArray("content") ?: return tokens to false
            val thinking = (0 until content.length()).any { content.optJSONObject(it)?.optString("type") == "thinking" }
            return tokens to thinking
        }

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
            // An empty reply means there were no words to clean. The caller inserts nothing for it.
            return out.toString().trim()
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
