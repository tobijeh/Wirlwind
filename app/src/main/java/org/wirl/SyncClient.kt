package org.wirl

import java.io.IOException
import java.net.InetAddress

/** One address to try for a computer. */
data class SyncEndpoint(val address: InetAddress, val port: Int)

sealed class SyncResult {
    /** The computer's merged dictionary, and the address that worked. */
    class Synced(val entries: List<DictionaryEntry>, val endpoint: SyncEndpoint) : SyncResult()

    /** 401: the computer no longer accepts this pairing. The user has to scan a new code. */
    object NeedsPairing : SyncResult()

    class Failed(val reason: String, val code: Int? = null, val error: Throwable? = null) : SyncResult()
}

/**
 * One sync exchange with one computer (docs/sync-protocol.md §4–§6). [discover] returns addresses found with
 * NSD; the last address that worked and the QR code's hosts are tried after them.
 */
class SyncClient(
    private val discover: (PairedComputer) -> List<SyncEndpoint>,
    private val transport: (SyncEndpoint, String) -> LocalHttp.Response = { ep, body ->
        LocalHttp.postJson(ep.address, ep.port, SyncProtocol.PATH, body)
    },
) {
    fun endpoints(computer: PairedComputer): List<SyncEndpoint> {
        val found = runCatching { discover(computer) }.getOrDefault(emptyList())
        val last = LocalHttp.literal(computer.lastHost)?.let { SyncEndpoint(it, computer.lastPort.takeIf { p -> p > 0 } ?: computer.port) }
        val fromQr = computer.hosts.mapNotNull { LocalHttp.literal(it) }.map { SyncEndpoint(it, computer.port) }
        return (found + listOfNotNull(last) + fromQr).filter { LocalHttp.isPrivate(it.address) }.distinct()
    }

    fun sync(computer: PairedComputer, phoneId: String, phoneName: String, entries: List<DictionaryEntry>): SyncResult {
        val candidates = endpoints(computer)
        if (candidates.isEmpty()) return SyncResult.Failed("no address for ${computer.name}")
        val body = SyncProtocol.buildRequest(computer.secret, phoneId, phoneName, entries)
        var lastError: Throwable? = null
        for (endpoint in candidates) {
            val response = try {
                transport(endpoint, body)
            } catch (e: IOException) {
                lastError = e
                continue
            }
            return when (response.code) {
                200 -> try {
                    SyncResult.Synced(SyncProtocol.readResponse(computer.secret, phoneId, response.body), endpoint)
                } catch (e: Exception) {
                    SyncResult.Failed("unreadable response from ${endpoint.address.hostAddress}", 200, e)
                }
                401 -> SyncResult.NeedsPairing
                else -> SyncResult.Failed("HTTP ${response.code} from ${endpoint.address.hostAddress}", response.code)
            }
        }
        val tried = candidates.joinToString { "${it.address.hostAddress}:${it.port}" }
        return SyncResult.Failed("couldn't reach ${computer.name} ($tried): ${lastError?.message ?: "no answer"}", null, lastError)
    }
}
