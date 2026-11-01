# Meow Island Device (ESP32 display) — app side

## Where it lives
Settings → **Meow Island Device** (a card at the top of Settings opens the new section; the tab bar and every other screen are unchanged).

## Architecture
* **Bluetooth LE, native Kotlin** (`DeviceLink.kt`, bridged by `DeviceModule.kt`). No new npm dependency, and it keeps syncing in the background because it is started
  by the same long-lived services as the island (`IslandService`, `MediaListener`, `BootReceiver`) and by the app.
* **Pairing**: Android's own bonding with a passkey shown on the display. Needs Android 6+. Permissions: *Nearby devices* (Android 12+) or *Location* (11 and older, scan only).
* **Reconnect**: 3 s, 6 s, 12 s, 30 s retries, then Android's background auto-connect; Bluetooth switched off/on is handled; the phone only remembers the device address and name.
* **Data**: nothing is streamed as images except the small artwork/lyric masks the display asks for. The phone sends small frames and the ESP32 draws everything itself.
  * Steps: `MeowWidgets.stepCardData()` — the very data the home-screen Step goals widget uses (steps, goal, heat-map levels, wording codes). Sent when it changes.
  * Music: `MeowMusic.state()` + `MediaListener.current` (one provider-independent `NowPlaying` model). Title/artist/position are sent on track/state change; position re-synced every 10 s and on seek (the display counts seconds itself). The album cover is a cached 80x80 RGB565 image sent once per track (the status CRC lets the phone skip a cover the display already holds). Lyrics are three lines rendered to a 1-bit mask on the phone and sent when the line changes; sync/plain state and the text fallback travel in MUSIC2. See **[`docs/MUSIC.md`](MUSIC.md)**.
  * Time/timezone on connect and every 30 min (so the clock works without Wi-Fi).
* **Wi-Fi** is optional and set from the app (password goes straight into one encrypted frame; not stored on the phone).

## Frame format (all little-endian)
`0xA5 | type | len | payload`, any number of frames per Bluetooth write, one frame may span writes.
| type | payload |
|---|---|
| 01 HELLO | proto u8 |
| 02 SCREEN | 0 clock, 1 steps, 2 music |
| 03 STEPS | steps u32, goal u32, flags u8 (1 has, 2 goal on, 4 heat on, 8 happy), label u8, hint u8, subtitle u8, 27 bytes = 54 heat levels (nibbles) |
| 04 MUSIC | flags u8 (1 session, 2 playing), pos s u16, dur s u16, titleLen u8 + ASCII, artistLen u8 + ASCII |
| 05 WIFI_SET | ssidLen u8, ssid (1–32), passLen u8, pass (0 or 8–63) |
| 06 WIFI_CLEAR | — |
| 07 UNPAIR | flags (1 = also forget Wi-Fi) |
| 08 TIME | epoch u32, tz minutes i16 |
| 0B MUSIC2 | flags u8 (1 art present, 2 lyrics synced, 4 video), lyricState u8 (0..6), lineIdx u16, source/prev/cur/next ASCII strings |
| 10 LYRIC | token u8, seq u8, line u16, w u16 (224), h u16 (80), 1-bit mask bytes (up to 190 per frame); `00 00` clears |

Status (device → phone, read + notify, 23 bytes; 10-byte and 19-byte statuses from older firmware are still accepted): proto, fw major, fw minor, screen, wifi state (0 none/1 connecting/2 connected/3 failed), wifi reason, flags (1 time valid, 2 pairing open, 4 has Wi-Fi, 8 steps fresh, 16 music fresh), bonds, free heap KB, reserved, xfer state/id/next/reason, custom CRC, album-art CRC. Full definitions are in `firmware/MeowIsland/protocol.h`; `docs/NEW_SCREENS.md` lists the additions and `docs/MUSIC.md` explains the lyric mask and artwork cache.

## Platform limits (be aware)
* **Android only.** The project is Android-focused (Kotlin services); there is no iOS implementation.
* The phone cannot give the ESP32 health data or music by itself: steps need the Health tab's Step counter on (and the Physical activity permission), music needs Notification access (as for the music widget).
* The display's own fonts have plain ASCII only: accents are stripped and **Devanagari/CJK/emoji titles are dropped** (an all-non-Latin title shows as “Playing”). Lyrics are the exception: the phone renders them into a bitmap, so any script the phone can shape (including right-to-left Urdu/Arabic) appears correctly. Playback buttons stay on the phone because the TFT is not touch.
* Android keeps a bond after the device forgets it. After **Unpair** (or a 10 s reset) remove “MeowIsland-…” in Android Bluetooth settings before pairing again; the app detects repeated failures and says so.
* Some phone makers aggressively stop background apps; if sync stops, allow Meow Island to run in the background (battery settings).
