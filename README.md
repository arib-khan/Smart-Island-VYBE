# 🐱 Meow Island

<div align="center">
  
  <h3>A tiny floating island. A little cat energy. More control over your Android.</h3>
  
  <p>
    <a href="https://github.com/arib-khan/MEOW-Island/stargazers"><img src="https://img.shields.io/github/stars/arib-khan/MEOW-Island?style=for-the-badge&color=ba8cff" alt="GitHub stars"></a>
    <a href="https://github.com/arib-khan/MEOW-Island/forks"><img src="https://img.shields.io/github/forks/arib-khan/MEOW-Island?style=for-the-badge" alt="GitHub forks"></a>
    <a href="https://github.com/arib-khan/MEOW-Island/issues"><img src="https://img.shields.io/github/issues/arib-khan/MEOW-Island?style=for-the-badge" alt="GitHub issues"></a>
    <img src="https://img.shields.io/badge/platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android">
    <img src="https://img.shields.io/badge/status-early%20development-orange?style=for-the-badge" alt="Early development">
  </p>

  **Meow Island is an early-stage Android app exploring a customizable floating island for quick, glanceable controls and useful updates.**

  [Explore the repository](https://github.com/arib-khan/MEOW-Island) · [Report a bug](https://github.com/arib-khan/MEOW-Island/issues) · [Request a feature](https://github.com/arib-khan/MEOW-Island/issues)

</div>

---

> [!NOTE]
> **This is a very early, actively developing project.** Some features are implemented in source code but have not yet been fully tested on a physical Android device. Expect bugs, incomplete behavior, and platform-specific limitations. Please read the [known limitations](#-current-status--known-limitations) before trying it.

## ✨ What is Meow Island?

Meow Island is a cat-inspired floating interface for Android, designed to keep useful information and quick actions close without constantly switching between apps.

The goal is simple: make a compact, customizable island that can surface music controls, reminders, timers, notifications, and screen-time feedback—wrapped in a playful, polished UI.

## 🐾 Features

| Feature | What it aims to do | Status |
|---|---|---|
| **Customizable floating island** | Show a compact pill above other apps; adjust position and size, with saved settings | Implemented in source; device testing pending |
| **Drag-to-position editor** | Move the island, use position controls/presets, and reset its location | Implemented in source; device testing pending |
| **Music controls** | Show media information and artwork; control previous, play/pause, and next when supported by the active media session | Implemented in source; player/device testing pending |
| **Universal music detection** | One provider-independent model for any app that publishes an Android media session (Spotify, Amazon Music, Apple Music, Tidal, Deezer, local players, YouTube) | Implemented in source; multi-provider testing pending |
| **Lyrics & cached artwork** | Opt-in LRCLIB → lyrics.ovh fallback, version-aware matching, synced or plain lyrics, phone-rendered multilingual/RTL lyric mask, persistent album-art cache and an art CRC that skips repeat transfers | Implemented in source; provider/device testing pending |
| **YouTube display** | Thumbnail, title, duration and a bottom progress bar; no caption or lyrics extraction | Implemented in source; device testing pending |
| **Animated music artwork** | Rotate artwork while playback is active and stop when playback pauses | Implemented in source; device testing pending |
| **Screen Time Guardian** | Choose installed apps, configure daily limits, and show cat reactions when a limit is exceeded or when you leave the app | Implemented in source; usage-access testing pending |
| **Repeat limit reminders** | Remind you at a configurable interval (default: five minutes) while an app remains over its limit | Implemented in source; timing/foreground-service testing pending |
| **Reminders** | Schedule reminders and surface them through the island and notifications | Present in project; end-to-end testing pending |
| **Timers** | Run countdown timers and show timer state on the island | Basic start/cancel flow; pause/resume not implemented |
| **Notification previews** | Surface notification content when Notification Access is granted | Implemented in source; device testing pending |
| **Hide for full-screen video** | Attempt to hide the island during supported full-screen playback scenarios | Best-effort heuristic; not universally reliable |
| **Cat-inspired look** | Dark UI, cat ears, and expressive 😾 / 😺 feedback | Included in current design |

**Feature status is intentionally conservative:** source-code implementation is not the same as a verified, reliable feature on every device.

## 🎬 Vision

Meow Island is being developed around a few principles:

- **Useful at a glance:** keep the island compact until something needs attention.
- **Your screen, your setup:** personalize its position and dimensions.
- **Playful, not distracting:** use cat expressions and smooth motion with restraint.
- **Native where necessary:** use Android services and APIs for system-level capabilities.
- **Transparent about limitations:** respect Android permissions and platform restrictions.

## 🧰 Tech Stack

- **React Native + Expo**
- **TypeScript**
- **Kotlin** for Android-specific integration
- **Android WindowManager overlay** for the floating island
- **Android media sessions** for playback metadata and supported controls
- **Notification Listener** for notification access
- **UsageStatsManager** for screen-time tracking
- **Expo config plugin** to integrate the native Android components

The Android application ID is currently `com.smartisland.app`. It has been kept unchanged to support updating an existing installation.

## 🚀 Getting Started

Meow Island is currently source-first. A successful build and correct behavior on a physical device still need to be verified in your environment.

### Prerequisites

- Node.js 20 or newer
- JDK 17
- Android Studio and Android SDK (including platform/build tools)
- An Android phone or emulator; a physical phone is recommended for overlay, media-session, notification, and usage-access testing

### Set up

1. Clone the repository:

   ```bash
   git clone https://github.com/arib-khan/MEOW-Island.git
   cd MEOW-Island
   ```

2. Install dependencies:

   ```bash
   npm install
   ```

3. Generate the Android native project:

   ```bash
   npx expo prebuild --platform android --clean
   ```

4. Run on a connected Android device:

   ```bash
   npx expo run:android
   ```

For detailed Windows instructions, Gradle build commands, first-run permissions, and troubleshooting, see **[`docs/SETUP.md`](docs/SETUP.md)**.

### Build an APK

On Windows PowerShell:

```powershell
cd android
.\gradlew.bat assembleRelease
```

On macOS/Linux:

```bash
cd android
./gradlew assembleRelease
```

If the build succeeds, the APK is normally generated at:

```text
android/app/build/outputs/apk/release/app-release.apk
```

The setup guide notes that the default release build may use a debug signing key. **Do not treat that as a production release configuration.** Configure a proper signing key before distributing a public release.

## 🔐 First-run permissions

Meow Island uses Android special access for its system-level features. Grant only the permissions needed for the features you want to use.

1. **Notifications** — for reminder notifications.
2. **Display over other apps** — required for the floating island.
3. **Notification Access** — needed for notification previews and access to media-session information exposed through Android.
4. **Usage Access** — needed for Screen Time Guardian.

After granting access, return to Meow Island and refresh permission status if prompted. Some Android versions and manufacturers add extra restrictions for sideloaded apps or background services.

## ⚠️ Current Status & Known Limitations

Meow Island is in an early development stage, and the following limitations are known:

- **Physical-device testing is incomplete.** Overlay positioning, touch handling, music controls, screen-time behavior, and background reliability still need systematic testing.
- **Full-screen video detection is best-effort.** Android does not expose one universal full-screen-video callback for every app. Portrait video, Shorts/Reels, and different system-bar behaviors may not be detected reliably.
- **Screen-time updates can be delayed.** Android usage events may arrive in batches. Reminder timing is not guaranteed to be exact.
- **Background behavior varies by device.** Battery optimization and manufacturer-specific task management may stop the island service. Force-stopping the app or revoking permissions also affects behavior.
- **Timers are basic.** Pause/resume controls are not implemented in the current version.
- **Lyrics depend on public databases.** LRCLIB and lyrics.ovh do not cover every song, language or version; lyrics.ovh is plain-text only and cannot verify versions, so version-marked titles are deliberately not sent to it. Video apps (YouTube and streaming video) never get lyrics.
- **Artwork crosses Bluetooth.** Cached covers and covers the display already holds appear immediately; the first transfer of a new 80x80 cover is bounded by the BLE link. The display keeps one cover in RAM, not a multi-cover cache.
- **Design scope is still evolving.** The current interface is dark-theme focused; some polish and setup flows may remain incomplete.
- **An APK is not provided by this README.** Build it locally using the setup guide, and verify the resulting build before installing or sharing it.

These limitations will be updated as features are tested and improved.

## 🧪 Testing Checklist

Testing contributions and device reports are especially helpful. When testing, include your Android version and phone model.

- [ ] Island appears over other apps after the required permission is granted.
- [ ] Dragging and saved position work after restarting the app.
- [ ] Island respects screen edges, display cutouts, and navigation mode.
- [ ] Music metadata updates and supported playback controls affect the real media session.
- [ ] Artwork rotation follows playback state.
- [ ] Notification previews work when access is granted.
- [ ] Screen-time limit behavior updates when entering and leaving a monitored app.
- [ ] Repeated screen-time reminders show current usage and the configured limit.
- [ ] Reminders and timers continue to behave as expected when the app is backgrounded.
- [ ] Full-screen video hiding works on the tested video app and device.

## 🛣️ Roadmap

- [ ] Verify builds and native behavior on physical Android devices.
- [ ] Improve overlay positioning and interaction across screen sizes and navigation modes.
- [ ] Validate media-session behavior with multiple music apps.
- [ ] Improve screen-time tracking, reminder timing, and lifecycle recovery.
- [ ] Test full-screen detection across popular video apps and document compatibility.
- [ ] Refine onboarding, accessibility, and error states.
- [ ] Prepare a reproducible release build and release checklist.

## 🤝 Contributing

Contributions, ideas, bug reports, and constructive feedback are welcome—especially while the project is still young.

1. Check existing [issues](https://github.com/arib-khan/MEOW-Island/issues) before opening a new one.
2. For bugs, include steps to reproduce, expected vs. actual behavior, Android version, and device model.
3. For feature ideas, explain the user problem the idea would solve.
4. For code changes, keep the scope focused and test the affected behavior when possible.

If you are planning a larger change, opening an issue first can help align on the approach.

## ⭐ Support the Project

Meow Island is at a **very early stage**, and there is a lot left to improve.

- ⭐ **Star the repository** if the idea interests you.
- 🐛 Report bugs and share what does not work on your device.
- 💡 Suggest features and UX improvements.
- 🔀 Contribute code or documentation if you would like to help build it.

**A star is a small gesture that helps an early project get noticed. Thank you for checking it out!**

## 📄 License

Meow Island is released under the [PolyForm Noncommercial License 1.0.0](LICENSE). You may use, study, modify and share it for **non-commercial purposes** (personal use, learning, research, hobby projects, and use by charitable, educational or public-safety organisations). **Commercial use is not permitted** without a separate written licence from the author. See the [`LICENSE`](LICENSE) file for the complete text and [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) for third-party material that keeps its own licence.

Versions of Meow Island that were already published under the MIT License before this change remain available under the MIT License to those who received them; this licence applies to the project from now on.

---

<div align="center">

  **Made with 🐾 and lots of iteration.**

  [⭐ Star Meow Island](https://github.com/arib-khan/MEOW-Island/stargazers) · [🐛 Report an issue](https://github.com/arib-khan/MEOW-Island/issues)

</div>
