package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/** The different-network check, the error-message choice, and the status-line choice. Pure: no phone needed. */
class SyncNetworkTest {
    private fun v4(s: String) = InetAddress.getByName(s) as Inet4Address
    private fun phone(s: String, prefix: Int = 24) = PhoneAddress(v4(s), prefix)

    @Test fun sameSlash24IsTheSameSubnet() {
        assertTrue(SyncNetwork.sameSubnet(v4("192.168.0.103"), v4("192.168.0.20"), 24))
    }

    @Test fun differentSlash24IsADifferentSubnet() {
        // What actually happened: the phone was on 192.168.0.x and the laptop on 192.168.103.x.
        assertFalse(SyncNetwork.sameSubnet(v4("192.168.0.103"), v4("192.168.103.11"), 24))
    }

    @Test fun otherPrefixLengths() {
        assertTrue("a /16 spans both", SyncNetwork.sameSubnet(v4("192.168.0.103"), v4("192.168.103.11"), 16))
        assertTrue("a /22 covers .0 to .3", SyncNetwork.sameSubnet(v4("10.0.0.5"), v4("10.0.3.250"), 22))
        assertFalse("but not .4", SyncNetwork.sameSubnet(v4("10.0.0.5"), v4("10.0.4.1"), 22))
        assertTrue(SyncNetwork.sameSubnet(v4("172.16.9.9"), v4("172.31.0.1"), 12))
        assertFalse(SyncNetwork.sameSubnet(v4("10.1.2.3"), v4("10.1.2.4"), 32))
        assertTrue(SyncNetwork.sameSubnet(v4("10.1.2.3"), v4("10.1.2.3"), 32))
        assertTrue("/0 is everything", SyncNetwork.sameSubnet(v4("10.1.2.3"), v4("192.168.1.1"), 0))
    }

    @Test fun anyPhoneAddressSharingASubnetIsEnough() {
        val phone = listOf(phone("10.0.0.7"), phone("192.168.103.50"))
        assertTrue(SyncNetwork.sharesSubnet(phone, listOf(v4("192.168.1.4"), v4("192.168.103.11"))))
        assertFalse(SyncNetwork.sharesSubnet(phone, listOf(v4("192.168.1.4"))))
    }

    @Test fun unreachableOnAnotherSubnetMeansDifferentNetworks() {
        assertEquals(
            SyncProblem.DIFFERENT_NETWORK,
            SyncNetwork.unreachableProblem(listOf(phone("192.168.0.103")), listOf(v4("192.168.103.11"))),
        )
    }

    @Test fun unreachableOnTheSameSubnetMeansTheComputerIsNotAnswering() {
        assertEquals(
            SyncProblem.NOT_ANSWERING,
            SyncNetwork.unreachableProblem(listOf(phone("192.168.103.50")), listOf(v4("192.168.103.11"))),
        )
    }

    @Test fun phoneWithoutWifiCountsAsADifferentNetwork() {
        assertEquals(SyncProblem.DIFFERENT_NETWORK, SyncNetwork.unreachableProblem(emptyList(), listOf(v4("192.168.103.11"))))
    }

    @Test fun withNoComputerAddressToCompareItSaysNotAnswering() {
        assertEquals(SyncProblem.NOT_ANSWERING, SyncNetwork.unreachableProblem(listOf(phone("192.168.0.103")), emptyList()))
        assertEquals(
            "IPv6 addresses aren't compared",
            SyncProblem.NOT_ANSWERING,
            SyncNetwork.unreachableProblem(listOf(phone("192.168.0.103")), listOf(InetAddress.getByName("fe80::1"))),
        )
    }

    @Test fun computerAddressesAreTheLastWorkingOneAndTheQrHosts() {
        val c = computer(hosts = listOf("192.168.103.11", "192.168.103.11", "nonsense"), lastHost = "192.168.1.9")
        assertEquals(listOf(v4("192.168.1.9"), v4("192.168.103.11")), SyncNetwork.computerAddresses(c))
    }

    @Test fun problemIdsRoundTrip() {
        SyncProblem.entries.forEach { assertEquals(it, SyncProblem.fromId(it.id)) }
        assertNull(SyncProblem.fromId(""))
    }

    private fun computer(
        name: String = "DESKTOP-ABC",
        hosts: List<String> = listOf("192.168.1.2"),
        lastHost: String = "",
        lastSyncAt: Long = 0,
        needsPairing: Boolean = false,
        lastError: String = "",
    ) = PairedComputer("0c1a3a0e-8a57-4b0e-9d63-0a2b1d2e3f40", name, "s", 47913, hosts, lastHost, 0, lastSyncAt, needsPairing, lastError)

    @Test fun statusPrefersPairAgainThenSyncingThenProblemThenSyncTime() {
        assertEquals(SyncState.NeedsPairing, SyncStatus.of(computer(needsPairing = true, lastError = "failed"), syncing = true))
        assertEquals(SyncState.Syncing, SyncStatus.of(computer(lastError = "different_network"), syncing = true))
        assertEquals(
            SyncState.Problem(SyncProblem.DIFFERENT_NETWORK),
            SyncStatus.of(computer(lastSyncAt = 5, lastError = "different_network"), syncing = false),
        )
        assertEquals(SyncState.Synced(5), SyncStatus.of(computer(lastSyncAt = 5), syncing = false))
        assertEquals(SyncState.NotSynced, SyncStatus.of(computer(), syncing = false))
    }

    @Test fun onlyPairAgainAndProblemsNeedAttention() {
        assertTrue(SyncState.NeedsPairing.needsAttention)
        assertTrue(SyncState.Problem(SyncProblem.NOT_ANSWERING).needsAttention)
        assertFalse(SyncState.Syncing.needsAttention)
        assertFalse(SyncState.Synced(1).needsAttention)
        assertFalse(SyncState.NotSynced.needsAttention)
    }

    @Test fun homeRowTalksAboutTheComputerThatNeedsAttentionMost() {
        assertNull(SyncStatus.homePick(emptyList(), syncing = false))
        val ok = computer(name = "A", lastSyncAt = 10)
        val newer = computer(name = "B", lastSyncAt = 20)
        val broken = computer(name = "C", lastError = "not_answering")
        val repair = computer(name = "D", needsPairing = true)
        assertEquals("B", SyncStatus.homePick(listOf(ok, newer), false)!!.first.name)
        assertEquals("C", SyncStatus.homePick(listOf(ok, broken, newer), false)!!.first.name)
        assertEquals("D", SyncStatus.homePick(listOf(ok, broken, repair), false)!!.first.name)
    }
}
