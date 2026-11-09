package org.wirl

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A minimal HTTP/1.1 client for the sync request, and the only place Wirl sends cleartext HTTP.
 *
 * The app's network security config forbids cleartext everywhere. Sync talks plain HTTP to the computer on the
 * same Wi-Fi (the payload is AES-GCM encrypted), so it goes through this client, which connects only to IP
 * literals in private ranges: 10/8, 172.16/12, 192.168/16, 169.254/16, loopback, and IPv6 link-local or ULA.
 * Host names are never resolved, so a name can't point it at a public address.
 */
object LocalHttp {
    class Response(val code: Int, val body: String)

    private const val MAX_RESPONSE = 16 * 1024 * 1024
    private val IPV6_LITERAL = Regex("^[0-9a-fA-F:.]*:[0-9a-fA-F:.]*(%[0-9A-Za-z._-]+)?$")

    /** True if [host] is an IP literal in a private range. Names return false without a DNS lookup. */
    fun isPrivateLiteral(host: String): Boolean = literal(host)?.let { isPrivate(it) } == true

    fun isPrivate(address: InetAddress): Boolean = when (address) {
        is Inet4Address -> address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress
        is Inet6Address -> address.isLinkLocalAddress || address.isLoopbackAddress ||
            (address.address[0].toInt() and 0xfe) == 0xfc
        else -> false
    }

    /** Parses an IPv4 or IPv6 literal without any DNS lookup. Null for anything else. */
    fun literal(host: String): InetAddress? {
        val h = host.trim().removePrefix("[").removeSuffix("]")
        if (h.isEmpty()) return null
        val ipv4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").matchEntire(h)
        if (ipv4 != null) {
            val bytes = ipv4.groupValues.drop(1).map { it.toInt() }
            if (bytes.any { it > 255 }) return null
            return InetAddress.getByAddress(bytes.map { it.toByte() }.toByteArray())
        }
        // An IPv6 literal: hex digits, ':' and '.', with an optional %scope. getByName doesn't do DNS for these.
        if (IPV6_LITERAL.matches(h)) return runCatching { InetAddress.getByName(h) }.getOrNull()
        return null
    }

    /** Connecting on the local network takes milliseconds; an address that doesn't answer in 2 s isn't there. */
    const val CONNECT_TIMEOUT_MS = 2_000

    /** Once connected, the computer has 10 s to merge and answer. */
    const val READ_TIMEOUT_MS = 10_000

    /** POSTs [json] and returns the status and body. Throws [IOException] on network errors or a non-private address. */
    fun postJson(
        address: InetAddress,
        port: Int,
        path: String,
        json: String,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
    ): Response {
        if (!isPrivate(address)) throw IOException("refusing non-private address ${address.hostAddress}")
        val body = json.toByteArray(Charsets.UTF_8)
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            val hostHeader = if (address is Inet6Address) "[${address.hostAddress}]:$port" else "${address.hostAddress}:$port"
            val head = "POST $path HTTP/1.1\r\n" +
                "Host: $hostHeader\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
            val out = socket.getOutputStream()
            out.write(head.toByteArray(Charsets.US_ASCII))
            out.write(body)
            out.flush()
            return readResponse(socket.getInputStream())
        }
    }

    internal fun readResponse(input: InputStream): Response {
        val status = readLine(input) ?: throw IOException("empty response")
        val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("bad status line: $status")
        var length = -1
        var chunked = false
        while (true) {
            val line = readLine(input) ?: throw IOException("truncated headers")
            if (line.isEmpty()) break
            val name = line.substringBefore(':').trim().lowercase()
            val value = line.substringAfter(':', "").trim()
            if (name == "content-length") length = value.toIntOrNull() ?: -1
            if (name == "transfer-encoding" && value.lowercase().contains("chunked")) chunked = true
        }
        val bytes = when {
            chunked -> readChunked(input)
            length >= 0 -> readExactly(input, length)
            else -> readToEnd(input)
        }
        return Response(code, String(bytes, Charsets.UTF_8))
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString("US-ASCII")
            if (b == '\n'.code) return buf.toString("US-ASCII").removeSuffix("\r")
            buf.write(b)
            if (buf.size() > 16 * 1024) throw IOException("header line too long")
        }
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        if (n > MAX_RESPONSE) throw IOException("response too large")
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r == -1) throw IOException("truncated body")
            off += r
        }
        return out
    }

    private fun readToEnd(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val r = input.read(buf)
            if (r == -1) break
            out.write(buf, 0, r)
            if (out.size() > MAX_RESPONSE) throw IOException("response too large")
        }
        return out.toByteArray()
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: throw IOException("truncated chunk")
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: throw IOException("bad chunk size")
            if (size == 0) {
                // Trailers, then the blank line.
                while (readLine(input)?.isNotEmpty() == true) Unit
                return out.toByteArray()
            }
            out.write(readExactly(input, size))
            if (out.size() > MAX_RESPONSE) throw IOException("response too large")
            readLine(input)
        }
    }
}
