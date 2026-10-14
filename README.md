# Wirl

A floating bubble that cleans up dictated text with one tap, in any app (WhatsApp included).
It also adds a **Clean up** button to the text-selection menu.

Personal, sideloaded app. Min Android 8.0 (API 26).

## How it works

- **Bubble.** An accessibility service draws the round teal Wirl bubble while a text field is focused.
  - **Tap:** sends the field's text to Claude and replaces it with the cleaned version.
  - **Long-press:** restores the original text (undo).
  - **Drag:** move it anywhere. The position is remembered.
  - Password fields are ignored, and the bubble never shows inside Wirl itself.
  - If the text changes while it's cleaning, or the request fails, your text is left untouched.
- **Selection menu.** Select text, then pick **Clean up** from the menu (it may be under ⋮).
  In read-only text, the result is copied to the clipboard instead.

Uses `claude-haiku-5-5` via the Messages API, with a 15-second timeout.

## Dictation

Speak, and the cleaned-up text appears where the cursor is, in any app.

- **Tap the bubble** to start. The bubble turns red and shows what you've said so far. Tap again to stop.
- **Hold the bubble** to clean up the text already in the field. Hold again, with the text unchanged, to put the original back.
- Dictation uses Android's speech recognition, on-device when the phone supports it. Long pauses don't stop it; it keeps going until you tap.
- Dictation tries the Google app recognizer, then Speech Services by Google, then the phone's default. The on-device recognizer is used first only when its offline pack is installed. Any language error moves on to the next recognizer.
- **Settings → Test dictation** starts each recognizer and reports which ones work. Results also go to Diagnostics.
- If the on-device speech pack for your language isn't installed, dictation uses the online recognizer instead. Settings shows the language in use and whether its offline pack is installed. On Android 13 and later, **Download speech pack** asks the phone to download it.
- If your phone's language isn't supported, dictation uses English (US).
- Your dictionary terms help the recognizer spell names correctly on Android 13 and later.
- **Settings:** the microphone permission button, the language, and "Clean up dictation with AI". Turn the last one off to insert exactly what you said.
- If cleanup fails, Wirl inserts what you said instead. If the field can't take text, it copies it to the clipboard.
- Dictated text is pasted at the cursor, so the rest of the box is never rewritten. Your clipboard is restored afterwards.
- Placeholder text (such as WhatsApp's "Message") is never treated as something you typed, and hold-to-clean says "Nothing to clean" for it.
- If dictation fails, the bubble shows the specific cause. **Settings → Diagnostics** keeps the last 20 attempts with the error code and any exception; **Copy** puts them on the clipboard.

## Personal dictionary

Teach Wirl the names and words it gets wrong. Open **Settings → Dictionary**, or select text in any app
and choose **Add to Wirl dictionary**.

- **Term:** the correct spelling, e.g. `Sela`.
- **Sounds like:** comma-separated mishearings, e.g. `salad, Sella, Stella`.
- **Context note:** when the change applies, e.g. `my web app; different from Selah, the biblical term`.

Each cleanup sends only the entries that might match the text, so the model knows to spell them that way.
Use **Import** and **Export** to move the dictionary to another phone, as JSON.

## 1. Get an API key

Create a key at <https://console.anthropic.com/settings/keys>. You'll paste it into the app.
It's stored encrypted on the phone (EncryptedSharedPreferences) and never in the code.

## 2. Build the APK

You need **JDK 17 or newer** and the **Android SDK**. The easiest way to get both is Android Studio.

### Option A: Android Studio (easiest)

1. Install [Android Studio](https://developer.android.com/studio).
2. **File → Open** and choose this folder. Wait for Gradle sync to finish.
3. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
4. Click **locate** in the popup. The file is `app/build/outputs/apk/debug/app-debug.apk`.

### Option B: Command line

```bash
# Point Gradle at your SDK once (Android Studio's default path shown):
echo "sdk.dir=$HOME/Android/Sdk" > local.properties          # Linux
# echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties # macOS
# Windows: sdk.dir=C\:\\Users\\YOU\\AppData\\Local\\Android\\Sdk

./gradlew assembleRelease        # Windows: gradlew.bat assembleRelease
```

The APK is at `app/build/outputs/apk/release/app-release.apk`. It's signed with the
debug key, which is fine for sideloading. (`assembleDebug` also works.)

### Option C: GitHub Releases (no computer needed)

Every push to `main` builds a signed release APK and publishes it as a GitHub Release
tagged `v1.0.<run number>`. On your phone, open the repo's **Releases** page
(<https://github.com/tobijeh/Wirlwind/releases/latest>) and tap `wirl-1.0.N.apk`.
You can also start a build from the **Actions** tab with **Run workflow**.

Releases are signed with the key in the `DEBUG_KEYSTORE_BASE64` repo secret
(**Settings → Secrets and variables → Actions**), so each one installs over the last.
If that secret is missing, the build fails instead of publishing an APK that can't update.

> **Keep building on the same computer.** Android only installs an update over an existing
> app if it's signed with the same key. A different machine has a different debug key, so you'd
> need to uninstall first (and re-enter your API key).

## 3. Install it on your phone

### Option A: USB (adb)

1. On the phone: **Settings → About phone**, tap **Build number** 7 times to unlock Developer options.
2. **Settings → System → Developer options → USB debugging:** on.
3. Connect by USB and tap **Allow** on the phone's prompt.
4. Install:
   ```bash
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```
   (`adb` is in your SDK's `platform-tools` folder. In Android Studio you can also just press **Run ▶**.)

### Option B: Direct APK install

1. Copy the APK to the phone (Google Drive, email it to yourself, or USB file transfer).
2. Open it from the Files app or the download notification.
3. When asked, allow **Install unknown apps** for that app (Files, Chrome, Drive…), go back, and tap **Install**.
4. If Play Protect warns about an unknown app, tap **More details → Install anyway**.

## 4. Set it up

1. Open **Wirl**.
2. Paste your API key and tap **Save**.
3. Tap **Open settings**, find **Wirl** (sometimes under **Downloaded apps** or
   **Installed apps**), and turn it on.
   - **Android 13+:** if the switch is greyed out with "Restricted setting", go to
     **Settings → Apps → Wirl**, tap **⋮** (top right) → **Allow restricted settings**, then try again.
     This happens to every sideloaded accessibility app.
4. Open WhatsApp, tap the message box, dictate, and tap the bubble.

## Tweaking

- **Cleanup instructions** on the settings screen are the system prompt. Edit and **Save**.
  **Reset to default** restores the original.
- **Bubble size** (40–80 dp) and **opacity** (30–100%) sliders change the floating bubble
  instantly, with a live preview beside them.
- **Show floating bubble** turns the bubble off without disabling the service. The selection menu
  button keeps working either way.

## Troubleshooting

- **Bubble doesn't appear:** check the accessibility service is still on. Some phones (Xiaomi,
  Samsung, Huawei) turn it off to save battery; set Wirl's battery usage to **Unrestricted**.
- **"Invalid API key":** re-paste the key and tap **Save**. The screen shows its last 4 characters.
- **"Couldn't replace text in this field":** a few apps don't allow text to be set by accessibility
  services. Use the selection menu's **Clean up** instead.
- **"Text too long to clean up":** the reply would exceed 1024 tokens. Clean it up in parts.

## Tests and CI

- `./gradlew testDebugUnitTest` runs the unit tests: dictionary matching and editing, prompt building,
  the API request and response handling (against a fake server), error messages, and settings storage.
- `./gradlew lintDebug` runs Android lint.
- **CI** (`.github/workflows/ci.yml`) runs lint, unit tests, and a debug build on every pull request.
- **Releases** (`.github/workflows/build.yml`) are built only from `main`, after lint and tests pass.

### Require checks before merging to main

1. On GitHub, go to **Settings → Branches → Add branch ruleset** (or **Add classic branch protection rule**).
2. Set the branch name pattern to `main`.
3. Turn on **Require status checks to pass before merging** and add **build-and-test**.
   The check only appears in the list after it has run at least once.
4. Optional: turn on **Require a pull request before merging**.
5. To allow auto-merge, go to **Settings → General** and tick **Allow auto-merge** under Pull Requests.

