package org.wirl

import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One address to try for a computer. */
data class SyncEndpoint(val address: InetAddress, val port: Int)

sealed class SyncResult {
    /** The computer's merged dictionary, and the address that worked. */
    class Synced(val entries: List<DictionaryEntry>, val endpoint: SyncEndpoint) : SyncResult()

    /** 401: the computer no longer accepts this pairing. The user has to scan a new code. */
    object NeedsPairing : SyncResult()

    /**
     * The sync didn't work. [unreachable] means no address answered at all (as opposed to an answer that was an
     * error), and [tried] lists the addresses that were tried.
     */
    class Failed(
        val reason: String,
        val code: Int? = null,
        val error: Throwable? = null,
        val unreachable: Boolean = false,
        val tried: List<SyncEndpoint> = emptyList(),
    ) : SyncResult()
}

/**
 * One sync exchange with one computer (docs/sync-protocol.md §4–§6).
 *
 * Address order: the address that worked last time and the QR code's hosts first, tried at the same time, and the
 * first one to answer is used. Only if none of them answers does [discover] run (NSD, which takes a few seconds),
 * and then the addresses it finds are tried.
 */
class SyncClient(
    private val discover: (PairedComputer) -> List<SyncEndpoint>,
    private val transport: (SyncEndpoint, String) -> LocalHttp.Response = { ep, body ->
        LocalHttp.postJson(ep.address, ep.port, SyncProtocol.PATH, body)
    },
) {
    /** What answered: the address and its HTTP response. */
    private class Answer(val endpoint: SyncEndpoint, val response: LocalHttp.Response)

    /** The last address that worked, then the QR code's hosts. Private addresses only, no duplicates. */
    fun knownEndpoints(computer: PairedComputer): List<SyncEndpoint> {
        val last = LocalHttp.literal(computer.lastHost)
            ?.let { SyncEndpoint(it, computer.lastPort.takeIf { p -> p > 0 } ?: computer.port) }
        val fromQr = computer.hosts.mapNotNull { LocalHttp.literal(it) }.map { SyncEndpoint(it, computer.port) }
        return (listOfNotNull(last) + fromQr).filter { LocalHttp.isPrivate(it.address) }.distinct()
    }

    fun sync(computer: PairedComputer, phoneId: String, phoneName: String, entries: List<DictionaryEntry>): SyncResult {
        val body = SyncProtocol.buildRequest(computer.secret, phoneId, phoneName, entries)
        val tried = mutableListOf<SyncEndpoint>()
        var lastError: Throwable? = null

        fun attempt(endpoints: List<SyncEndpoint>): Answer? {
            val fresh = endpoints.filter { LocalHttp.isPrivate(it.address) && it !in tried }
            if (fresh.isEmpty()) return null
            tried += fresh
            return firstAnswer(fresh, body) { lastError = it }
        }

        val answer = attempt(knownEndpoints(computer))
            ?: attempt(runCatching { discover(computer) }.getOrDefault(emptyList()))
            ?: return if (tried.isEmpty()) {
                SyncResult.Failed("no address for ${computer.name}", unreachable = true)
            } else {
                val list = tried.joinToString { "${it.address.hostAddress}:${it.port}" }
                SyncResult.Failed(
                    "couldn't reach ${computer.name} ($list): ${lastError?.message ?: "no answer"}",
                    null,
                    lastError,
                    unreachable = true,
                    tried = tried.toList(),
                )
            }

        val host = answer.endpoint.address.hostAddress
        return when (answer.response.code) {
            200 -> try {
                SyncResult.Synced(SyncProtocol.readResponse(computer.secret, phoneId, answer.response.body), answer.endpoint)
            } catch (e: Exception) {
                SyncResult.Failed("unreadable response from $host", 200, e, tried = tried.toList())
            }
            401 -> SyncResult.NeedsPairing
            else -> SyncResult.Failed("HTTP ${answer.response.code} from $host", answer.response.code, tried = tried.toList())
        }
    }

    /**
     * Sends [body] to every endpoint at once and returns the first that answers with any HTTP status. Endpoints that
     * fail with an [IOException] are reported to [onError]. Null if none answers.
     */
    private fun firstAnswer(endpoints: List<SyncEndpoint>, body: String, onError: (Throwable) -> Unit): Answer? {
        if (endpoints.size == 1) {
            val endpoint = endpoints.single()
            return try {
                Answer(endpoint, transport(endpoint, body))
            } catch (e: IOException) {
                onError(e)
                null
            }
        }
        val pool = Executors.newFixedThreadPool(endpoints.size)
        try {
            val done = ExecutorCompletionService<Answer>(pool)
            endpoints.forEach { endpoint -> done.submit { Answer(endpoint, transport(endpoint, body)) } }
            repeat(endpoints.size) {
                val future = done.take()
                try {
                    return future.get()
                } catch (e: java.util.concurrent.ExecutionException) {
                    val cause = e.cause ?: e
                    if (cause !is IOException) throw cause
                    onError(cause)
                }
            }
            return null
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(100, TimeUnit.MILLISECONDS)
        }
    }
}
