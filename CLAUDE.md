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
- **Prompt:** the core prompt is `CleanupPrompt.CORE`. It is built in and not editable. The only user input is optional *style notes*, appended under "Speaker's style preferences:". Don't add a prompt editor.
- **Dictionary:** relevant entries are added by `DictionaryPrompt.forText`. Always apply the same rule.
- **Alternatives:** only dictation sends recognizer alternatives (`CleanupPrompt.build(..., alternatives)`). Hold-to-clean never does.
- **Dictation waits for Claude.** The text is inserted only after the reply. Raw text is inserted only when the call fails or cleanup is off, and it says "Raw: cleanup failed" with the reason.
- **Default on:** "Clean up dictation with AI" is on by default. Settings migrations must never leave it off for users who didn't turn it off.

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
