package org.wirl

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Why a sync round failed. [problem] is what the phone shows (docs/sync-protocol.md §8). */
sealed class RelayError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** 401: the mailbox belongs to a different token. */
    class Unauthorized : RelayError("the sync service no longer accepts this pairing (401)")

    /** 410: the computer disconnected all phones. */
    class Closed : RelayError("the sync group was closed (410)")

    /** The value doesn't decrypt: wrong secret, or tampering. */
    class Decrypt(cause: Throwable? = null) : RelayError("couldn't decrypt the synced dictionary", cause)

    class Network(cause: Throwable) : RelayError("couldn't reach the sync service: ${cause.message}", cause)

    class Server(val code: Int) : RelayError("the sync service answered $code")

    class TooManyConflicts : RelayError("kept conflicting with another device; will retry")

    class BadData(cause: Throwable) : RelayError("unexpected data: ${cause.message}", cause)

    val problem: SyncProblem
        get() = when (this) {
            is Unauthorized, is Closed, is Decrypt -> SyncProblem.NEEDS_PAIRING
            is Network -> SyncProblem.UNREACHABLE
            is Server, is TooManyConflicts, is BadData -> SyncProblem.FAILED
        }
}

/** The mailbox's current value (§5). */
data class Remote(val version: Long, val nonce: String, val ciphertext: String) {
    val sealed: SyncCrypto.Sealed get() = SyncCrypto.Sealed(nonce, ciphertext)
}

sealed class PutResult {
    data class Stored(val version: Long) : PutResult()

    /** 409: someone wrote first. This is the current value. */
    data class Conflict(val current: Remote) : PutResult()
}

/** The relay API (§5). [get] returns null for an empty mailbox. Both throw [RelayError]. */
interface RelayTransport {
    fun get(keys: SyncCrypto.GroupKeys): Remote?

    fun put(keys: SyncCrypto.GroupKeys, baseVersion: Long, sealed: SyncCrypto.Sealed): PutResult
}

/** The relay over HTTPS, on the app's shared OkHttp client: connect timeout 10 s, whole request 20 s. */
class HttpRelay(base: String, http: OkHttpClient = sharedHttp) : RelayTransport {
    private val base = base.trim().trimEnd('/')
    private val client = http

    private fun url(keys: SyncCrypto.GroupKeys) = "$base/v2/m/${keys.mailbox}"

    override fun get(keys: SyncCrypto.GroupKeys): Remote? {
        val request = Request.Builder().url(url(keys)).get().header("Authorization", "Bearer ${keys.token}").build()
        return call(request) { code, body ->
            when (code) {
                200 -> remote(body)
                404 -> null
                else -> throw statusError(code)
            }
        }
    }

    override fun put(keys: SyncCrypto.GroupKeys, baseVersion: Long, sealed: SyncCrypto.Sealed): PutResult {
        val json = JSONObject()
            .put("baseVersion", baseVersion)
            .put("nonce", sealed.nonce)
            .put("ciphertext", sealed.ciphertext)
            .toString()
        val request = Request.Builder()
            .url(url(keys))
            .put(json.toRequestBody(JSON))
            .header("Authorization", "Bearer ${keys.token}")
            .build()
        return call(request) { code, body ->
            when (code) {
                200 -> PutResult.Stored(parse(body) { it.getLong("version") })
                409 -> PutResult.Conflict(remote(body))
                else -> throw statusError(code)
            }
        }
    }

    private fun <T> call(request: Request, handle: (Int, String) -> T): T {
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw RelayError.Network(e)
        }
        return response.use {
            val body = try {
                it.body?.string().orEmpty()
            } catch (e: IOException) {
                throw RelayError.Network(e)
            }
            handle(it.code, body)
        }
    }

    private fun remote(body: String): Remote = parse(body) {
        Remote(it.getLong("version"), it.getString("nonce"), it.getString("ciphertext"))
    }

    private fun <T> parse(body: String, read: (JSONObject) -> T): T = try {
        read(JSONObject(body))
    } catch (e: JSONException) {
        throw RelayError.BadData(e)
    }

    private fun statusError(code: Int): RelayError = when (code) {
        401 -> RelayError.Unauthorized()
        410 -> RelayError.Closed()
        else -> RelayError.Server(code)
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        /** The app's shared client (connections stay open), with the timeouts from §10. */
        val sharedHttp: OkHttpClient by lazy {
            ClaudeApi.sharedHttp.newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .build()
        }
    }
}

/** The result of one sync round. [entries] is the merged dictionary, tombstones included. */
data class SyncOutcome(val entries: List<DictionaryEntry>, val devices: List<SyncProtocol.Device>, val uploaded: Boolean)

/** One sync round (§6): read, decrypt, merge, upload only if needed, and on a conflict merge again (5 times at most). */
object SyncRound {
    const val MAX_CONFLICT_RETRIES = 5

    fun run(
        relay: RelayTransport,
        keys: SyncCrypto.GroupKeys,
        local: List<DictionaryEntry>,
        me: SyncProtocol.Device,
        now: Long,
    ): SyncOutcome {
        var remote = relay.get(keys)
        repeat(MAX_CONFLICT_RETRIES + 1) {
            val payload = remote?.let { open(keys, it) }
            val outcome = SyncProtocol.mergeWithMailbox(local, payload, me, now)
            val upload = outcome.upload ?: return SyncOutcome(outcome.entries, payload?.devices.orEmpty(), uploaded = false)
            when (val put = relay.put(keys, remote?.version ?: 0L, SyncProtocol.seal(keys, upload))) {
                is PutResult.Stored -> return SyncOutcome(outcome.entries, upload.devices, uploaded = true)
                is PutResult.Conflict -> remote = put.current
            }
        }
        throw RelayError.TooManyConflicts()
    }

    private fun open(keys: SyncCrypto.GroupKeys, remote: Remote): SyncProtocol.Payload = try {
        SyncProtocol.open(keys, remote.sealed)
    } catch (e: SyncCrypto.AuthenticationException) {
        throw RelayError.Decrypt(e)
    } catch (e: JSONException) {
        throw RelayError.BadData(e)
    }
}
