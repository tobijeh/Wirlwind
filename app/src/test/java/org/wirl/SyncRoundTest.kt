package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The sync round (docs/sync-protocol.md §6) and the wire formats (§2, §4), against [FakeRelay], which follows the
 * relay's rules. Robolectric only because the payload is org.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncRoundTest {
    private val secret = SyncCrypto.newSecret()
    private val keys = SyncCrypto.derive(secret)
    private val relay = FakeRelay()
    private val t0 = 1_767_225_600_000L
    private val minute = 60_000L

    private val phone = SyncProtocol.Device("0b7c6f7e-1d2a-4b5c-9d8e-7f6a5b4c3d2e", "Pixel 9", SyncProtocol.KIND_PHONE, 0)
    private val computer = SyncProtocol.Device("0c1a3a0e-8a57-4b0e-9d63-0a2b1d2e3f40", "DESKTOP-ABC", SyncProtocol.KIND_COMPUTER, 0)

    private fun id(n: Int) = "00000000-0000-4000-8000-%012d".format(n)

    private fun entry(n: Int, term: String, at: Long, deleted: Boolean = false) =
        DictionaryEntry(term, emptyList(), "", id(n), at, deleted)

    private fun round(local: List<DictionaryEntry>, me: SyncProtocol.Device, now: Long) = SyncRound.run(relay, keys, local, me, now)

    @Test fun twoDevicesConverge() {
        var onPhone = listOf(entry(1, "Sela", t0))
        var onComputer = listOf(entry(2, "Kubernetes", t0), entry(3, "Zoe", t0))

        onPhone = round(onPhone, phone, t0).entries
        onComputer = round(onComputer, computer, t0 + minute).entries
        onPhone = round(onPhone, phone, t0 + 2 * minute).entries

        assertEquals(listOf("Sela", "Kubernetes", "Zoe"), onPhone.map { it.term })
        assertEquals(onComputer, onPhone)
        assertEquals(onPhone, relay.read(keys)!!.entries)
        assertEquals(setOf("Pixel 9", "DESKTOP-ABC"), relay.read(keys)!!.devices.map { it.name }.toSet())
    }

    @Test fun editsAndDeletesWinByNewestUpdatedAt() {
        round(listOf(entry(1, "Sela", t0), entry(2, "Zoe", t0)), computer, t0)
        // The phone renamed one entry and deleted the other, later.
        val result = round(listOf(entry(1, "Selah", t0 + 5), entry(2, "Zoe", t0 + 5, deleted = true)), phone, t0 + minute)
        assertEquals(listOf("Selah", "Zoe"), result.entries.map { it.term })
        assertTrue(result.entries[1].deleted)
        assertEquals(result.entries, relay.read(keys)!!.entries)
    }

    @Test fun aConflictIsMergedWithTheNewValueAndRetried() {
        round(emptyList(), computer, t0)
        // While the phone is uploading, the computer writes a new word first.
        relay.beforePut = { round(listOf(entry(2, "Kubernetes", t0 + 1)), computer, t0 + 2 * minute) }

        val result = round(listOf(entry(1, "Sela", t0 + 1)), phone, t0 + 3 * minute)

        assertTrue(result.uploaded)
        assertEquals(listOf("Sela", "Kubernetes"), result.entries.map { it.term })
        assertEquals("both words are in the mailbox", result.entries, relay.read(keys)!!.entries)
        assertEquals("setup, the phone's PUT (409), the computer's PUT, the retry", 4, relay.puts)
    }

    @Test fun nothingIsUploadedWhenNothingChanged() {
        val first = round(listOf(entry(1, "Sela", t0)), phone, t0)
        assertTrue(first.uploaded)
        val putsBefore = relay.puts
        val second = round(first.entries, phone, t0 + minute)
        assertFalse(second.uploaded)
        assertEquals(putsBefore, relay.puts)
        assertEquals(first.entries, second.entries)
        assertEquals(1L, relay.version(keys))
    }

    @Test fun theDeviceRecordIsRefreshedEvery15Minutes() {
        round(listOf(entry(1, "Sela", t0)), phone, t0)
        assertFalse(round(listOf(entry(1, "Sela", t0)), phone, t0 + 14 * minute).uploaded)
        val heartbeat = round(listOf(entry(1, "Sela", t0)), phone, t0 + 15 * minute)
        assertTrue(heartbeat.uploaded)
        assertEquals(t0 + 15 * minute, relay.read(keys)!!.devices.single { it.id == phone.id }.lastSync)
    }

    @Test fun aRenamedPhoneUploadsItsNewName() {
        round(emptyList(), phone, t0)
        assertTrue(round(emptyList(), phone.copy(name = "Pixel 10"), t0 + minute).uploaded)
        assertEquals("Pixel 10", relay.read(keys)!!.devices.single().name)
    }

    @Test fun aClosedMailboxMeansPairAgain() {
        round(emptyList(), computer, t0)
        relay.close(keys)
        val error = expectError { round(emptyList(), phone, t0 + minute) }
        assertTrue(error is RelayError.Closed)
        assertEquals(SyncProblem.NEEDS_PAIRING, error.problem)
    }

    @Test fun anotherTokenGets401AndAWrongKeyDoesNotDecrypt() {
        round(emptyList(), computer, t0)
        val otherToken = SyncCrypto.GroupKeys(keys.mailbox, SyncCrypto.derive(SyncCrypto.newSecret()).token, keys.key)
        val unauthorized = expectError { SyncRound.run(relay, otherToken, emptyList(), phone, t0) }
        assertTrue(unauthorized is RelayError.Unauthorized)
        assertEquals(SyncProblem.NEEDS_PAIRING, unauthorized.problem)

        val otherKey = SyncCrypto.GroupKeys(keys.mailbox, keys.token, SyncCrypto.derive(SyncCrypto.newSecret()).key)
        val decrypt = expectError { SyncRound.run(relay, otherKey, emptyList(), phone, t0) }
        assertTrue(decrypt is RelayError.Decrypt)
        assertEquals(SyncProblem.NEEDS_PAIRING, decrypt.problem)
    }

    @Test fun networkErrorsMeanCantReach() {
        relay.failNext = RelayError.Network(java.io.IOException("timeout"))
        val error = expectError { round(emptyList(), phone, t0) }
        assertEquals(SyncProblem.UNREACHABLE, error.problem)
    }

    @Test fun itGivesUpAfterFiveConflicts() {
        round(emptyList(), computer, t0)
        var n = 0
        relay.beforeEveryPut = { if (n++ % 2 == 0) round(listOf(entry(100 + n, "w$n", t0 + n)), computer, t0 + n) }
        val error = expectError { round(listOf(entry(1, "Sela", t0)), phone, t0 + minute) }
        assertTrue(error is RelayError.TooManyConflicts)
        assertEquals(SyncProblem.FAILED, error.problem)
    }

    @Test fun oldTombstonesAndOldDevicesAreDropped() {
        val day = 24 * 60 * minute
        val now = t0 + 200 * day
        val payload = SyncProtocol.Payload(
            entries = listOf(entry(1, "old", now - 91 * day, deleted = true), entry(2, "recent", now - 89 * day, deleted = true)),
            devices = listOf(computer.copy(lastSync = now - 181 * day), computer.copy(id = "x", lastSync = now - day)),
        )
        val outcome = SyncProtocol.mergeWithMailbox(emptyList(), payload, phone, now)
        assertEquals(listOf("recent"), outcome.entries.map { it.term })
        assertEquals(setOf("x", phone.id), outcome.upload!!.devices.map { it.id }.toSet())
    }

    @Test fun deviceListsMergeByNewestLastSync() {
        val a = listOf(phone.copy(lastSync = 5), computer.copy(lastSync = 9))
        val b = listOf(phone.copy(name = "renamed", lastSync = 7), computer.copy(lastSync = 3))
        val merged = SyncProtocol.mergeDevices(a, b, 10)
        assertEquals(listOf(phone.id, computer.id), merged.map { it.id })
        assertEquals("renamed", merged[0].name)
        assertEquals(9L, merged[1].lastSync)
    }

    @Test fun payloadRoundTripsAndIgnoresUnknownFields() {
        val payload = SyncProtocol.Payload(listOf(entry(1, "Sela", t0).copy(soundsLike = listOf("salad"), note = "n")), listOf(phone))
        assertEquals(payload, SyncProtocol.open(keys, SyncProtocol.seal(keys, payload)))
        val json = """{"entries":[{"id":"${id(1)}","term":"Sela","soundsLike":[],"note":"","updatedAt":1,"deleted":false,"color":"red"}],
            "devices":[{"id":"d","name":"PC","kind":"computer","lastSync":2,"os":"win"}],"future":true}"""
        val parsed = SyncProtocol.Payload.fromJson(json)
        assertEquals("Sela", parsed.entries.single().term)
        assertEquals(SyncProtocol.Device("d", "PC", "computer", 2), parsed.devices.single())
    }

    @Test fun aV2CodeIsAccepted() {
        val result = SyncProtocol.parsePairingQr(
            """{"v":2,"name":"DESKTOP-ABC","relay":"https://wirl-relay.wirl.workers.dev/","secret":"$secret"}""",
        )
        val qr = (result as SyncProtocol.QrResult.Valid).qr
        assertEquals("DESKTOP-ABC", qr.name)
        assertEquals("trailing slash trimmed", "https://wirl-relay.wirl.workers.dev", qr.relay)
        assertEquals(secret, qr.secret)
        assertEquals(keys.mailbox, PairedComputer.fromQr(qr).mailbox)
    }

    @Test fun aV1CodeAsksToUpdateTheComputer() {
        val v1 = """{"v":1,"deviceId":"0c1a3a0e-8a57-4b0e-9d63-0a2b1d2e3f40","name":"A","secret":"$secret","port":47913,"hosts":["192.168.1.2"]}"""
        assertEquals(SyncProtocol.QrResult.OldVersion, SyncProtocol.parsePairingQr(v1))
    }

    @Test fun badCodesAreRejected() {
        fun parse(s: String) = SyncProtocol.parsePairingQr(s)
        assertEquals(SyncProtocol.QrResult.Invalid, parse("https://example.com"))
        assertEquals("http relays aren't allowed", SyncProtocol.QrResult.Invalid,
            parse("""{"v":2,"name":"A","relay":"http://wirl-relay.wirl.workers.dev","secret":"$secret"}"""))
        assertEquals(SyncProtocol.QrResult.Invalid, parse("""{"v":2,"name":"A","relay":"ftp://x","secret":"$secret"}"""))
        assertEquals(SyncProtocol.QrResult.Invalid, parse("""{"v":2,"name":"A","relay":"https://x.dev","secret":"c2hvcnQ="}"""))
        assertEquals(SyncProtocol.QrResult.Invalid, parse("""{"v":3,"name":"A","relay":"https://x.dev","secret":"$secret"}"""))
        assertNull(SyncProtocol.relayUrl("https://user@x.dev"))
    }

    private fun expectError(block: () -> Unit): RelayError {
        try {
            block()
        } catch (e: RelayError) {
            return e
        }
        fail("expected a RelayError")
        throw AssertionError()
    }
}
