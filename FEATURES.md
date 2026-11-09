# Features

Every Wirl feature, with its status in Wirl for Android (this repository) and Wirl for Windows (`tobijeh/Wirl-Desktop`).

✅ done and covered by unit tests · 🟢 done, needs a check on a real device · ➖ not applicable on that platform · ⬜ not available

The Desktop column is taken from `FEATURES.md` in Wirl-Desktop. Update both files when a shared feature changes.

| Area | Feature | Android | Desktop |
|---|---|---|---|
| **Dictation** | Local Whisper transcription (whisper.cpp) | 🟢 on-phone, arm64 | 🟢 sidecar process |
| | Google speech recognizer as fallback engine | 🟢 | ➖ |
| | GPU acceleration (Vulkan) with CPU fallback | ➖ CPU only | 🟢 |
| | Crash isolation for Whisper, restart, reason logged | ➖ in-process | 🟢 |
| | Background chunking at natural pauses (5–10 s) | ✅ | ✅ |
| | Only the final chunk is left to transcribe at stop | ✅ | 🟢 |
| | `audio_ctx` sized per chunk | ✅ (256–1500) | ✅ (min 384) |
| | Dictionary terms as the Whisper initial prompt | ✅ | ✅ |
| | Sound-tag and invented-phrase filtering | ✅ | ✅ |
| | Silent discard (< 1 s, or no words) | ✅ | ✅ |
| | Insert at the cursor via clipboard paste, clipboard restored | 🟢 | ✅ logic / 🟢 Windows |
| | Fallback when the field can't take text | 🟢 copies to clipboard | ✅ logic / 🟢 "Copied, press Ctrl+V" |
| | Pause other media during dictation | 🟢 always on | 🟢 |
| | Start and stop tones | 🟢 | 🟢 |
| | Slide to cancel while recording | ✅ logic / 🟢 gesture | ➖ (Esc cancels) |
| **Models** | Fast (base.en q5_1), Accurate (small.en q5_1), Multilingual (base q5_1) | ✅ | ✅ |
| | Download from Hugging Face with resume and SHA-256 check | ✅ | ✅ |
| | Downloads wait for Wi-Fi unless mobile data is allowed | ✅ | ➖ |
| | Model kept loaded, freed after 10 min idle | ✅ | 🟢 kept loaded |
| | Language picker | 🟢 | 🟢 |
| **AI cleanup** | Claude Haiku 5.5, built-in prompt | ✅ adaptive thinking, low effort | ✅ thinking off |
| | Personal style notes | ✅ | ✅ |
| | Only plausibly matching dictionary entries sent | ✅ | ✅ |
| | Keep-alive connection, warmed when recording starts | ✅ | 🟢 |
| | Raw-text fallback with the reason | ✅ | ✅ |
| | Own API key in the OS secure store | 🟢 EncryptedSharedPreferences | 🟢 Windows Credential Manager |
| | Cleanup quality set | ✅ 28 cases, runs in CI with a key | ✅ 26 cases, manual |
| **Clean up existing text** | Hold the bubble to clean the field; hold again to undo | 🟢 | ➖ |
| | "Clean up" in the text-selection menu | 🟢 | ➖ |
| | Clean up selection (copy → clean → paste back, undo) | ➖ | 🟢 |
| **Controls** | Floating bubble (tap to dictate, drag, size, opacity) | 🟢 | ➖ |
| | Quick Settings tile (On / Off / Needs setup) | ✅ logic / 🟢 tile | ➖ |
| | Global hotkeys, hold to talk, shortcut recorder, conflict warnings | ➖ | ✅ / 🟢 |
| | Pill indicator (states, size, placement) | ➖ (the bubble shows state) | ✅ / 🟢 |
| | Tray icon with states and menu | ➖ | ✅ / 🟢 |
| **Icons** | 25 picker icons from the provided sheet | ✅ | ✅ |
| **Dictionary** | Add, edit, search, alphabetical | ✅ | ✅ |
| | Delete with undo, kept as a tombstone for sync, purged after 90 days | ✅ | ✅ |
| | Phonetic and case-insensitive matching | ✅ | ✅ |
| | Quick add from the text-selection menu | 🟢 | ➖ |
| | Import (sync format, old format, simple list) merged by term; export | ✅ | ✅ |
| | Automatic migration to the sync format (ids, timestamps) | ✅ | ➖ (always used it) |
| **Dictionary sync** | Protocol in `docs/sync-protocol.md` (AES-256-GCM, HKDF, per-id merge) | ✅ (test vector checked) | ✅ |
| | Pairing by QR code | 🟢 phone scans, from Settings › Computer sync | 🟢 computer shows the code |
| | Computer sync screen and Home row (status in amber when action is needed) | ✅ / 🟢 | ➖ |
| | Several paired devices, Unpair | ✅ storage / 🟢 screen | 🟢 |
| | "Synced with *name* *time ago*" per paired device | 🟢 | 🟢 |
| | 401 → "Pair again" | ✅ | ✅ |
| | Last working address and QR addresses first (in parallel), then mDNS `_wirl._tcp` | ✅ order / 🟢 NSD | 🟢 advertises |
| | Short LAN timeouts (2 s connect, 10 s read) | ✅ | ➖ |
| | Plain errors: different Wi-Fi networks, or computer not answering (firewall) | ✅ | ➖ |
| | Private networks only | ✅ | ✅ |
| | Syncs right away when a sync screen opens, after pairing and on Sync now ("Syncing…" shown) | ✅ engine / 🟢 screen | ➖ (the computer is the server) |
| | Background sync after changes, on joining Wi-Fi, every 30 min on unmetered networks | 🟢 | ➖ |
| | Silent, failures logged in Diagnostics and retried | ✅ logging / 🟢 retry | 🟢 |
| **App** | Settings home with pages (Dictation, AI cleanup, Dictionary, Computer sync, Bubble, Setup, About) | ✅ | 🟢 |
| | Setup checklist on first run; Test dictation; optional "Sync with your computer" | 🟢 | 🟢 |
| | Start with the system | 🟢 (accessibility service) | 🟢 |
| | Reset to defaults (keeps key, dictionary, pairing) | ✅ | ✅ |
| | Developer options (7 taps on the version) with Diagnostics | ✅ | 🟢 |
| **Reliability** | Timeouts on the API call | ✅ 15 s | ✅ |
| | Watchdog on stuck dictation | ⬜ | ✅ |
| | Diagnostics: last 20 entries with timing and thermal status | ✅ | ✅ |
| **Releases** | Fixed signing key, so installs update over the last release | 🟢 | 🟢 (updater key) |
| | Builds on every pull request; release from `main` | 🟢 | 🟢 |
| | In-app auto-update | ⬜ (install from GitHub Releases) | 🟢 |
