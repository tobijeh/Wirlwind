package org.wirl

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.InetAddress

/**
 * The phone side of a sync against a fake computer that follows docs/sync-protocol.md the way the desktop does:
 * decrypt with the pairing secret (401 if it doesn't), merge per id, purge, encrypt the result back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncExchangeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val phoneId = "0b7c6f7e-1d2a-4b5c-9d8e-7f6a5b4c3d2e"
    private val deviceId = "0c1a3a0e-8a57-4b0e-9d63-0a2b1d2e3f40"
    private val secret = SyncCrypto.newSecret()
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private lateinit var server: MockWebServer

    /** The fake computer's dictionary. */
    private var computerEntries = listOf<DictionaryEntry>()
    private var lastPhoneName = ""

    private val savedFactory = Prefs.secureFactory

    @Before fun start() {
        Prefs.secureFactory = { app -> app.getSharedPreferences("test_secure", Context.MODE_PRIVATE) }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method != "POST" || request.path != SyncProtocol.PATH) return MockResponse().setResponseCode(404)
                val body = JSONObject(request.body.readUtf8())
                if (body.getInt("v") != 1) return MockResponse().setResponseCode(400)
                val id = body.getString("phoneId")
                lastPhoneName = body.getString("phoneName")
                val key = SyncCrypto.deriveKey(secret)
                val plain = try {
                    SyncCrypto.decrypt(key, id, body.getString("nonce"), body.getString("ciphertext"))
                } catch (e: SyncCrypto.AuthenticationException) {
                    return MockResponse().setResponseCode(401)
                }
                val incoming = DictionaryJson.fromJsonArray(JSONObject(String(plain)).getJSONArray("entries"))
                computerEntries = DictionarySync.purge(DictionarySync.merge(computerEntries, incoming), System.currentTimeMillis())
                val out = JSONObject().put("entries", DictionaryJson.toJsonArray(computerEntries)).toString()
                val sealed = SyncCrypto.encrypt(key, id, out.toByteArray())
                return MockResponse().setResponseCode(200)
                    .setBody(JSONObject().put("nonce", sealed.nonce).put("ciphertext", sealed.ciphertext).toString())
            }
        }
        server.start(loopback, 0)
    }

    @After fun stop() {
        server.shutdown()
        Prefs(context).pairedComputers = emptyList()
        PairedComputers.load(context)
        Prefs.secureFactory = savedFactory
    }

    private fun computer(secretToUse: String = secret, hosts: List<String> = listOf("127.0.0.1")) =
        PairedComputer(deviceId, "DESKTOP-ABC", secretToUse, server.port, hosts)

    private fun entry(id: String, term: String, at: Long, deleted: Boolean = false) =
        DictionaryEntry(term, emptyList(), "", id, at, deleted)

    @Test fun bothSidesEndWithTheSameDictionary() {
        val now = System.currentTimeMillis()
        computerEntries = listOf(entry("00000000-0000-4000-8000-000000000001", "Zoe", now))
        val phone = listOf(entry("00000000-0000-4000-8000-000000000002", "Sela", now))

        val result = SyncClient({ emptyList() }).sync(computer(), phoneId, "Pixel 9", phone)

        assertTrue(result is SyncResult.Synced)
        val merged = (result as SyncResult.Synced).entries
        assertEquals(listOf("Zoe", "Sela"), merged.map { it.term })
        assertEquals(computerEntries, merged)
        assertEquals("Pixel 9", lastPhoneName)
    }

    @Test fun tombstonesTravelInBothDirections() {
        val now = System.currentTimeMillis()
        val id = "00000000-0000-4000-8000-000000000003"
        computerEntries = listOf(entry(id, "Sela", now - 1000))
        val result = SyncClient({ emptyList() }).sync(computer(), phoneId, "Pixel", listOf(entry(id, "Sela", now, deleted = true)))
        assertTrue((result as SyncResult.Synced).entries.single().deleted)
        assertTrue(computerEntries.single().deleted)
    }

    @Test fun wrongSecretGets401AndAsksToPairAgain() {
        val result = SyncClient({ emptyList() }).sync(computer(SyncCrypto.newSecret()), phoneId, "Pixel", emptyList())
        assertEquals(SyncResult.NeedsPairing, result)
    }

    @Test fun knownAddressesAreTriedBeforeNsdAndNsdDoesNotRunWhenOneAnswers() {
        var discovered = false
        val client = SyncClient(discover = {
            discovered = true
            emptyList()
        })
        val result = client.sync(computer(), phoneId, "Pixel", emptyList())
        assertTrue(result is SyncResult.Synced)
        assertFalse("NSD only runs when the known addresses fail", discovered)
    }

    @Test fun lastWorkingAddressComesFirstThenQrHosts() {
        val c = computer(hosts = listOf("192.168.1.23", "10.0.0.2")).copy(lastHost = "192.168.1.50", lastPort = 50000)
        val order = SyncClient({ emptyList() }).knownEndpoints(c).map { "${it.address.hostAddress}:${it.port}" }
        assertEquals(listOf("192.168.1.50:50000", "192.168.1.23:${server.port}", "10.0.0.2:${server.port}"), order)
    }

    @Test fun nsdRunsOnlyAfterEveryKnownAddressFailsAndItsAddressIsUsed() {
        val tried = java.util.Collections.synchronizedList(mutableListOf<String>())
        var discoveredAfter = emptyList<String>()
        val client = SyncClient(
            discover = {
                discoveredAfter = tried.toList()
                listOf(SyncEndpoint(loopback, server.port))
            },
            transport = { ep, body ->
                tried += ep.address.hostAddress!!
                if (ep.address.hostAddress != "127.0.0.1") throw IOException("unreachable")
                LocalHttp.postJson(ep.address, ep.port, SyncProtocol.PATH, body)
            },
        )
        val c = computer(hosts = listOf("192.168.1.23")).copy(lastHost = "192.168.1.50")
        val result = client.sync(c, phoneId, "Pixel", emptyList())
        assertTrue(result is SyncResult.Synced)
        assertEquals("127.0.0.1", (result as SyncResult.Synced).endpoint.address.hostAddress)
        assertEquals("both known addresses were tried before NSD", setOf("192.168.1.50", "192.168.1.23"), discoveredAfter.toSet())
    }

    @Test fun anUnreachableFirstAddressTimesOutQuicklyAndTheNextIsUsed() {
        // 10.255.255.1 is a private address nothing answers on: the connect either fails at once or times out in 2 s.
        val c = computer(hosts = listOf("127.0.0.1")).copy(lastHost = "10.255.255.1", lastPort = server.port)
        val started = System.currentTimeMillis()
        val result = SyncClient({ emptyList() }).sync(c, phoneId, "Pixel", emptyList())
        val took = System.currentTimeMillis() - started
        assertTrue(result is SyncResult.Synced)
        assertEquals("127.0.0.1", (result as SyncResult.Synced).endpoint.address.hostAddress)
        assertTrue("took $took ms", took < 4_000)
    }

    @Test fun aSilentAddressGivesUpWithinTheConnectTimeout() {
        val started = System.currentTimeMillis()
        val error = runCatching {
            LocalHttp.postJson(InetAddress.getByName("10.255.255.1"), 47913, SyncProtocol.PATH, "{}")
        }.exceptionOrNull()
        val took = System.currentTimeMillis() - started
        assertTrue("an unreachable address throws IOException, got $error", error is IOException)
        assertTrue("took $took ms, the connect timeout is ${LocalHttp.CONNECT_TIMEOUT_MS} ms", took < LocalHttp.CONNECT_TIMEOUT_MS + 1_500)
    }

    @Test fun aForegroundSyncRecordsTheTimeAndTheStatusSaysJustNow() {
        val prefs = Prefs(context)
        prefs.pairedComputers = listOf(computer().copy(lastError = SyncProblem.NOT_ANSWERING.id))
        PairedComputers.load(context)

        assertTrue(DictionarySyncEngine.syncAll(context, SyncClient({ emptyList() }), { emptyList() }))

        val saved = Prefs(context).pairedComputers.single()
        assertTrue(saved.lastSyncAt > 0)
        assertEquals("a sync that works clears the old error", "", saved.lastError)
        assertEquals("127.0.0.1", saved.lastHost)
        assertFalse(DictionarySyncEngine.running.value)
        val state = SyncStatus.of(PairedComputers.list.value.single(), DictionarySyncEngine.running.value)
        assertEquals(
            "Synced with DESKTOP-ABC just now",
            SyncStatusText.line(context, saved, state, System.currentTimeMillis()),
        )
    }

    private fun failingSync(phone: List<PhoneAddress>): PairedComputer {
        Prefs(context).pairedComputers = listOf(computer(hosts = listOf("192.168.103.11")))
        PairedComputers.load(context)
        val client = SyncClient({ emptyList() }, { _, _ -> throw IOException("connect timed out") })
        assertFalse(DictionarySyncEngine.syncAll(context, client, { phone }))
        return Prefs(context).pairedComputers.single()
    }

    @Test fun differentNetworksAreExplained() {
        val saved = failingSync(listOf(PhoneAddress(InetAddress.getByName("192.168.0.103") as java.net.Inet4Address, 24)))
        assertEquals(SyncProblem.DIFFERENT_NETWORK, saved.problem)
        assertEquals(
            "Can't reach DESKTOP-ABC. Your phone and computer seem to be on different Wi-Fi networks. Connect both to the same Wi-Fi.",
            SyncStatusText.line(context, saved, SyncStatus.of(saved, false), System.currentTimeMillis()),
        )
        assertTrue("details still go to Diagnostics", Prefs(context).diagnostics.first().engines.startsWith("SYNC"))
    }

    @Test fun sameNetworkButNoAnswerPointsAtWirlAndTheFirewall() {
        val saved = failingSync(listOf(PhoneAddress(InetAddress.getByName("192.168.103.40") as java.net.Inet4Address, 24)))
        assertEquals(SyncProblem.NOT_ANSWERING, saved.problem)
        assertEquals(
            "Can't reach DESKTOP-ABC. Make sure Wirl is running on the computer and allowed through Windows Firewall on Private networks.",
            SyncStatusText.line(context, saved, SyncStatus.of(saved, false), System.currentTimeMillis()),
        )
    }

    @Test fun a401StillAsksToPairAgain() {
        Prefs(context).pairedComputers = listOf(computer(SyncCrypto.newSecret()))
        PairedComputers.load(context)
        DictionarySyncEngine.syncAll(context, SyncClient({ emptyList() }), { emptyList() })
        val saved = Prefs(context).pairedComputers.single()
        assertTrue(saved.needsPairing)
        assertEquals(
            "DESKTOP-ABC no longer accepts this phone",
            SyncStatusText.line(context, saved, SyncStatus.of(saved, false), System.currentTimeMillis()),
        )
    }

    @Test fun unreachableComputerFailsQuietlyForRetry() {
        server.shutdown()
        val result = SyncClient({ emptyList() }).sync(computer(), phoneId, "Pixel", emptyList())
        assertTrue(result is SyncResult.Failed)
        assertTrue((result as SyncResult.Failed).unreachable)
    }

    @Test fun publicAddressesAreNeverContacted() {
        assertFalse(LocalHttp.isPrivateLiteral("8.8.8.8"))
        assertFalse(LocalHttp.isPrivateLiteral("example.com"))
        assertTrue(LocalHttp.isPrivateLiteral("192.168.1.23"))
        assertTrue(LocalHttp.isPrivateLiteral("10.0.0.2"))
        assertTrue(LocalHttp.isPrivateLiteral("172.16.5.4"))
        assertFalse(LocalHttp.isPrivateLiteral("172.32.0.1"))
        assertTrue(LocalHttp.isPrivateLiteral("fe80::1"))
        assertTrue(LocalHttp.isPrivateLiteral("fd12:3456::1"))
        assertFalse(LocalHttp.isPrivateLiteral("2001:4860::8888"))
        val tried = mutableListOf<String>()
        val client = SyncClient({ listOf(SyncEndpoint(InetAddress.getByName("8.8.8.8"), 80)) }, { ep, _ ->
            tried += ep.address.hostAddress!!
            throw IOException("no")
        })
        client.sync(computer(hosts = listOf("8.8.8.8")), phoneId, "Pixel", emptyList())
        assertTrue(tried.isEmpty())
    }

    @Test fun chunkedResponsesAreRead() {
        val raw = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n"
        val response = LocalHttp.readResponse(raw.byteInputStream())
        assertEquals(200, response.code)
        assertEquals("hello world", response.body)
    }

    @Test fun pairingQrIsParsed() {
        val qr = SyncProtocol.parsePairingQr(
            """{"v":1,"deviceId":"$deviceId","name":"DESKTOP-ABC","secret":"$secret","port":47913,"hosts":["192.168.1.23","8.8.8.8"]}""",
        )!!
        assertEquals(deviceId, qr.deviceId)
        assertEquals("DESKTOP-ABC", qr.name)
        assertEquals(47913, qr.port)
        assertEquals("public hosts are dropped", listOf("192.168.1.23"), qr.hosts)
    }

    @Test fun invalidQrCodesAreRejected() {
        assertNull(SyncProtocol.parsePairingQr("https://example.com"))
        assertNull(SyncProtocol.parsePairingQr("""{"v":2,"deviceId":"$deviceId","secret":"$secret","port":1,"hosts":[]}"""))
        assertNull(SyncProtocol.parsePairingQr("""{"v":1,"deviceId":"nope","secret":"$secret","port":1,"hosts":[]}"""))
        assertNull(SyncProtocol.parsePairingQr("""{"v":1,"deviceId":"$deviceId","secret":"c2hvcnQ=","port":1,"hosts":[]}"""))
    }

    @Test fun pairedComputersAreStoredAndUnpaired() {
        val prefs = Prefs(context)
        prefs.pairedComputers = emptyList()
        val qr = SyncProtocol.parsePairingQr(
            """{"v":1,"deviceId":"$deviceId","name":"A","secret":"$secret","port":47913,"hosts":["192.168.1.23"]}""",
        )!!
        PairedComputers.pair(context, qr)
        PairedComputers.pair(context, qr.copy(deviceId = "0c1a3a0e-8a57-4b0e-9d63-0a2b1d2e3f41", name = "B"))
        assertEquals(listOf("A", "B"), Prefs(context).pairedComputers.map { it.name })
        PairedComputers.pair(context, qr.copy(name = "A2"))
        assertEquals("re-pairing replaces, not duplicates", listOf("B", "A2"), Prefs(context).pairedComputers.map { it.name })
        PairedComputers.unpair(context, deviceId)
        assertEquals(listOf("B"), Prefs(context).pairedComputers.map { it.name })
    }
}
