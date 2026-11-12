# Features

Every Wirl feature, with its status in Wirl for Android (this repository) and Wirl for Windows (`tobijeh/Wirl-Desktop`).

✅ done and covered by unit tests · 🟢 done, needs a check on a real device · ➖ not applicable on that platform · ⬜ not available

The Desktop column is taken from `FEATURES.md` in Wirl-Desktop. Update both files when a shared feature changes.

| Area | Feature | Android | Desktop |
|---|---|---|---|
| **Dictation** | Local Whisper transcription (whisper.cpp) | 🟢 on-phone, arm64 | 🟢 sidecar process |
| | Google speech recognizer as fallback engine (automatic backup, not a setting; Advanced shows the engine in use) | 🟢 | ➖ |
| | GPU acceleration (Vulkan) with CPU fallback | ➖ CPU only | 🟢 |
| | Crash isolation for Whisper, restart, reason logged | ➖ in-process | 🟢 |
| | Background chunking at natural pauses (5–10 s) | ✅ | ✅ |
| | Only the final chunk is left to transcribe at stop | ✅ | 🟢 |
| | `audio_ctx` sized per chunk | ✅ (256–1500) | ✅ (min 384) |
| | Dictionary terms as the Whisper initial prompt | ✅ | ✅ |
| | Sound-tag and invented-phrase filtering | ✅ | ✅ |
| | Silent discard (< 1 s, or no words) | ✅ | ✅ |
| | Insert at the cursor via clipboard paste, clipboard restored only if it still holds the dictation | ✅ logic / 🟢 | ✅ logic / 🟢 Windows |
| | Paste check: settle, confirm the text appeared, paste once more, then set-text, then leave it on the clipboard | ✅ logic / 🟢 | ✅ logic / 🟢 Windows |
| | Short, clean English typed as heard (no cleanup); short answers to spoken questions caught | ✅ logic / 🟢 | ⏳ prompt ready |
| | Empty fields written straight in (no clipboard, no "pasted from clipboard" message), checked, paste as backup | ✅ logic / 🟢 | ➖ |
| | Fallback when the field can't take text | 🟢 copies to clipboard | ✅ logic / 🟢 "Copied, press Ctrl+V" |
| | Pause other media during dictation | 🟢 always on | 🟢 |
| | Start and stop tones | 🟢 | 🟢 |
| | Slide to cancel while recording | ✅ logic / 🟢 gesture | ➖ (Esc cancels) |
| | Quick switch: tick several downloaded models, swipe the recording bubble up/down to switch (shows EN, EN+, 粵, 普); tapping a model never downloads it; a looping demo on the Dictation page shows the swipe; the label also shows when recording starts | ✅ logic / 🟢 gesture | ➖ |
| **Models** | English · Fast (base.en q5_1), English · Accurate (small.en q5_1); Multilingual removed on Android | ✅ | ✅ (Multilingual still offered) |
| | Cantonese (粵語): SenseVoice int8 via sherpa-onnx, language yue, use_itn on | ✅ download, settings / 🟢 on-device | ⬜ |
| | Cantonese mode in cleanup (traditional characters, spoken Cantonese, English kept; `docs/cantonese.md`) | ✅ | ⬜ |
| | Mandarin (普通话): same SenseVoice files, language zh, Mandarin mode in cleanup (simplified; `docs/mandarin.md`) | ✅ / 🟢 on-device | ⬜ |
| | One model per language; Google's recognizer follows the model (en, yue-Hant-HK, zh-CN); no separate language picker | ✅ | ➖ |
| | Cancel a model download (Resume continues it) | ✅ | ⬜ |
| | Download from Hugging Face with resume and SHA-256 check | ✅ | ✅ |
| | Downloads wait for Wi-Fi unless mobile data is allowed | ✅ | ➖ |
| | Model kept loaded, freed after 10 min idle | ✅ | 🟢 kept loaded |
| | Language picker | ➖ (the model sets the language) | 🟢 |
| **AI cleanup** | Claude Haiku 5.5, built-in prompt | ✅ adaptive thinking, low effort | ✅ thinking off |
| | Personal style notes | ✅ | ✅ |
| | Only plausibly matching dictionary entries sent | ✅ | ✅ |
| | Keep-alive connection, warmed when recording starts | ✅ | 🟢 |
| | Raw-text fallback with the reason | ✅ | ✅ |
| | If Claude answers a dictation instead of cleaning it up (it sounded like a request), the spoken words are inserted; clean-up leaves the text as it was | ✅ | ✅ |
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
| **Dictionary sync** | Encrypted mailbox relay, protocol v2 in `docs/sync-protocol.md` (HKDF keys, AES-256-GCM, per-id merge) | ✅ (test vector checked) | ✅ |
| | Works on any network (Wi-Fi or mobile data), no accounts | ✅ / 🟢 | ✅ / 🟢 |
| | Pairing by QR code (v1 codes rejected with "Update Wirl on your computer") | ✅ parsing / 🟢 phone scans, from Settings › Computer sync | 🟢 computer shows the code |
| | Computer sync screen and Home row (status in amber when action is needed) | ✅ / 🟢 | ➖ |
| | Several computers (one mailbox each), Unpair locally | ✅ storage / 🟢 screen | 🟢 |
| | "Synced with *name* *time ago*", "Also synced: *other phones*" | ✅ / 🟢 | 🟢 |
| | Compare-and-swap with merge and retry on conflicts (5 at most) | ✅ | ✅ |
| | Uploads only on changes, plus a 15-minute heartbeat | ✅ | ✅ |
| | 401 / 410 / undecryptable → "Pair again"; network → "Can't reach the sync service" | ✅ | ✅ |
| | Old Wi-Fi pairings dropped once with "Pair again with your computer's new code" | ✅ | ➖ |
| | Syncs right away when a sync screen opens, after pairing and on Sync now ("Syncing…" shown) | ✅ engine / 🟢 screen | 🟢 Sync now |
| | Background sync 2 s after changes, when the network comes back, every 30 min (computer: every 5 min) | 🟢 | 🟢 |
| | Silent, failures logged in Diagnostics and retried | ✅ logging / 🟢 retry | 🟢 |
| **App** | Settings home with pages (Dictation, AI cleanup, Dictionary, Computer sync, Bubble, Setup, About) | ✅ | 🟢 |
| | Setup checklist on first run (last item: speech model downloaded); optional "Sync with your computer" | 🟢 | 🟢 |
| | Check Google recognizers (Developer options, troubleshooting) | 🟢 | ➖ |
| | Start with the system | 🟢 (accessibility service) | 🟢 |
| | Reset to defaults (keeps key, dictionary, pairing) | ✅ | ✅ |
| | Developer options (7 taps on the version) with Diagnostics | ✅ | 🟢 |
| **Reliability** | Timeouts on the API call | ✅ 15 s | ✅ |
| | Watchdog on stuck dictation | ⬜ | ✅ |
| | Diagnostics: last 20 entries with timing and thermal status | ✅ | ✅ |
| **Releases** | Fixed signing key, so installs update over the last release | 🟢 | 🟢 (updater key) |
| | Builds on every pull request; release from `main` | 🟢 | 🟢 |
| | In-app auto-update | ⬜ (install from GitHub Releases) | 🟢 |
