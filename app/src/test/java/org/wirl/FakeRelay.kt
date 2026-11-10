package org.wirl

/**
 * An in-memory relay that follows the server's rules in Wirl-Desktop's relay/src/mailbox.js (docs/sync-protocol.md
 * §5): the first write sets the mailbox's token hash; another token gets 401; a closed mailbox answers 410; a PUT
 * whose baseVersion isn't the current version gets 409 with the current value; a missing mailbox accepts any base.
 */
class FakeRelay : RelayTransport {
    private data class State(
        val tokenHash: String,
        val version: Long,
        val nonce: String = "",
        val ciphertext: String = "",
        val closed: Boolean = false,
    )

    private val boxes = HashMap<String, State>()
    var gets = 0
        private set
    var puts = 0
        private set

    /** Runs once before the next PUT is applied, to simulate another device writing first. Re-arm it to repeat. */
    var beforePut: (() -> Unit)? = null

    /** Runs before every PUT is applied. */
    var beforeEveryPut: (() -> Unit)? = null

    /** Thrown by the next call, for network failures. */
    var failNext: RelayError? = null

    override fun get(keys: SyncCrypto.GroupKeys): Remote? {
        gets++
        failNext?.let { failNext = null; throw it }
        val state = check(keys)
        return state?.let { Remote(it.version, it.nonce, it.ciphertext) }
    }

    override fun put(keys: SyncCrypto.GroupKeys, baseVersion: Long, sealed: SyncCrypto.Sealed): PutResult {
        failNext?.let { failNext = null; throw it }
        beforePut?.let { beforePut = null; it() }
        beforeEveryPut?.invoke()
        puts++
        val state = check(keys)
        require(sealed.nonce.length == 16) { "the relay answers 400 to a nonce that isn't 16 base64 characters" }
        if (state != null && baseVersion != state.version) {
            return PutResult.Conflict(Remote(state.version, state.nonce, state.ciphertext))
        }
        val version = (state?.version ?: 0L) + 1
        boxes[keys.mailbox] = State(SyncCrypto.tokenHash(keys.token), version, sealed.nonce, sealed.ciphertext)
        return PutResult.Stored(version)
    }

    /** DELETE: closes the mailbox. Later requests with that token get 410. */
    fun close(keys: SyncCrypto.GroupKeys) {
        val state = boxes[keys.mailbox]
        boxes[keys.mailbox] = State(SyncCrypto.tokenHash(keys.token), state?.version ?: 0L, closed = true)
    }

    /** The mailbox's current content, decrypted. */
    fun read(keys: SyncCrypto.GroupKeys): SyncProtocol.Payload? =
        boxes[keys.mailbox]?.takeIf { !it.closed }?.let { SyncProtocol.open(keys, SyncCrypto.Sealed(it.nonce, it.ciphertext)) }

    fun version(keys: SyncCrypto.GroupKeys): Long = boxes[keys.mailbox]?.version ?: 0L

    private fun check(keys: SyncCrypto.GroupKeys): State? {
        val state = boxes[keys.mailbox] ?: return null
        if (state.tokenHash != SyncCrypto.tokenHash(keys.token)) throw RelayError.Unauthorized()
        if (state.closed) throw RelayError.Closed()
        return state
    }
}
