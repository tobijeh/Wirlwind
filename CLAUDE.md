# Wirl: working notes for Claude

Wirl is a personal Android app (Kotlin, min SDK 26) that cleans up dictated text with Claude. It's sideloaded, not on the Play Store.

## Build, test, release

- Tests: `./gradlew testDebugUnitTest`. Lint: `./gradlew lintDebug`. Builds: `./gradlew assembleDebug assembleRelease`.
- Every PR must pass the `build-and-test` check before it merges. Releases are built only from `main` by `build.yml`.
- Releases are signed with the fixed key from `signing/wirl.p12`, via `WIRL_KEYSTORE`. Never change it. Installs must keep updating over the last release.
- Versions come from the GitHub run number. Don't hard-code `versionCode`.

## Cleanup rules (these are product rules, not style)

- **One cleanup function.** Hold-to-clean and dictation both call `ClaudeClient.clean` → `CleanupPipeline`. Never add a second implementation.
- **Model:** always `claude-haiku-5-5` (`ClaudeApi.MODEL`). No model picker, and no setting for it.
- **Output rules.** The core prompt tells Claude never to describe sounds or add bracketed annotations, and to return an empty response for input with no intended words.
- **Prompt:** the core prompt is `CleanupPrompt.CORE`. It is an editor prompt: it resolves self-corrections, removes restarts, repeats and filler, carries out spoken commands ("new paragraph", "scratch that", "make that a list", "question mark"), fixes sound-alike and wrong-word errors, and keeps casual tone. It is built in and not editable. The only user input is optional *style notes*, appended under "Speaker's style preferences:". Don't add a prompt editor. Dictation and hold-to-clean send the same prompt.
- **Cleanup quality set.** `evals/cleanup_cases.json` holds the cases (input, expected meaning, optional dictionary and alternatives). Any change to `CleanupPrompt`, the cleanup code, or the dictionary prompt must keep the set above 90%. Add a case when a new behavior is added or a real mistake is found. `CleanupEvalTest` runs the set against the real API with claude-haiku-5-5, and Haiku judges each output. It runs only when `ANTHROPIC_API_KEY` is set. The `cleanup-eval` workflow sets it and runs on pull requests that touch the prompt or cleanup code.
- **Dictionary:** relevant entries are added by `DictionaryPrompt.forText`. Always apply the same rule.
- **Alternatives:** only dictation sends recognizer alternatives (`CleanupPrompt.build(..., alternatives)`). Hold-to-clean never does.
- **Dictation waits for Claude.** The text is inserted only after the reply. Raw text is inserted only when the call fails or cleanup is off, and it says "Raw: cleanup failed" with the reason.
- **Default on:** "Clean up dictation with AI" is on by default. Settings migrations must never leave it off for users who didn't turn it off.

## Dictation engines

- **Whisper is the default engine.** It runs on the phone through whisper.cpp (`app/src/main/cpp/`, built by CMake with the NDK for arm64-v8a only). The version is pinned to one commit in `CMakeLists.txt`. Change the pin on purpose, and test it on a phone.
- **Models are downloaded, never bundled.** `WhisperModel` lists three: Fast (`base.en` q5_1, default), Accurate (`small.en` q5_1), and Multilingual (`base` q5_1). They come from the official `ggerganov/whisper.cpp` repository on Hugging Face. Each has a pinned size and SHA-256, and a download must match both (`ModelDownloader`). A download resumes from its `.part` file with a Range request. A file that fails the check is deleted, and so is a file that won't load.
- **Downloads wait for Wi-Fi.** Model downloads start only on an unmetered network, or on mobile data when the user has allowed it (`DownloadPolicy`). Settings shows each model's status, with Download, Resume, and Delete.
- **Google's SpeechRecognizer is the fallback**, chosen in Settings. Dictation uses it automatically when the Whisper model isn't downloaded or the native library won't load (`EngineChoice`). If Whisper can't open the microphone at start, the same fallback applies.
- **Recording and chunks.** `WhisperSession` records 16 kHz mono PCM continuously until the user taps stop. A pause of about 600 ms ends a chunk (`PauseDetector`), and a chunk is never longer than about 20 s. Each chunk is transcribed on one background thread, in order (`ChunkPipeline`), while recording goes on. Chunks finished during recording are reused at stop, never transcribed again, so only the final chunk remains when the user taps stop. Chunk text is joined in spoken order, and a repeated chunk is ignored (`ChunkAssembler`).
- **No live preview for Whisper.** While recording, only the red bubble shows (`DictationPreview`). Google's recognizer keeps its word-by-word preview when it's the engine in use.
- **Dictation is for words, not captions.** Only chunks where VAD found speech (at least six 30 ms frames of it) are transcribed. Whisper runs with blank and non-speech tokens suppressed, and a segment it judges to be silence is dropped. `TranscriptFilter` then removes sound annotations: square-bracket tags like `[BLANK_AUDIO]`, and parenthetical or asterisk notes like `(music)` or `*sighs*` that name a sound. A note is removed only if it names a sound, so `(see below)` stays. It also drops silence phrases that Whisper invents ("Thank you.", "Thanks for watching!", "Please subscribe", "you") when they make up the whole chunk or the whole dictation. Text in a longer sentence is kept.
- **Nothing is inserted without words.** `SpeechGate` decides this. Claude is called only when the spoken words have at least three letters (`SpeechGate.MIN_LETTERS`). Otherwise nothing is sent, nothing is inserted, and nothing is shown: the recording is discarded quietly and logged in Diagnostics. An empty reply from Claude also inserts nothing. A reply that is a remark about the recording ("No speech detected.", "Unclear audio, please try again.") is treated the same way (`SpeechGate.isRecordingRemark`); the whole reply must be the remark, so real sentences that mention silence are kept. Hold-to-clean never clears a field for an empty reply, and still says "No speech heard" when there's nothing to clean.
- **Quiet accidental recordings.** A recording shorter than one second is discarded without processing or a message (`RecordingRules.tooShort`). Whisper's invented phrases and sound-only audio are discarded the same way.
- **Cancel while recording.** While recording, a small red X shows at the bottom centre of the screen (`CancelTarget`). Dragging the bubble so its centre is over the X cancels the dictation without processing, and the bubble goes back to where it was before the drag. Cancelled chunks that haven't started are dropped (`ChunkPipeline.cancel`), and the microphone is released (`WhisperSession.cancel`). Nothing is inserted.
- **Quick Settings tile.** `WirlTileService` shows On, Off, or Needs setup (`WirlTileLogic`). Needs setup means the accessibility service is off or the API key is missing; tapping then opens accessibility settings or the setup checklist. Tapping On or Off toggles `Prefs.bubbleEnabled`, the same setting as the Settings toggle, and the tile follows that setting. Long-press opens Settings through the `QS_TILE_PREFERENCES` filter on `MainActivity`.
- **Off and on.** Turning Wirl off discards any recording or processing without inserting anything, releases the microphone, audio focus, and recognizer, hides the bubble, and stops watching fields. The accessibility service stays connected. Turning it on rebuilds the bubble and resets all state (recording, processing, undo, overlays), so off then on acts as a reset. A Claude reply that arrives after a discard is not inserted.
- **Audio is never stored.** Chunk samples live only in memory, and are dropped once transcribed. Nothing is written to disk or uploaded.
- **Accuracy.** Dictionary terms go to Whisper as the initial prompt (`WhisperPrompt.build`). On stop, the full transcript goes through the same `ClaudeClient.clean` as every other dictation. Whisper doesn't send recognizer alternatives.
- **Memory and warm-up.** The model loads when the accessibility service connects and when dictation starts, stays loaded, and is freed after ten minutes without use (`ModelHolder`). Threads default to four (the performance cores), and Developer options can change them. The native library is built with `-O3`, using baseline NEON. dotprod and i8mm aren't enabled, because a build that needs them crashes on phones without them. Greedy decoding with no timestamps and no temperature fallback keeps decoding to one pass.
- **Claude speed.** One shared HTTP client keeps connections open between dictations. Dictation opens the connection when it starts (`ClaudeClient.warmUp`), so the request doesn't wait for a TLS handshake.
- **Developer options.** The Diagnostics screen is hidden behind a switch at the bottom of Settings, and it's off by default. Logging runs either way, and keeps the last 20 attempts.
- **Sounds.** Whisper dictation makes one start tone and one end tone (`DictationTones`). It makes no other sounds, and doesn't mute streams. The mute logic is for the Google fallback only.
- **Diagnostics** open with a TIMING section: model wait, final chunk, Whisper total, Claude first byte and total (and whether its connection was reused), output tokens, thinking, insert time, and stop-to-insert time. Then the engine, cleanup, insert, and sound sections (`DictationTiming`).
- **Tests** cover everything that doesn't need the phone: chunk assembly, pause detection, the pipeline (including cancel), the model holder, downloads (resume, corrupt files), prompts, the engine fallback, the recording rules and tile logic (`RecordingRulesTest`), and gesture reset. The native bridge, the microphone, the X target, the tile service, and the off/on switch can't run in unit tests. Check those on a phone.

## Look and feel

- **Palette** (`values/colors.xml`, with night values in `values-night/`): `brand` purple (the icon tile), `brand_dark`, `brand_light`, `ink`, `paper` (page background), `card` (settings cards). Use these tokens, not hex values. Teal is gone.
- **Icons** are the tiles from the icon set, in `drawable-nodpi/ux_*.png`, with transparent rounded corners. `ux_logo_tile` is the app mark and `ic_wirl_glyph` is the white swirl for the bubble and the notification. The launcher is the swirl on the ink background.
- **Settings** is a stack of cards. Each card opens with `section_header.xml` (icon tile, title, one line). Controls inside use the `Wirl_Row` and `Wirl_Caption` styles. Style names must not contain dots, or Android reads them as children of the part before the dot.

## Insertion and field text

- Dictated text is pasted at the cursor via the clipboard, and the user's clipboard is restored. Setting the whole field is only a fallback.
- Placeholder text (hint or content description) is never treated as typed text (`FieldText`), and is never sent to Claude.
- Dictation never adds a leading space to a field that was empty (blank, hint, content description, or cursor at 0), and a safety net removes any leading whitespace left in such a field after inserting (`LeadingWhitespaceGuard`).

## Settings

- Settings migrations are versioned (`Prefs`, `settings_version`). Add a new version step for any change that could strand old saved values.
- "Reset to defaults" clears `Prefs.RESETTABLE_KEYS`. It keeps the API key and the dictionary. Add new settings to that list.

## Diagnostics

- Every dictation attempt is logged (`DiagnosticEntry`): recognizer trail, error codes, the field state, the transcript, cleanup result, and the text inserted. Keep this useful for debugging phone-only problems.

## Tests

- Pure logic is tested with plain JUnit. Anything that touches `org.json`, prefs, or the API is tested with Robolectric and `MockWebServer`.
- The bubble service, accessibility, and speech recognizer can't run in unit tests. Those need a phone check, and PRs should say so.
