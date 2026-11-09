package org.wirl

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** One of the phone's own IPv4 addresses and its prefix length, e.g. 192.168.0.103/24. */
data class PhoneAddress(val address: Inet4Address, val prefixLength: Int)

/** Why a sync didn't work, as the phone explains it. Stored per computer as [PairedComputer.lastError]. */
enum class SyncProblem(val id: String) {
    /** None of the computer's addresses are on the phone's Wi-Fi subnet. */
    DIFFERENT_NETWORK("different_network"),

    /** Same network, but nothing answered: Wirl isn't running, or Windows Firewall blocks it. */
    NOT_ANSWERING("not_answering"),

    /** The computer answered, but the exchange failed (an HTTP error, or a reply that didn't decrypt). */
    FAILED("failed"),
    ;

    companion object {
        fun fromId(id: String): SyncProblem? = entries.firstOrNull { it.id == id }
    }
}

/** Pure network checks for sync, so they can be tested without a phone. IPv4 only. */
object SyncNetwork {
    /** True if [a] and [b] are in the same subnet of [prefixLength] bits. */
    fun sameSubnet(a: Inet4Address, b: Inet4Address, prefixLength: Int): Boolean {
        val bits = prefixLength.coerceIn(0, 32)
        if (bits == 0) return true
        val mask = -1 shl (32 - bits)
        return (toInt(a) and mask) == (toInt(b) and mask)
    }

    /** True if any of the computer's IPv4 addresses is on the same subnet as any of the phone's addresses. */
    fun sharesSubnet(phone: List<PhoneAddress>, computer: List<InetAddress>): Boolean =
        phone.any { own -> computer.filterIsInstance<Inet4Address>().any { sameSubnet(own.address, it, own.prefixLength) } }

    /**
     * Chooses the message for a sync that reached nothing. If the phone has IPv4 addresses and the computer's known
     * addresses are all on other subnets, the two are on different networks. Otherwise the computer probably isn't
     * running Wirl, or Windows Firewall is blocking it. With no computer IPv4 address to compare, it can't tell, so
     * it says the computer isn't answering.
     */
    fun unreachableProblem(phone: List<PhoneAddress>, computer: List<InetAddress>): SyncProblem {
        val computerV4 = computer.filterIsInstance<Inet4Address>()
        if (computerV4.isEmpty()) return SyncProblem.NOT_ANSWERING
        if (phone.isEmpty()) return SyncProblem.DIFFERENT_NETWORK
        return if (sharesSubnet(phone, computerV4)) SyncProblem.NOT_ANSWERING else SyncProblem.DIFFERENT_NETWORK
    }

    /** The computer's addresses to compare against: the last one that worked and the QR code's hosts. */
    fun computerAddresses(computer: PairedComputer): List<InetAddress> =
        (listOf(computer.lastHost) + computer.hosts).mapNotNull { LocalHttp.literal(it) }.distinct()

    /** The phone's private IPv4 addresses on interfaces that are up (Wi-Fi, hotspot, Ethernet), with their prefixes. */
    fun phoneAddresses(): List<PhoneAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.interfaceAddresses.orEmpty() }
            .mapNotNull { ia ->
                val address = ia.address as? Inet4Address ?: return@mapNotNull null
                if (!LocalHttp.isPrivate(address) || address.isLoopbackAddress) return@mapNotNull null
                PhoneAddress(address, ia.networkPrefixLength.toInt())
            }
    }.getOrDefault(emptyList())

    private fun toInt(a: Inet4Address): Int =
        a.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) }
}
