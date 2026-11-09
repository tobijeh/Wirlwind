package org.wirl

import org.json.JSONArray
import org.json.JSONObject

/** Wire formats from docs/sync-protocol.md. */
object SyncProtocol {
    const val VERSION = 1
    const val SERVICE_TYPE = "_wirl._tcp"
    const val PATH = "/wirl/sync"
    const val DEFAULT_PORT = 47913

    /** The pairing QR code's content. */
    data class PairingQr(
        val deviceId: String,
        val name: String,
        val secret: String,
        val port: Int,
        val hosts: List<String>,
    )

    /** Parses a scanned QR code. Null if it isn't a valid Wirl pairing code. */
    fun parsePairingQr(text: String): PairingQr? = runCatching {
        val o = JSONObject(text.trim())
        if (o.optInt("v", -1) != VERSION) return null
        val deviceId = o.getString("deviceId").trim()
        if (!DictionarySync.isUuid(deviceId)) return null
        val secret = o.getString("secret").trim()
        if (SyncCrypto.unb64(secret).size != 32) return null
        val port = o.optInt("port", DEFAULT_PORT)
        if (port !in 1..65535) return null
        val hostsJson = o.optJSONArray("hosts") ?: JSONArray()
        val hosts = (0 until hostsJson.length()).map { hostsJson.optString(it).trim() }
            .filter { LocalHttp.isPrivateLiteral(it) }
        PairingQr(deviceId, o.optString("name").trim().ifEmpty { "Computer" }, secret, port, hosts)
    }.getOrNull()

    /** The POST body: `{"v", "phoneId", "phoneName", "nonce", "ciphertext"}` with plaintext `{"entries": [...]}`. */
    fun buildRequest(secret: String, phoneId: String, phoneName: String, entries: List<DictionaryEntry>): String {
        val plain = JSONObject().put("entries", DictionaryJson.toJsonArray(entries)).toString()
        val sealed = SyncCrypto.encrypt(SyncCrypto.deriveKey(secret), phoneId, plain.toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("v", VERSION)
            .put("phoneId", phoneId)
            .put("phoneName", phoneName)
            .put("nonce", sealed.nonce)
            .put("ciphertext", sealed.ciphertext)
            .toString()
    }

    /** Decrypts a 200 response into the computer's merged dictionary. Throws if it doesn't decrypt or parse. */
    fun readResponse(secret: String, phoneId: String, body: String): List<DictionaryEntry> {
        val o = JSONObject(body)
        val plain = SyncCrypto.decrypt(SyncCrypto.deriveKey(secret), phoneId, o.getString("nonce"), o.getString("ciphertext"))
        return DictionaryJson.fromJsonArray(JSONObject(String(plain, Charsets.UTF_8)).getJSONArray("entries"))
    }
}

/** A computer this phone is paired with. Stored in EncryptedSharedPreferences by [PairedComputers]. */
data class PairedComputer(
    val deviceId: String,
    val name: String,
    val secret: String,
    val port: Int,
    val hosts: List<String>,
    /** The address that last worked, tried before the QR hosts. */
    val lastHost: String = "",
    val lastPort: Int = 0,
    /** When the last sync succeeded, or 0. */
    val lastSyncAt: Long = 0L,
    /** The computer answered 401: the pairing is no longer valid. */
    val needsPairing: Boolean = false,
    /** Why the last sync failed ([SyncProblem.id]), or empty after a sync that worked. Kept on the phone only. */
    val lastError: String = "",
) {
    val problem: SyncProblem? get() = SyncProblem.fromId(lastError)

    fun toJson(): JSONObject = JSONObject()
        .put("deviceId", deviceId)
        .put("name", name)
        .put("secret", secret)
        .put("port", port)
        .put("hosts", JSONArray(hosts))
        .put("lastHost", lastHost)
        .put("lastPort", lastPort)
        .put("lastSyncAt", lastSyncAt)
        .put("needsPairing", needsPairing)
        .put("lastError", lastError)

    companion object {
        fun fromQr(qr: SyncProtocol.PairingQr) = PairedComputer(qr.deviceId, qr.name, qr.secret, qr.port, qr.hosts)

        fun fromJson(o: JSONObject): PairedComputer {
            val hosts = o.optJSONArray("hosts") ?: JSONArray()
            return PairedComputer(
                deviceId = o.getString("deviceId"),
                name = o.optString("name"),
                secret = o.getString("secret"),
                port = o.optInt("port", SyncProtocol.DEFAULT_PORT),
                hosts = (0 until hosts.length()).map { hosts.optString(it) },
                lastHost = o.optString("lastHost"),
                lastPort = o.optInt("lastPort", 0),
                lastSyncAt = o.optLong("lastSyncAt", 0L),
                needsPairing = o.optBoolean("needsPairing", false),
                lastError = o.optString("lastError"),
            )
        }

        fun encodeList(list: List<PairedComputer>): String = JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }.toString()

        fun decodeList(raw: String?): List<PairedComputer> = runCatching {
            val a = JSONArray(raw ?: "[]")
            (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }
}
