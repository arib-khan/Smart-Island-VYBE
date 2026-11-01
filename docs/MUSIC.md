# Music on Meow Island: detection, lyrics and artwork

This is the detailed companion to `docs/NEW_SCREENS.md` and `docs/DEVICE.md`. It describes what the phone detects, where lyrics
come from, how artwork is cached and transferred, and what the TFT firmware does with all of it.

## 1. Detection is provider-independent

The only supported source is Android's own **MediaSession** (`MediaSessionManager` / `MediaController`), read by the existing
notification-listener service (`MediaListener.kt`). That is the same mechanism Spotify, Amazon Music, Apple Music, Tidal, Deezer,
JioSaavn, Poweramp, VLC, YouTube Music and other players implement. Meow Island does **not** read another app's private storage,
scrape its UI or capture audio.

`MediaListener` normalises a session into one model, `NowPlaying` (`NowPlaying.kt`):

| field | Android key / source | notes |
|---|---|---|
| package / app label | `MediaController.packageName` | used for the source pill and video detection |
| title | `METADATA_KEY_TITLE`, `DISPLAY_TITLE` | required before anything is shown |
| artist | `METADATA_KEY_ARTIST`, `ALBUM_ARTIST`, `DISPLAY_SUBTITLE` | optional |
| album | `METADATA_KEY_ALBUM` | optional; improves lyrics matching and the cache key |
| track id | `METADATA_KEY_MEDIA_ID`, `MEDIA_URI` | optional; part of the stable cache key |
| duration | `METADATA_KEY_DURATION` | 0 when the player does not expose it |
| position | `PlaybackState.position` + elapsed time | clamped to the duration |
| playing | `PlaybackState.STATE_PLAYING` | buffering is not claimed as playing |
| previous / next | `ACTION_SKIP_TO_PREVIOUS` / `ACTION_SKIP_TO_NEXT` | the island/widget controls use these |
| artwork | `METADATA_KEY_ALBUM_ART`, `ART`, `DISPLAY_ICON` | never fetched from a website |

The island card, the Dancing Cat home-screen widget and the ESP32 link all consume this one model. There is no per-app code path, no
duplicate listener and no second service. A player that exposes only a title still works; fields it does not publish stay empty.

*Video* apps are recognised by package (`Provider.isVideo`): YouTube, YouTube Music, Netflix, Prime Video, Disney+, etc. They are
shown like any other session (thumbnail / title / duration / progress) but are excluded from lyrics. The phone sets the MUSIC2
`video` flag so the firmware hides the lyric zone and draws the bottom progress bar.

## 2. Lyrics

### 2.1 Why the old feature only worked for some Spotify songs

The previous implementation retrieved lyrics only when the media session's package was Spotify. That gate had no technical reason:
Android's media session does not carry lyrics for any player, and Spotify has no public lyrics API. The actual causes of the gaps
were:

1. **The provider gate.** Amazon Music, YouTube Music, local players and every other app were marked `LY_NA` before any lookup.
2. **A single source with no fallback.** LRCLIB was the only provider; a missing entry, a 404 or a busy server ended the lookup.
3. **Search results were not matched to the track.** The old `pick()` took the first search result with synced lyrics within 8 s of
   the reported length, without comparing the title, artist or album. That can return a different version — and could return nothing
   when the duration was unknown.
4. **Plain lyrics were thrown away.** A source with only plain text was reported as "no synced lyrics", so the display showed a
   status line instead of the lyrics it had.
5. **The TFT cannot draw non-ASCII text.** `DeviceProtocol.ascii()` correctly stripped Devanagari, Arabic, CJK and emoji because the
   Adafruit GFX fonts are ASCII-only. That is why non-Latin lyrics could never appear, regardless of the provider.

### 2.2 Providers and fallback

`LyricsService` (`LyricsLogic.kt`) tries providers in order. A time-synchronised answer wins immediately; a plain answer is kept
and a later synced-capable provider may replace it; a plain-only provider is skipped once a plain answer exists.

| provider | what it returns | authentication / terms | used when |
|---|---|---|---|
| **LRCLIB** (`lrclib.net`) | synced LRC and/or plain text; title/artist/album/duration metadata on search results | free public API, documented at `/docs`; identifiable `User-Agent`; requests serialised with a 300 ms gap; `Retry-After` on 429 honoured; no account | first |
| **lyrics.ovh** (`api.lyrics.ovh`) | plain text only; no metadata about the match | free public API; no key; one request per lookup | fallback when LRCLIB has no answer or is unreachable |

Both are opt-in. With the switch off, nothing is sent anywhere and the display says "Lyrics are off". Only the track's title,
artist, album and duration are sent — never notification content, account data or device identifiers.

The chain returns:

* `LY_AVAILABLE` — real timestamped lines (only LRCLIB can produce this);
* `LY_UNSYNCED` — plain lines, shown **without** claiming they are in time;
* `LY_UNAVAILABLE` — every source answered "no such song";
* `LY_OFFLINE` — no source could be reached at all (network failure / server busy);
* `LY_PROVIDER_OFF` — the user has not switched lyrics on.

A 404/400 answer is never reported as "offline". A 60 s retry window applies to network failures only, so a temporary outage does
not permanently cache a miss.

### 2.3 Accurate matching

`LyricsMatcher` compares every search candidate with the playing track:

* **Title**: Unicode-preserving normalisation (NFC, case folding, punctuation), bracketed extras and " - ..." decorations removed,
  then token Jaccard plus character bigrams (so CJK/Devanagari titles that do not use spaces still compare). A candidate below the
  title threshold is discarded.
* **Artist**: collaborations are split on `, & ; feat ft x and`; an exact shared artist wins, otherwise the best pair similarity is
  used. A different artist (a cover band) is rejected when the title alone would otherwise match.
* **Album**: a bonus signal when both sides expose one.
* **Length**: exact within 3 s, decaying to 0 at 90 s; unknown lengths are neutral rather than disqualifying. A difference of more
  than two minutes is rejected even when title and artist match, because that is an extended/live edit rather than the same recording.
* **Version markers**: `remix`, `rmx`, `live`, `acoustic`, `unplugged`, `cover`, `karaoke`, `instrumental`, `demo`, `nightcore`,
  `sped up`, `slowed`, `8d`, `mashup`, `medley`, `reprise`, `extended`, `radio edit`, `orchestral`, `piano version`, `lullaby`,
  `female/male version`, `duet`, `tribute` and `parody` must match exactly between the playing title and the candidate. A live or
  remix candidate is never accepted for the studio track, and vice versa. `remaster`, `deluxe`, `explicit` and `clean` are *not*
  treated as different lyrics.
* `instrumental` candidates are rejected unless the playing title says instrumental.

`lyrics.ovh` returns no metadata, so it is skipped for titles that themselves carry a version marker. That is a deliberate,
honest limitation: it cannot prove it answered the right version. LRCLIB, which does carry metadata, handles those tracks.

### 2.4 Synchronised and plain lyrics

* **Synced**: `Lrc.parse` only accepts text with at least two real `[mm:ss.xx]` timestamps. The current line follows the playback
  position; the previous and next line are shown in the existing dim/bright/dim style. Metadata tags (`[ar:]`, `[ti:]`) are skipped.
* **Plain**: lines are cleaned (HTML tags removed, `&amp;`-style entities decoded, section labels dropped, blank runs collapsed) and
  shown in a three-line window that advances one line every six seconds of playback. It is **pagination, not synchronisation**: the
  MUSIC2 `synced` flag stays false and the state stays `LY_UNSYNCED`.
* **No lyrics**: a short honest status line ("No lyrics found for this track").
* Nothing is translated, transliterated or generated. The text is exactly what the provider returned.

### 2.5 Multilingual rendering

The TFT's fonts are ASCII-only, so the phone renders the three visible lines into a **1-bit mask** (`LyricBandRenderer.kt`,
224x80, 28 bytes per row) and sends it in `MSG_LYRIC` chunks. Android's text stack performs shaping and bidi ordering, so:

* Devanagari conjuncts and matras render correctly (the text is never NFD-stripped);
* Urdu and Arabic are shaped and ordered right-to-left; mixed lines get Unicode bidi ordering;
* Korean, Japanese and Chinese use the phone's system font fallback;
* line wrapping is done by `StaticLayout` with high-quality break strategy, honouring the 224 px width;
* the current line wraps to at most two lines, the previous/next lines are ellipsised, and the existing lyric-zone geometry and
  colours are preserved.

The mask is tied to the MUSIC2 line index: the firmware draws it only when `lyricLine == MUSIC2.lineIdx`, so a late burst from the
previous line can never appear as the current one. A new track clears the mask immediately. If the mask is not available yet (or the
installed phone app is older), the firmware falls back to the ASCII text fields — the old behaviour, including its script limits.

The font coverage therefore follows the phone's system fonts. Every language the phone can display (all languages supported by the
lyrics providers) renders; a language whose glyphs are missing from the phone's own fonts cannot be invented by the display, and
this is noted in the limitations.

## 3. Album artwork

### 3.1 Why it used to be slow

1. **No cache.** Every track — including a replay — re-read the bitmap from the media session, re-scaled it and re-sent 12 800 bytes
   over BLE.
2. **Repeated scaling.** `MediaListener.pushMedia()` runs on every metadata/playback update; the old code built a new 96x96 bitmap
   each time.
3. **The transfer started late.** The phone waited for a sync tick after the track changed, then encoded on the UI thread, then ran
   the ack/window image protocol (72 chunks at the old 180-byte chunk size).
4. **No way to skip a cover the device already had.** The device's art buffer is RAM-only, so the phone always transferred again
   after an app switch or reconnect.
5. **The artwork tile was cleared on every new transfer.** That is correct (the old cover must not be shown for the new song), but
   combined with the slow transfer it meant a long placeholder.

### 3.2 The pipeline now

```
MediaSession metadata
        │  (only when the bitmap object changes)
        ▼
96x96 bitmap (island/widget)  ──►  ArtCache (background thread)
                                        │  memory LRU (16)
                                        │  disk LRU  (<= 120 files / 6 MB,
                                        │  files named SHA-256(package|trackId|title|artist|album))
                                        ▼
                              80x80 RGB565 bytes (12 800)
                                        │
                    CRC-32 compared with the device status
                                        │
                     ┌──────────────────┴──────────────────┐
                 equal: no transfer                 different: ImageSender
                 (instant, RAM already has it)     90-byte→190-byte chunks
```

* **Stable key**: package + track id (when exposed) + NFC-normalised title/artist/album. Punctuation and spacing are normalised,
  case is folded, and the track id makes two different tracks with the same title distinct.
* **Instant replay**: memory hit → the transfer starts on the next link tick; disk hit → the bytes are read on the background
  thread. Nothing is decoded again.
* **No repeated transfer**: the device status now carries `artCrc` (the CRC of the art in its RAM). If it equals the cached image's
  CRC, the phone marks the cover as present and sends no image data. This survives app switches and reconnects while the display
  keeps the image in RAM.
* **Non-blocking**: scaling, PNG/JPEG-free RGB565 conversion and disk I/O run on `ArtCache`'s single background thread. The UI and
  the BLE queue never wait for them.
* **Stale-cover protection**: the phone tracks the artwork revision and the current track key. A completed transfer from the previous
  track updates only that key; `hasArt` stays false for the new track, so the display keeps its placeholder. The firmware also clears
  its art flag when the title changes, and the new transfer begins from scratch.
* **Failures**: a failed request (HTTP/decoder/BLE) sets `artKeyFailed` and is not retried in a loop; a lost link or an aborted
  transfer (reason 0) is retried after reconnecting. The placeholder tile stays visible, and the image is replaced in one go when
  the transfer completes.
* **Faster transfer**: `IMG_CHUNK` is now 190 (the firmware's maximum) instead of 180, and the artwork CRC skip avoids the transfer
  entirely for repeats. The image is displayed only after the complete CRC-checked transfer, never partially.

### 3.3 Display experience

* The note tile is the placeholder from the first frame; it is never a blank rectangle.
* A cached cover starts transferring immediately on track change.
* The actual bitmap replaces the tile only after the full image arrived and its CRC matched.
* No animations or effects were added.

## 4. YouTube and video sources

Caption/subtitle extraction was **removed**, not disabled:

* `MeowCaptionService.kt` and `meow_caption_config.xml` are deleted;
* its manifest registration, app switch, accessibility text, native bridge methods and `CaptionBuffer` are gone;
* the protocol no longer defines caption states 7/8, and the music screen no longer references Live Caption.

Media detection and playback controls are unchanged. While a video session plays (YouTube, YouTube Music and other video apps):

* the thumbnail is the session artwork, with the same placeholder if the app exposes none;
* title and duration are shown when the app publishes them;
* progress is calculated from `PlaybackState` and shown as elapsed / total;
* the progress bar is drawn at the bottom of the screen, updating once per second (and immediately on seek/pause);
* the lyric zone is not drawn at all — no lyrics, no captions, no status text.

The phone still emits the regular `MUSIC`/`MUSIC2` data (`video` flag, `lyricState = 0`), so older firmware that ignores the flag
would simply show no lyrics state; it would not receive captions either.

## 5. ESP32 link

No new connection, service or listener was added. All messages travel on the existing encrypted BLE link.

* **MUSIC2** now uses flags bit 2 = video. `lyricState` is 0..6; state 6 means plain lyrics shown without timing.
* **MUSIC / MUSIC2** are still sent only when their content changes (plus a 10 s progress refresh and a 30 s keepalive). Seeking
  triggers an immediate update.
* **MSG_LYRIC (0x10)**: `[token u8][seq u8][line u16][w u16][h u16][mask bytes]`, up to 190 bytes per frame, 224x80 mask,
  MSB-first rows. A two-byte `00 00` payload clears the mask. It has its own 16-frame queue on the device so an image transfer
  cannot make lyrics stutter. A burst that stalls for 3 s is dropped.
* **Status** grows from 19 to 23 bytes with `artCrc u32`. Older 10/19-byte statuses are still parsed; missing fields read as 0.
* **Image transfer** chunk maximum is now 190 bytes (custom screens benefit too). The custom-screen file protocol, CRC checks,
  LittleFS atomic replacement and reconnect behaviour are unchanged.
* **Progress** is still extrapolated from the last reported position on the device, so BLE traffic does not need to carry every
  second. Pauses, track changes, app switches, Wi-Fi/Bluetooth loss and reconnects are handled by the existing state machine:
  a reconnect forces `MUSIC`, `MUSIC2`, the lyric mask and (when needed) the artwork to be sent again.

## 6. Limitations that are real

* **Lyrics availability** depends on the public databases. LRCLIB applies rate limits and offers no commercial SLA; lyrics.ovh is
  plain-only. Neither can cover every song, language or version.
* **lyrics.ovh matching** has no metadata; version-marked titles are therefore not sent to it. A provider with verifiable metadata
  (LRCLIB) is always preferred. Some community entries are transliterations (for example romanised Hindi/Japanese); explicitly marked
  ones are rejected, but an unmarked transliteration cannot be detected from the text alone. It is still the provider's own text,
  never translated or generated by Meow Island.
* **Phone fonts**: the mask renders exactly what Android can shape. A language whose fonts are absent from a particular phone
  cannot be displayed; Meow Island does not bundle fonts. This is a device-font limitation, not a transcoding bug.
* **Titles/artist/notifications** still use the TFT's ASCII fonts and are simplified (the existing `ascii()` behaviour). Only
  lyrics are rendered as a mask.
* **First-time artwork** still crosses BLE (about 12.8 KB). Cached replays and covers the display already holds are instant; the
  first transfer is bounded by the link, not by decoding or the network.
* **Artwork RAM** on the device is one cover (80x80), not a multi-cover cache. The phone remembers many covers on disk.
* **Provider metadata gaps**: some players publish no artist, duration or position. The UI degrades honestly (no invented values)
  and lyrics matching falls back to what is available.
* **Artwork is only what the player exposes.** Meow Island does not scrape cover-art websites; a player that publishes no artwork
  gets the placeholder.

## 7. Verification

* `node tests/run.js` — pure JS tests, plus static checks that no caption service remains, that lyrics are not Spotify-gated, that
  the artwork cache/status path exists and that the firmware has the video mode.
* `sh tests/kotlin/run_device.sh` — LRC parsing, multilingual plain lyrics, version-aware matching, provider fallback, protocol
  frames and the image sender against a lossy simulated link. It also regenerates `tests/device_vectors.txt`.
* `sh firmware/host-tests/run.sh` — protocol vectors (now including `MSG_LYRIC` and the 23-byte status), the image codec/receiver
  under sanitizers, clock regression and the full sketch smoke test (lyric mask pixels, video bottom bar, art CRC skip, new-track
  clearing).
* Android build: `npx expo prebuild --platform android` then `android/gradlew.bat :app:compileDebugKotlin` / `assembleDebug`.
  This compiles every Kotlin file in `plugins/kotlin`, including the new model, cache, renderer and providers.
