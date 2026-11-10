package org.wirl

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Wire formats from docs/sync-protocol.md (v2: encrypted mailbox). */
object SyncProtocol {
    const val VERSION = 2

    /** A device re-uploads after this long even when nothing changed, so others can show "Synced X ago" (§6). */
    const val HEARTBEAT_MS = 15L * 60 * 1000

    /** Devices not seen for this long are dropped from the device list (§6). */
    const val DEVICE_MAX_AGE_MS = 180L * 24 * 60 * 60 * 1000

    const val KIND_PHONE = "phone"
    const val KIND_COMPUTER = "computer"

    /** The pairing QR code's content (§2). [relay] has no trailing slash. */
    data class PairingQr(val name: String, val relay: String, val secret: String)

    sealed class QrResult {
        data class Valid(val qr: PairingQr) : QrResult()

        /** A v1 (Wi-Fi) code: the computer needs updating. */
        object OldVersion : QrResult()

        object Invalid : QrResult()
    }

    /** Parses a scanned code. Only v2 codes with an https relay and a 32-byte secret are accepted. */
    fun parsePairingQr(text: String): QrResult {
        val o = runCatching { JSONObject(text.trim()) }.getOrNull() ?: return QrResult.Invalid
        val v = o.optInt("v", -1)
        if (v == 1) return QrResult.OldVersion
        if (v != VERSION) return QrResult.Invalid
        val relay = relayUrl(o.optString("relay")) ?: return QrResult.Invalid
        val secret = o.optString("secret").trim()
        if (SyncCrypto.secretBytes(secret) == null) return QrResult.Invalid
        return QrResult.Valid(PairingQr(o.optString("name").trim().ifEmpty { "Computer" }, relay, secret))
    }

    /** The relay base URL without a trailing slash, or null unless it's an https URL with a host. */
    fun relayUrl(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrEmpty()) return null
        if (uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null) return null
        return trimmed
    }

    /** A device in the sync group (§4). [kind] is "phone" or "computer". */
    data class Device(val id: String, val name: String, val kind: String, val lastSync: Long) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("kind", kind).put("lastSync", lastSync)

        companion object {
            fun fromJson(o: JSONObject): Device? {
                val id = o.optString("id").trim()
                if (id.isEmpty()) return null
                return Device(id, o.optString("name"), o.optString("kind"), o.optLong("lastSync", 0L))
            }

            fun listToJson(devices: List<Device>): JSONArray = JSONArray().also { a -> devices.forEach { a.put(it.toJson()) } }

            fun listFromJson(a: JSONArray?): List<Device> =
                if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::fromJson) }
        }
    }

    /** The decrypted mailbox content: the whole dictionary, tombstones included, and the device list. */
    data class Payload(val entries: List<DictionaryEntry> = emptyList(), val devices: List<Device> = emptyList()) {
        fun toJson(): String = JSONObject()
            .put("entries", DictionaryJson.toJsonArray(entries))
            .put("devices", Device.listToJson(devices))
            .toString()

        companion object {
            /** Unknown fields are ignored. Throws if [json] isn't an object. */
            fun fromJson(json: String): Payload {
                val o = JSONObject(json)
                return Payload(
                    entries = o.optJSONArray("entries")?.let { DictionaryJson.fromJsonArray(it) }.orEmpty(),
                    devices = Device.listFromJson(o.optJSONArray("devices")),
                )
            }
        }
    }

    fun seal(keys: SyncCrypto.GroupKeys, payload: Payload): SyncCrypto.Sealed =
        SyncCrypto.encrypt(keys, payload.toJson().toByteArray(Charsets.UTF_8))

    /** Throws [SyncCrypto.AuthenticationException] if it doesn't decrypt, or a JSON exception if it isn't a payload. */
    fun open(keys: SyncCrypto.GroupKeys, sealed: SyncCrypto.Sealed): Payload =
        Payload.fromJson(String(SyncCrypto.decrypt(keys, sealed), Charsets.UTF_8))

    /** Per id, the larger `lastSync` wins. Devices not seen for 180 days are dropped. Sorted by id. */
    fun mergeDevices(a: List<Device>, b: List<Device>, now: Long): List<Device> {
        val map = LinkedHashMap<String, Device>()
        for (d in a + b) {
            val current = map[d.id]
            if (current == null || d.lastSync > current.lastSync) map[d.id] = d
        }
        return map.values.filter { now - it.lastSync <= DEVICE_MAX_AGE_MS }.sortedBy { it.id }
    }

    /** What to store locally, and what to upload if an upload is needed. */
    data class MergeOutcome(val entries: List<DictionaryEntry>, val upload: Payload?)

    /**
     * Merges this device's dictionary into the mailbox content (§6 steps 3–5). Upload when the merged entries differ
     * from the mailbox's, or this device's record is missing, renamed, a different kind, or 15 minutes old.
     */
    fun mergeWithMailbox(local: List<DictionaryEntry>, remote: Payload?, me: Device, now: Long): MergeOutcome {
        val mailbox = remote ?: Payload()
        val entries = DictionarySync.purge(DictionarySync.merge(local, mailbox.entries), now)
        val mine = mailbox.devices.firstOrNull { it.id == me.id }
        val stale = mine == null || mine.name != me.name || mine.kind != me.kind || now - mine.lastSync >= HEARTBEAT_MS
        val changed = entries != mailbox.entries.sortedBy { it.id }
        val upload = if (changed || stale) {
            Payload(entries, mergeDevices(mailbox.devices, listOf(me.copy(lastSync = now)), now))
        } else {
            null
        }
        return MergeOutcome(entries, upload)
    }
}

/**
 * A computer this phone is paired with: one sync group, one mailbox. Stored in EncryptedSharedPreferences by
 * [PairedComputers]. [mailbox] is derived from [secret] and kept so a re-scanned code can replace this one.
 */
data class PairedComputer(
    val name: String,
    val relay: String,
    val secret: String,
    val mailbox: String,
    /** When the last sync round succeeded, or 0. */
    val lastSyncAt: Long = 0L,
    /** Why the last round failed ([SyncProblem.id]), or empty after a round that worked. */
    val status: String = "",
    /** The group's devices as of the last round, this phone included. */
    val devices: List<SyncProtocol.Device> = emptyList(),
) {
    /** Same relay and mailbox: the same sync group. */
    val key: String get() = "$relay|$mailbox"

    val problem: SyncProblem? get() = SyncProblem.fromId(status)

    val needsPairing: Boolean get() = problem == SyncProblem.NEEDS_PAIRING

    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("relay", relay)
        .put("secret", secret)
        .put("mailbox", mailbox)
        .put("lastSyncAt", lastSyncAt)
        .put("status", status)
        .put("devices", SyncProtocol.Device.listToJson(devices))

    companion object {
        fun fromQr(qr: SyncProtocol.PairingQr) =
            PairedComputer(qr.name, qr.relay, qr.secret, SyncCrypto.derive(qr.secret).mailbox)

        /** Null for anything that isn't a v2 pairing (old Wi-Fi pairings have no relay). */
        fun fromJson(o: JSONObject): PairedComputer? {
            val relay = SyncProtocol.relayUrl(o.optString("relay")) ?: return null
            val secret = o.optString("secret")
            if (SyncCrypto.secretBytes(secret) == null) return null
            return PairedComputer(
                name = o.optString("name").ifEmpty { "Computer" },
                relay = relay,
                secret = secret,
                mailbox = SyncCrypto.derive(secret).mailbox,
                lastSyncAt = o.optLong("lastSyncAt", 0L),
                status = o.optString("status"),
                devices = SyncProtocol.Device.listFromJson(o.optJSONArray("devices")),
            )
        }

        fun encodeList(list: List<PairedComputer>): String = JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }.toString()

        fun decodeList(raw: String?): List<PairedComputer> = runCatching {
            val a = JSONArray(raw ?: "[]")
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::fromJson) }
        }.getOrDefault(emptyList())
    }
}

/** Why a sync round failed, as the phone explains it (§8). Stored per computer as [PairedComputer.status]. */
enum class SyncProblem(val id: String) {
    /** 401, 410, or a value that doesn't decrypt: "Pairing is no longer valid. Pair again." */
    NEEDS_PAIRING("needs_pairing"),

    /** No connection, timeout, DNS failure: "Can't reach the sync service. Will try again." */
    UNREACHABLE("unreachable"),

    /** 5xx, too many conflicts, or unreadable data: "Sync didn't finish. Will try again." */
    FAILED("failed"),
    ;

    companion object {
        fun fromId(id: String): SyncProblem? = entries.firstOrNull { it.id == id }
    }
}
