# Meow Island — Setup Guide

> Status: source code only. Never compiled or run by the author. Expect small Kotlin/Gradle fixes on first build. See README.md for what is and isn't implemented.

## 0. Updating to the Meow Island revision
Work from your short copy (for example `C:\si`). Unzip the update over it, then run each line separately:
```
cd C:\si
npx expo prebuild --platform android --clean
cd android
.\gradlew.bat assembleRelease -PreactNativeArchitectures=arm64-v8a
```
Prebuild must print `Finished prebuild` with no red error (it applies the new icon, launch window, manifest entries and Kotlin). Install over the old app; the launcher label and icon change to Meow Island. No new npm packages are required.

## 0b. Previous update notes
Copy over `src/`, `plugins/` (all files) and `docs/`. `package.json`/`app.json` need no change. Then:
```
cd <project root>
npx expo prebuild --platform android --clean
cd android
.\gradlew.bat assembleRelease -PreactNativeArchitectures=arm64-v8a
```
`prebuild --clean` is required now: the plugin adds new manifest entries (boot receiver, narrow app-visibility query) and registers the native module. If it stops with `withIsland: PackageList(this).packages not found`, paste your `MainApplication.kt` and I will adapt the plugin.
Uninstall nothing; install over the old APK (turn Play Protect scanning off if it blocks the sideload).

## 1. Prerequisites
| Tool | Version | Check |
|---|---|---|
| Node.js | 20 or newer | `node -v` |
| JDK | 17 (recommended) | `java -version` |
| Android Studio | latest (installs SDK + platform tools) | — |
| Android SDK | Platform 34/35, Build-Tools, Platform-Tools | SDK Manager |
| Android phone | Android 10+ with USB debugging on | `adb devices` |

Set environment variables (add to your shell profile):
- macOS/Linux: `export ANDROID_HOME=$HOME/Android/Sdk` (macOS: `$HOME/Library/Android/sdk`) and add `$ANDROID_HOME/platform-tools` to `PATH`.
- Windows: set `ANDROID_HOME` to `C:\Users\<you>\AppData\Local\Android\Sdk` and add `%ANDROID_HOME%\platform-tools` to `Path`.

## 2. Install and generate the native project
```
unzip smart-island.zip && cd smart-island
npm install
npx expo prebuild --platform android --clean
```
`prebuild` creates the `android/` folder and runs `plugins/withIsland.js`, which:
- adds the manifest permissions and the two services (`IslandService`, `MediaListener`)
- copies the Kotlin files from `plugins/kotlin/` into `android/app/src/main/java/com/smartisland/app/`
- registers `IslandPackage` in `MainApplication`

If the registration step fails (the plugin matches `PackageList(this).packages.apply {`), open `android/app/src/main/java/com/smartisland/app/MainApplication.kt` and add `add(IslandPackage())` inside that block by hand.

## 3. Run or build
Debug build on a connected phone (fastest way to test):
```
npx expo run:android
```
Release APK:
```
cd android
./gradlew assembleRelease        # Windows: gradlew.bat assembleRelease
```
Output: `android/app/build/outputs/apk/release/app-release.apk`. The default release build is signed with the debug key, which is fine for personal installs. Create your own keystore before sharing it widely.

Install: `adb install -r android/app/build/outputs/apk/release/app-release.apk`, or copy the APK to the phone and open it.

## 4. First-run permissions (in order)
1. **Allow notifications** — popup on first launch (needed for reminders).
2. **Overlay** — tap Grant, enable "Allow display over other apps" for Meow Island.
3. **Notification access** — tap Grant, enable Meow Island. Required for music info/controls and notification previews. On Android 13+ sideloaded apps may show "Restricted setting": open App info > ⋮ > *Allow restricted settings*, then retry.
4. **Usage access** — tap Grant, enable Meow Island. Required for Screen Time Guardian.
5. Return to the app, tap **Refresh permissions**, then **Start island**.

Denying a permission only disables the feature that needs it. Revoking one later shows ❌ again after refresh.

## 5. Keep it alive (important)
- Settings > Apps > Meow Island > Battery > **Unrestricted**.
- Xiaomi/Oppo/Vivo/Huawei/Samsung: enable Autostart and lock the app in Recents; disable "sleeping apps"/"adaptive battery" for it.
- Android 14+: Settings > Apps > Special access > *Alarms & reminders* > allow Meow Island (exact reminders).
- Reboot restart is NOT implemented yet; open the app and tap Start island after a reboot.

## 6. Testing checklist (tests A-G; health checks are in docs/HEALTH.md)
Overlay: Start island, press Home, open another app: pill stays; Stop removes it. Permissions: each Grant button opens the right page; status flips after returning. Music: play in a media app; title/art/CD; pause stops rotation. Screen time: Add app (picker, search), set 1 min, use the app >1 min: 😡; leave: 😊 once. Notifications: grant Notification access, send yourself a notification. Reminders: schedule +2 min, background the app; it fires and shows on the island. Persistence: force-close and reopen; settings, limits and reminders remain.
- [ ] Pill appears at top after Start island, over other apps
- [ ] Play music (Spotify/YouTube Music): title, artist, art show; art spins; pausing stops spin
- [ ] Settings › Meow Island Device › Music: lyrics on; play a song: synced lines or plain lines appear; switching tracks clears the old line and the old cover
- [ ] Play a YouTube video: thumbnail/title/progress bar at the bottom, no lyrics or captions; playback controls on the phone still work
- [ ] Replay a song already played: its cached cover and lyrics appear immediately; no repeated download/transfer
- [ ] Send yourself a notification: preview appears for ~5 s
- [ ] Schedule a reminder for 1 minute: island expands, notification fires; Snooze works; close the app and test again
- [ ] Add a limit (package name e.g. `com.android.chrome`, 1 min), use the app >1 min: 😡 appears; leave the app: 😊 for ~2.5 s
- [ ] Stop island removes the pill

## 7. Troubleshooting
| Problem | Fix |
|---|---|
| `SDK location not found` | Set `ANDROID_HOME` or create `android/local.properties` with `sdk.dir=/path/to/Sdk` |
| Gradle needs a different JDK | Use JDK 17: `export JAVA_HOME=<jdk17 path>` |
| `Unresolved reference: IslandPackage` | Re-run prebuild with `--clean`; confirm Kotlin files exist in `android/app/src/main/java/com/smartisland/app/` |
| `NativeModules.IslandModule` is undefined | Package not registered; add `add(IslandPackage())` manually (section 2) |
| No music info | Notification access not granted, or the player exposes no media session |
| Pill never shows | Overlay permission off, or OEM killed the service (section 5) |
| Package names for limits | Find with `adb shell pm list packages | grep <name>` |

## 7b. Windows build notes
- Use PowerShell commands one per line (older PowerShell has no `&&`). Run Gradle as `.\gradlew.bat assembleRelease -PreactNativeArchitectures=arm64-v8a`.
- Build from a SHORT path (e.g. `C:\si`) if any native library fails with "mkdir ... No such file or directory" (Windows 250-char limit). Before moving the folder run `.\gradlew.bat --stop` and close editors/terminals using it.
- Run `npx expo ...` only inside the folder containing package.json, otherwise npx fetches the obsolete global expo-cli.
- Kotlin version is pinned to 1.9.25 via expo-build-properties (needed by expo-modules-core 2.2.3).
- Reanimated was removed (long C++ paths, not used yet).

## 8. Project layout
```
smart-island/
├── README.md              overview, status, limitations
├── docs/SETUP.md          this guide
├── package.json  app.json  tsconfig.json  index.js
├── plugins/
│   ├── withIsland.js      Expo config plugin (manifest, Kotlin copy, registration)
│   └── kotlin/            IslandService, MediaListener, IslandModule(+Package)
└── src/
    ├── App.tsx            permissions + reminder + limits UI
    └── core/              db, reminders, activities (priority), guardian
```
