# Wirl dictionary sync protocol (v1)

Wirl for Windows and Wirl for Android keep the personal dictionary in sync over the local Wi-Fi network. There is no server and no cloud account. **Both apps must follow this document exactly.** The reference implementation is `crates/wirl-core/src/sync.rs` (crypto, merge) and `src-tauri/src/sync_server.rs` (HTTP, mDNS, pairing).

## Roles

- **Computer** (Wirl Desktop) is the **server**.
- **Phone** (Wirl Android) is the **client**. It starts every sync.

## 1. Dictionary entry format

```json
{
  "id": "7d0f6b0e-3b1a-4c43-9a55-5d3c3c0a2f61",
  "term": "Sela",
  "soundsLike": ["salad", "sella"],
  "note": "my daughter's name",
  "updatedAt": 1767225600000,
  "deleted": false
}
```

| Field | Type | Meaning |
|---|---|---|
| `id` | string, UUID | Stable identity of the entry. Never reused. |
| `term` | string | The correct spelling. |
| `soundsLike` | array of strings | Known mishearings. May be empty. |
| `note` | string | Context note. May be empty. |
| `updatedAt` | integer | Milliseconds since the Unix epoch, UTC. Set on every create, edit and delete. |
| `deleted` | boolean | Tombstone flag. |

- **Delete** = set `deleted: true` and update `updatedAt`. The entry stays in the list (a *tombstone*) so the deletion syncs.
- **Purge** tombstones whose `updatedAt` is more than **90 days** old.
- When an entry is edited, `updatedAt` must strictly increase (`max(now, previous + 1)`).

## 2. Pairing

On the computer, *Dictionary › Pair with phone* shows a QR code. Its content is this JSON (UTF-8, no extra whitespace required):

```json
{
  "v": 1,
  "deviceId": "0c1a3a0e-8a57-4b0e-9d63-0a2b1d2e3f40",
  "name": "DESKTOP-ABC",
  "secret": "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
  "port": 47913,
  "hosts": ["192.168.1.23"]
}
```

| Field | Meaning |
|---|---|
| `v` | Protocol version, `1`. |
| `deviceId` | The computer's UUID. Stable across restarts. |
| `name` | The computer's name, for display. |
| `secret` | Standard base64 (with padding) of **32 random bytes**. New for every QR code. |
| `port` | TCP port of the sync server (default 47913; the next free port up to 47932 if busy). |
| `hosts` | The computer's private IPv4 addresses (RFC 1918). |

- A QR code's secret is valid for **15 minutes** or until a phone completes its first sync with it, whichever comes first.
- The first successful sync with a pending secret completes pairing: the computer stores `phoneId → secret` in Windows Credential Manager and the phone's display name in its settings. The phone stores `deviceId → secret` in its own secure storage.
- **Unpair** deletes the stored secret on that side. Later requests from that phone get `401`.
- Scanning a new QR code from an already-paired phone replaces that phone's secret (re-pairing).

## 3. Discovery

- The computer advertises over mDNS / DNS-SD:
  - Service type: `_wirl._tcp` (`_wirl._tcp.local.`)
  - Instance name: the computer's name
  - Port: the sync port
  - TXT record: `id=<deviceId>`
- The phone finds the paired computer by matching `id` to the `deviceId` it stored. If discovery fails, it tries the `hosts` from the QR code (and the last address that worked).
- The computer only answers peers with private addresses (10/8, 172.16/12, 192.168/16, 169.254/16, loopback; IPv6 link-local / ULA). Others get `403`. Windows Firewall should allow Wirl on **Private** networks only.

## 4. Request

The phone sends:

```
POST http://<host>:<port>/wirl/sync
Content-Type: application/json
```

```json
{
  "v": 1,
  "phoneId": "0b7c6f7e-1d2a-4b5c-9d8e-7f6a5b4c3d2e",
  "phoneName": "Pixel 9",
  "nonce": "<base64 of 12 random bytes>",
  "ciphertext": "<base64>"
}
```

- `phoneId` is a UUID the phone creates once and keeps.
- Request plaintext (before encryption):

```json
{ "entries": [ /* the phone's FULL dictionary, including tombstones */ ] }
```

## 5. Encryption

| Item | Value |
|---|---|
| Cipher | AES-256-GCM, 12-byte nonce, 16-byte tag |
| Key | HKDF-SHA256, **IKM = the 32 decoded secret bytes**, **salt = none** (RFC 5869 default: 32 zero bytes), **info = `wirl-sync-v1`** (ASCII), output length 32 bytes |
| Additional authenticated data | the `phoneId` string, UTF-8 bytes |
| `ciphertext` | base64 of `encrypted bytes ‖ 16-byte tag` (the standard output of AES-GCM in Java `Cipher`, Rust `aes-gcm`, Python `cryptography`) |
| `nonce` | base64 of 12 fresh random bytes. **Never reuse** a nonce with the same key. |
| Encoding | Standard base64 alphabet with `=` padding |

**Decryption failure means unauthorized.** If no stored or pending secret decrypts the request, the computer returns `401` with an empty body.

### Test vector

| | |
|---|---|
| secret (base64) | `AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=` (bytes 0x00…0x1f) |
| derived key (hex) | `f2b4a1a6ce55a07aea8ccb22e73d9ad6f36e4e177601c4e0d1be7ad47e6eb66c` |
| phoneId (AAD) | `0b7c6f7e-1d2a-4b5c-9d8e-7f6a5b4c3d2e` |
| nonce (base64) | `AAECAwQFBgcICQoL` (bytes 0x00…0x0b) |
| plaintext | `{"entries":[]}` |
| ciphertext (base64) | `T4GGLLyKJaiy63rNMucjUgABVlyT/DGPB7LUjk1Z` |

This vector is checked by the desktop unit tests (`known_test_vector`) and was cross-checked with Python's `cryptography` package.

## 6. Response

On success the computer returns `200` with:

```json
{ "nonce": "<base64 12 bytes>", "ciphertext": "<base64>" }
```

encrypted exactly the same way (same key, AAD = the same `phoneId`, fresh nonce). Plaintext:

```json
{ "entries": [ /* the computer's FULL dictionary after merging, including tombstones */ ] }
```

The phone replaces its dictionary with this list. **Both sides end with identical dictionaries.**

| Status | Meaning |
|---|---|
| 200 | Synced. Body as above. |
| 400 | Malformed JSON, wrong `v`, or `phoneId` not a UUID. |
| 401 | Decryption failed (wrong or unknown secret, tampered data). Empty body. |
| 403 | Request came from a non-private address. |
| 413 | Body larger than 8 MB. |

## 7. Merge rule

For each `id` present on either side:

1. The version with the **newer `updatedAt` wins**.
2. On a tie, the version with **`deleted: true` wins**.
3. On a full tie (same `updatedAt` and `deleted`), pick the version whose canonical JSON serialization sorts greater. (This only matters if two devices produce different content in the same millisecond. The phone simply adopts the computer's merged list, so both sides still match.)

The computer then purges tombstones older than 90 days. The result is returned sorted by `id`.

## 8. When to sync

- The phone syncs when it joins Wi-Fi, when the dictionary changes (debounced), and when the user opens the dictionary.
- The computer shows "Synced X min ago" per paired phone.

---

## Wirl for Android (the client)

How the Android app implements the phone side. Everything above is the contract; this section only says where it lives and how the phone schedules it.

| Protocol part | Android code |
|---|---|
| Entry format, migration, tombstones, purge, merge (§1, §7) | `DictionarySync` (`migrate`, `reconcile`, `purge`, `merge`, `pickWinner`, `canonicalJson`) |
| Storage | `Prefs.dictionary`: the full list, tombstones included, in the §1 format. Settings version 4 migrates old entries (new UUID, `updatedAt` = now, sounds-like as a list). |
| Pairing QR (§2) | `SyncProtocol.parsePairingQr`, scanned with zxing-android-embedded from *Settings › Computer sync › Pair with a computer* (also *Dictionary › ⋮ › Pair with computer*). Both use `PairingFlow`. |
| Paired computers | `Prefs.pairedComputers` in EncryptedSharedPreferences: `deviceId`, `name`, `secret`, `port`, `hosts`, last working address, last sync time, whether the computer answered 401, and why the last sync failed (phone-only, never sent) |
| Discovery (§3) | `SyncClient`: first the last address that worked and the QR `hosts` with the QR `port`, all at once, using the first that answers. Only if none answers does `NsdDiscovery` run (Android NSD, service type `_wirl._tcp`, TXT `id=<deviceId>`), and then the address it finds is tried. The computer sees the same request either way. |
| Request, response (§4, §6) | `SyncProtocol.buildRequest` / `readResponse`, sent by `SyncClient` |
| Encryption (§5) | `SyncCrypto` (javax.crypto AES/GCM/NoPadding, HKDF-SHA256 by hand). `SyncCryptoTest` checks the test vector above. |
| HTTP | `LocalHttp`: a minimal HTTP/1.1 client over a socket. It connects only to IP literals in private ranges (10/8, 172.16/12, 192.168/16, 169.254/16, loopback, IPv6 link-local and ULA) and never resolves host names. The app's network security config forbids cleartext everywhere else. Timeouts suit a LAN: 2 s to connect, 10 s to read the answer. |

**Applying the response.** The phone merges the returned list into its current list with the §7 rule (rather than overwriting it), then purges old tombstones. If nothing changed on the phone during the request, that is exactly the computer's list; if the user edited something meanwhile, the edit is kept and goes out with the next sync.

**When the phone syncs.** While the user is looking, right away: when the Dictionary or Computer sync screen opens, just after a QR code is scanned, and on **Sync now**. These run in a coroutine on `Dispatchers.IO` tied to the screen (`ForegroundSync`), not through WorkManager, and the screen shows "Syncing…" until the result. In the background, through WorkManager (`SyncScheduler`, unique work so bursts collapse):

- 3 seconds after any dictionary change (debounced);
- when the phone joins Wi-Fi (`ConnectivityManager.registerNetworkCallback` with a PendingIntent to `WifiSyncReceiver`, re-armed when the accessibility service starts, which includes boot);
- every 30 minutes on an unmetered network (periodic work).

Foreground and background syncs both go through `DictionarySyncEngine.syncAll`, which holds one lock, so they never run at the same time. Nothing is scheduled while no computer is paired. Sync is silent: no notifications. A failed background sync is retried with WorkManager's backoff. Sync never blocks dictation or cleanup.

**When a sync fails.** The details go to Diagnostics (`SYNC <computer>` entries), and the computer's status line says what to do:

- No address answered, and none of the computer's addresses (the last that worked and the QR hosts) is on the same IPv4 subnet as any of the phone's addresses (interface address and prefix length, `SyncNetwork`): "Can't reach *name*. Your phone and computer seem to be on different Wi-Fi networks. Connect both to the same Wi-Fi."
- No address answered, but they share a subnet: "Can't reach *name*. Make sure Wirl is running on the computer and allowed through Windows Firewall on Private networks."
- The computer answered with an error, or a reply that didn't decrypt: "Couldn't sync with *name*. Wirl will try again in a moment."

**401.** The computer is marked as needing pairing. It's skipped by later syncs, and its status line shows "*name* no longer accepts this phone" with **Pair again** (scan a new QR code from that computer) and **Unpair**.

**Status lines.** The Computer sync screen (Settings home › Computer sync) shows one card per paired computer with its status, **Sync now**, **Pair again** when needed, and **Unpair**. The Dictionary screen shows the same status lines. They read "Syncing with *name*…", "Synced with *name* 5 min ago", "Paired with *name* · not synced yet", one of the messages above, or the 401 message. The Home row shows the computer that most needs attention, in amber when the user has something to do ("Can't reach *name*", "*name* needs pairing again").

**Permissions.** `CAMERA` (asked by the scanner when it opens). `ACCESS_LOCAL_NETWORK` is declared and requested after pairing on Android versions that define it; older versions don't ask.

**Import and export.** Export writes live entries in the §1 format. Import accepts the §1 format, the old Android format (`sounds_like`), a plain list of `{"term", "soundsLike", "note"}` (soundsLike as a list or a comma-separated string), or `{"entries": [...]}`. Tombstones in a file are skipped. Imported terms are merged by term: an existing term gets the new sounds-like values, not a duplicate entry. A new term keeps the file's `id` when it has one, so importing a computer's export and then syncing doesn't duplicate it.

**Same term added on both sides.** The merge is per `id`, so the same term added separately on the phone and the computer (two ids) ends up as two entries on both sides. Both sides still match.
