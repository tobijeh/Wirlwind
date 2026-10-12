# Wirl

A floating bubble that cleans up dictated text with one tap, in any app (WhatsApp included).
It also adds a **Clean up** button to the text-selection menu.

Personal, sideloaded app. Min Android 8.0 (API 26).

## How it works

- **Bubble.** An accessibility service draws a small blue bubble while a text field is focused.
  - **Tap:** sends the field's text to Claude and replaces it with the cleaned version.
  - **Long-press:** restores the original text (undo).
  - **Drag:** move it anywhere. The position is remembered.
  - Password fields are ignored, and the bubble never shows inside Wirl itself.
  - If the text changes while it's cleaning, or the request fails, your text is left untouched.
- **Selection menu.** Select text, then pick **Clean up** from the menu (it may be under ⋮).
  In read-only text, the result is copied to the clipboard instead.

Uses `claude-haiku-5-5` via the Messages API, with a 15-second timeout.

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

### Option C: GitHub Actions (no setup)

Every push to `main` builds the APK on GitHub. Open the repo's **Actions** tab, click the latest
**Build APK** run, and download **Wirl-apk** under **Artifacts** (it's a zip with the APK inside).
You can also start a build by hand with **Run workflow**.

Each run signs with a fresh debug key, so you'd have to uninstall before installing a newer build.
To make updates install in place, add your own key as a repo secret once:

```bash
base64 -w0 ~/.android/debug.keystore   # macOS: base64 -i ~/.android/debug.keystore
```

Copy the output into **Settings → Secrets and variables → Actions → New repository secret**,
named `DEBUG_KEYSTORE_BASE64`. (The file exists after you've built once locally or in Android Studio.)

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
- **Show floating bubble** turns the bubble off without disabling the service. The selection menu
  button keeps working either way.

## Troubleshooting

- **Bubble doesn't appear:** check the accessibility service is still on. Some phones (Xiaomi,
  Samsung, Huawei) turn it off to save battery; set Wirl's battery usage to **Unrestricted**.
- **"Invalid API key":** re-paste the key and tap **Save**. The screen shows its last 4 characters.
- **"Couldn't replace text in this field":** a few apps don't allow text to be set by accessibility
  services. Use the selection menu's **Clean up** instead.
- **"Text too long to clean up":** the reply would exceed 1024 tokens. Clean it up in parts.
