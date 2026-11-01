# New display screens: screen time, notifications, custom HTML, music redesign

Read this together with `docs/DEVICE.md`. **The link between phone and display is Bluetooth LE** (not Wi-Fi: the display's Wi-Fi is only used to fetch the time). Everything here uses that existing link, pairing and reconnect logic (`DeviceLink.kt`); no second connection manager was added.

## Screens
| id | screen | data source |
|----|--------|-------------|
| 0 | Clock | unchanged |
| 1 | Steps | unchanged |
| 2 | Music (redesigned, no cat) | `MediaListener` metadata + album art + optional lyrics source |
| 3 | Screen time | Android UsageStats through the existing `UsageUtil` (phone usage, not the display's on-time) |
| 4 | Notifications | `MediaListener` (the existing `NotificationListenerService`), opt-in |
| 5 | Custom | user's HTML, rendered on the phone, stored on the display |

## Android permissions
No new permission was added. Used: `PACKAGE_USAGE_STATS` (already declared, user grants Usage Access in settings), Notification Access (already used), `INTERNET` (already declared; used only by the optional lyrics lookup to LRCLIB and lyrics.ovh, which is off by default). The HTML file is chosen with Android's document picker (`ACTION_OPEN_DOCUMENT`), which needs no permission.

## New dependencies
None (no npm or Gradle additions). The picker, WebView rendering and transfer are native Kotlin; LittleFS is part of the ESP32 Arduino core.

## Protocol additions (all little-endian; frame = `A5 | type | len | payload`, payload <= 200 bytes)
Unchanged: types 1-8, the 10-byte status. Screen ids 3-5 are new (`SCREEN` payload 0..5).

* `0x09 SCREENTIME`: `flags u8 | totalMin u16 | goalMin u16 | n u8 | n x (min u16 | nameLen u8 | name)`. flags: bit0 data, bit1 needs Usage Access, bit2 unavailable, bit3 goal on. At most 4 apps, names ASCII (14 chars), minutes <= 1440.
* `0x0A NOTIFS`: `flags u8 | count u8 | count x (ageMin u16 | appLen u8 app | titleLen u8 title | textLen u8 text)`, newest first, as many as fit one frame (app 12, title 24, text 48 chars). flags: bit0 feature on, bit1 needs Notification Access. A whole snapshot replaces the previous one. RAM only on the display.
* `0x0B MUSIC2`: `flags u8 | lyricState u8 | lineIdx u16 | srcLen u8 src | prevLen prev | curLen cur | nextLen next`. flags: bit0 album art on the display belongs to this track, bit1 lyrics are time-synced, bit2 the source is a video (no lyric zone; bottom progress bar). lyricState: 0 n/a, 1 loading, 2 available (synced lines), 3 unavailable, 4 provider off, 5 offline, 6 plain lyrics only (shown WITHOUT timing).
* `0x10 LYRIC`: `token u8 | seq u8 | line u16 | w u16 | h u16 | mask bytes` (224x80, 1 bit per pixel, MSB first, up to 190 bytes per chunk; `00 00` clears). The phone renders the three lyric lines with Android's text stack so Devanagari, Arabic/Urdu RTL, Korean, Japanese and other scripts display correctly; the firmware draws it only when `line == MUSIC2.lineIdx`.
* Image transfer, kind 0 = custom screen file, kind 1 = album art (raw RGB565 80x80, RAM only):
  * `0x0C IMG_BEGIN`: `xfer u8 | kind u8 | total u32 | crc32 u32 | chunk u16 | w u16 | h u16`
  * `0x0D IMG_DATA`: `xfer u8 | seq u16 | bytes` (up to 190 bytes per chunk), accepted strictly in order
  * `0x0E IMG_END`: `xfer u8`; `0x0F IMG_CTRL`: `op u8` (1 abort, 2 delete custom image, 3 clear art)
  * Image frames go through their own 20-frame queue on the display; lyric chunks have a separate 16-frame queue so the two never starve each other. Every other message type keeps "newest wins".
* Status gains 13 bytes (total 23; a 10-byte or 19-byte status from old firmware is still understood): `xferState u8 (0 idle,1 receiving,2 done,3 failed) | xferId u8 | nextSeq u16 | reason u8 | customCrc u32 | artCrc u32`. `customCrc` is the CRC-32 of the stored custom file (0 if none); `artCrc` is the CRC-32 of the album art in the display's RAM (0 if none), which lets the phone skip a repeated cover transfer.
  reasons: 1 bad header, 2 too large, 3 no space, 4 flash error, 5 CRC mismatch, 6 sequence/timeout, 7 invalid image, 8 no memory, 9 busy.

**Transfer rules.** Phone sends BEGIN, then a window of 8 chunks, advancing as `nextSeq` grows in the status; after 2.5 s without progress it rewinds to the device's `nextSeq` (or repeats BEGIN); after 6 stalls it gives up. The device acks DONE only after the whole file matched the CRC and passed structural validation, then atomically replaces the old file (`/custom.tmp` -> `/custom.mimg`). A BEGIN whose CRC equals the stored one answers DONE immediately and writes nothing. A failed or interrupted upload never touches the stored design. A silent transfer is dropped after 6 s; losing the link drops it at once.

**Image format (MIMG)**, `firmware/MeowIsland/mimg.h`: 16-byte header (`MI`, version 1, w, h, colours <= 256, data length), RGB565 palette, run-length data (long run / short run / literal tokens). 240x320 portrait (`TFT_ROTATION 0`). The encoder is `src/core/customscreen.ts` (exact when the design has <= 256 colours, otherwise colour depth is lowered step by step; no dithering); the device decodes 8 rows at a time and never holds a full bitmap.

## Custom HTML rendering and safety
`CustomRender.kt` loads the HTML from a string into a throw-away, off-screen `WebView` laid out at 240x320 CSS px: no JavaScript bridge, JavaScript off unless the user switches on "Allow scripts" for that design, no storage, no file/content access, network loads blocked (remote images/fonts/scripts do not render), all navigation refused, 8 s timeout, view destroyed afterwards. Limits: 256 KB HTML, 64 KB encoded picture. The HTML source and encoded image are kept in the app's private storage (`filesDir`) only and re-sent automatically after reconnecting if the display does not have the exact design.

## Music screen
Layout: source pill (SPOTIFY / AMAZON MUSIC / YOUTUBE / app name) with a play/pause glyph, 80x80 album art (placeholder tile when none), title (scrolls if long), artist, progress bar with times, lyric zone. The dancing cat was removed **from the device's music screen only**; the Android home-screen "Dancing Cat" widget and `cat_assets.h` are untouched because other screens and the widget still use them. The TFT has no touch input, so playback controls stay on the phone.

The complete architecture, provider rules, matching, multilingual rendering and artwork cache are documented in **[`docs/MUSIC.md`](MUSIC.md)**. In short:

* **Detection:** Android MediaSession through the existing `MediaListener`, normalised into one provider-independent `NowPlaying` model. Works for Spotify, Amazon Music, Apple Music, Tidal, Deezer, JioSaavn, local players and anything else that publishes a media session. No per-app code, no second service.
* **Lyrics:** opt-in system with two legitimate providers and a fallback chain: LRCLIB (time-synced + plain, search results matched by title/artist/album/length **and version markers** so a live/remix/cover never shows the wrong lyrics) then lyrics.ovh (plain text). Synced lyrics follow playback; plain lyrics are paginated without pretending to be in time; no lyrics gets an honest status line. Nothing is translated, generated or scraped. Lyrics are looked up for every music app, not just Spotify.
* **Languages:** the phone renders the three visible lines into a 1-bit mask with Android's text stack (shaping, bidi, font fallback), so Devanagari, Urdu/Arabic right-to-left, Korean, Japanese and other scripts display correctly on the ASCII-only TFT. The firmware draws the mask in the existing lyric-zone geometry/colours; the ASCII text fields remain only as a fallback for older phone builds.
* **Album art:** stable cache key (package + track id + normalised title/artist/album), memory LRU + bounded disk cache, background decoding to 80x80 RGB565, instant reuse on replay, and a CRC in the device status so a cover the display already holds is never re-transferred. A placeholder tile is shown while fetching; a failed/partial transfer never leaves a half image.
* **Video (YouTube and other video apps):** **no lyrics and no captions** (caption extraction was removed with its accessibility service, manifest entry, settings switch and protocol states 7/8). The screen shows only the thumbnail, title, duration, elapsed time and a progress bar at the bottom, updated from the real playback position. Media detection and playback controls are unchanged.

## Tests
* `NODE_PATH=$(npm root -g) node tests/run.js` : JS logic (device state, screen-time rules, image codec, PNG preview, file checks) and static guards for the music work (no caption service, no Spotify-only lyrics gate, artwork cache, video mode).
* `sh tests/kotlin/run_device.sh` (needs `kotlinc`): Kotlin protocol/LRC/multilingual-matcher/provider-fallback/sender tests; rewrites `tests/device_vectors.txt`.
* `sh firmware/host-tests/run.sh` (needs g++, git, Pillow; downloads Adafruit GFX): protocol vectors (including `MSG_LYRIC` and the 23-byte status), image codec + receiver (sanitizers), clock regression, sketch smoke tests (lyric mask pixels, video bottom bar, art CRC skip).
