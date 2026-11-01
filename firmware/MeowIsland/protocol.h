// Meow Island link protocol (phone <-> ESP32 over BLE GATT). Pure C++: no Arduino or BLE headers, so it is unit-tested on a desktop
// (firmware/host-tests). The same byte layout is implemented in plugins/kotlin/DeviceProtocol.kt; tests/device_vectors.txt checks both
// sides against each other. All integers are little-endian.
//
//   frame   = 0xA5 | type | len | payload[len]            (len <= 200; any number of frames may be split across / packed into BLE writes)
//   status  = 10 fixed bytes + 13 transfer bytes, read / notify (device -> phone), see encodeStatus() / encodeStatusExt()
#pragma once
#include <stdint.h>
#include <stddef.h>
#include <string.h>

namespace meow {

constexpr uint8_t  PROTO_VERSION = 1;
constexpr uint8_t  FRAME_MAGIC   = 0xA5;
constexpr uint8_t  MAX_PAYLOAD   = 200;
constexpr uint32_t FRAME_TIMEOUT_MS = 2000;     // a half-received frame older than this is dropped

enum MsgType : uint8_t {
  MSG_HELLO = 0x01,       // [proto u8]
  MSG_SCREEN = 0x02,      // [screen u8: 0 clock, 1 steps, 2 music]
  MSG_STEPS = 0x03,       // see StepsData
  MSG_MUSIC = 0x04,       // see MusicData
  MSG_WIFI_SET = 0x05,    // [ssidLen u8][ssid][passLen u8][pass]
  MSG_WIFI_CLEAR = 0x06,  // (empty)
  MSG_UNPAIR = 0x07,      // [flags u8: bit0 also forget Wi-Fi]
  MSG_TIME = 0x08,        // [epoch u32][tzMinutes i16]
  // ---- protocol additions (screen time, notifications, music extras, image transfer) ----
  MSG_SCREENTIME = 0x09,  // see ScreenTimeData
  MSG_NOTIFS = 0x0A,      // see NotifData
  MSG_MUSIC2 = 0x0B,      // see Music2Data (source name, lyric lines)
  MSG_IMG_BEGIN = 0x0C,   // see ImgBegin       (image types 0x0C..0x0F are queued, never "latest wins")
  MSG_IMG_DATA = 0x0D,    // [xfer u8][seq u16][bytes]
  MSG_IMG_END = 0x0E,     // [xfer u8]
  MSG_IMG_CTRL = 0x0F,    // [op u8: 1 abort transfer, 2 delete custom image, 3 clear album art]
  MSG_LYRIC = 0x10,       // see LyricChunk     (queued chunks of the phone-rendered lyric mask)
};
constexpr uint8_t MSG_LAST = 0x10;

enum Screen : uint8_t { SCREEN_CLOCK = 0, SCREEN_STEPS = 1, SCREEN_MUSIC = 2, SCREEN_SCREENTIME = 3, SCREEN_NOTIFS = 4, SCREEN_CUSTOM = 5, SCREEN_COUNT = 6 };
enum WifiState : uint8_t { WIFI_NONE = 0, WIFI_CONNECTING = 1, WIFI_CONNECTED = 2, WIFI_FAILED = 3 };
enum WifiReason : uint8_t { REASON_NONE = 0, REASON_NOT_FOUND = 1, REASON_AUTH = 2, REASON_TIMEOUT = 3, REASON_OTHER = 4 };

constexpr int STEP_CELLS = 54;   // 9 x 6 heatmap, oldest first, last = today (same as StepCardRenderer.CELLS)

struct Frame { uint8_t type; uint8_t len; uint8_t payload[MAX_PAYLOAD]; };

// Byte-stream reassembly. Feed it every byte of every BLE write; returns true when a whole frame is ready in `out`.
class FrameAssembler {
 public:
  FrameAssembler() { reset(); }
  void reset() { st_ = 0; n_ = 0; }
  // Drops a stalled partial frame (call from the main loop with millis()).
  void expire(uint32_t now) { if (st_ != 0 && (uint32_t)(now - last_) > FRAME_TIMEOUT_MS) reset(); }
  bool push(uint8_t b, uint32_t now, Frame& out) {
    last_ = now;
    switch (st_) {
      case 0: if (b == FRAME_MAGIC) st_ = 1; return false;            // anything else = noise, wait for the next magic byte
      case 1: cur_.type = b; st_ = 2; return false;
      case 2:
        if (b > MAX_PAYLOAD) { reset(); return false; }               // impossible length: resynchronise
        cur_.len = b; n_ = 0;
        if (b == 0) { out = cur_; reset(); return true; }
        st_ = 3; return false;
      default:
        cur_.payload[n_++] = b;
        if (n_ >= cur_.len) { out = cur_; reset(); return true; }
        return false;
    }
  }
 private:
  Frame cur_{}; uint8_t st_ = 0; uint8_t n_ = 0; uint32_t last_ = 0;
};

// ---- little-endian helpers ----
inline uint16_t rd16(const uint8_t* p) { return (uint16_t)(p[0] | (p[1] << 8)); }
inline uint32_t rd32(const uint8_t* p) { return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24); }
inline void wr16(uint8_t* p, uint16_t v) { p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); }
inline void wr32(uint8_t* p, uint32_t v) { p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); p[2] = (uint8_t)(v >> 16); p[3] = (uint8_t)(v >> 24); }

// ---- STEPS: the data behind the phone's "Step goals" card (StepCardData) ----
// [steps u32][goal u32][flags u8][label u8][hint u8][subtitle u8][levels: 27 bytes = 54 nibbles, low nibble first]
struct StepsData {
  uint32_t steps = 0, goal = 0;
  bool hasSteps = false, goalOn = false, heatOn = false, happy = false;
  uint8_t label = 0;      // 0 "Today's Steps", 1 "Step counter is off"
  uint8_t hint = 0;       // 0 none, 1 "Turn it on in Meow Island > Health", 2 "Allow Physical activity in Health", 3 "Waiting for the first step reading"
  uint8_t subtitle = 0;   // 0 "Every step counts", 1 "Keep moving", 2 "Goal reached!"
  uint8_t levels[STEP_CELLS] = {0};   // 0..4
};
constexpr uint8_t STEPS_LEN = 4 + 4 + 4 + 27;
inline bool parseSteps(const uint8_t* p, uint8_t n, StepsData& o) {
  if (n != STEPS_LEN) return false;
  o.steps = rd32(p); o.goal = rd32(p + 4);
  if (o.goal > 10000000UL) return false;
  uint8_t f = p[8];
  o.hasSteps = f & 1; o.goalOn = f & 2; o.heatOn = f & 4; o.happy = f & 8;
  o.label = p[9] > 1 ? 0 : p[9];
  o.hint = p[10] > 3 ? 0 : p[10];
  o.subtitle = p[11] > 2 ? 0 : p[11];
  for (int i = 0; i < STEP_CELLS; i++) { uint8_t b = p[12 + i / 2]; uint8_t v = (i & 1) ? (b >> 4) : (b & 15); o.levels[i] = v > 4 ? 0 : v; }
  return true;
}

// ---- MUSIC: [flags u8: bit0 session, bit1 playing][pos s u16][dur s u16][titleLen u8][title][artistLen u8][artist] ----
constexpr uint8_t MAX_TITLE = 48, MAX_ARTIST = 40;
struct MusicData {
  bool session = false, playing = false;
  uint16_t posSec = 0, durSec = 0;
  char title[MAX_TITLE + 1] = "";
  char artist[MAX_ARTIST + 1] = "";
};
// Copies printable ASCII only (the TFT fonts have nothing else); anything else becomes a space and runs of spaces collapse.
inline void copyAscii(char* dst, size_t cap, const uint8_t* src, size_t n) {
  size_t o = 0; bool lastSpace = true;
  for (size_t i = 0; i < n && o + 1 < cap; i++) {
    uint8_t c = src[i];
    if (c < 0x20 || c > 0x7E) c = ' ';
    if (c == ' ') { if (lastSpace) continue; lastSpace = true; } else lastSpace = false;
    dst[o++] = (char)c;
  }
  while (o > 0 && dst[o - 1] == ' ') o--;
  dst[o] = 0;
}
inline bool parseMusic(const uint8_t* p, uint8_t n, MusicData& o) {
  if (n < 7) return false;
  o.session = p[0] & 1; o.playing = p[0] & 2;
  o.posSec = rd16(p + 1); o.durSec = rd16(p + 3);
  uint8_t tl = p[5];
  if (tl > MAX_TITLE || (size_t)6 + tl + 1 > n) return false;
  copyAscii(o.title, sizeof(o.title), p + 6, tl);
  uint8_t al = p[6 + tl];
  if (al > MAX_ARTIST || (size_t)7 + tl + al != n) return false;
  copyAscii(o.artist, sizeof(o.artist), p + 7 + tl, al);
  return true;
}

// ---- WIFI_SET ----
struct WifiCreds { uint8_t ssidLen = 0, passLen = 0; char ssid[33] = ""; char pass[64] = ""; };
// SSID 1..32 bytes; password empty (open network) or 8..63 bytes (WPA). Anything else is rejected, never truncated.
inline bool parseWifi(const uint8_t* p, uint8_t n, WifiCreds& o) {
  if (n < 2) return false;
  uint8_t sl = p[0];
  if (sl < 1 || sl > 32 || (size_t)2 + sl > n) return false;
  uint8_t pl = p[1 + sl];
  if ((pl != 0 && pl < 8) || pl > 63 || (size_t)2 + sl + pl != n) return false;
  memcpy(o.ssid, p + 1, sl); o.ssid[sl] = 0; o.ssidLen = sl;
  memcpy(o.pass, p + 2 + sl, pl); o.pass[pl] = 0; o.passLen = pl;
  for (uint8_t i = 0; i < sl; i++) if (o.ssid[i] == 0) return false;
  return true;
}

// ---- TIME ----
struct TimeData { uint32_t epoch = 0; int16_t tzMin = 0; };
inline bool parseTime(const uint8_t* p, uint8_t n, TimeData& o) {
  if (n != 6) return false;
  o.epoch = rd32(p); o.tzMin = (int16_t)rd16(p + 4);
  if (o.epoch < 1700000000UL || o.epoch > 4102444800UL) return false;       // Nov 2023 .. 2100: reject garbage
  if (o.tzMin < -14 * 60 || o.tzMin > 14 * 60) return false;
  return true;
}

// POSIX TZ string for a fixed offset, e.g. +330 -> "<+0530>-5:30". `out` needs 16 bytes.
inline void tzString(int16_t tzMin, char* out) {
  int a = tzMin < 0 ? -tzMin : tzMin; int h = a / 60, m = a % 60;
  char sign = tzMin < 0 ? '-' : '+';
  // POSIX offsets are written west-positive, so the sign flips after the colon form.
  int n = 0;
  out[n++] = '<'; out[n++] = sign; out[n++] = '0' + h / 10; out[n++] = '0' + h % 10; out[n++] = '0' + m / 10; out[n++] = '0' + m % 10; out[n++] = '>';
  out[n++] = tzMin < 0 ? '+' : '-';
  if (h >= 10) out[n++] = '0' + h / 10;
  out[n++] = '0' + h % 10;
  if (m) { out[n++] = ':'; out[n++] = '0' + m / 10; out[n++] = '0' + m % 10; }
  out[n] = 0;
}

// ---- STATUS (device -> phone), 10 bytes. Never contains Wi-Fi credentials or the SSID. ----
constexpr uint8_t STATUS_LEN = 10;
struct Status {
  uint8_t screen = 0, wifiState = 0, wifiReason = 0, bonds = 0, heapKb = 0, fwMajor = 1, fwMinor = 0;
  bool timeValid = false, pairingOpen = false, hasWifi = false, stepsFresh = false, musicFresh = false;
};
inline void encodeStatus(const Status& s, uint8_t* o) {
  o[0] = PROTO_VERSION; o[1] = s.fwMajor; o[2] = s.fwMinor; o[3] = s.screen; o[4] = s.wifiState; o[5] = s.wifiReason;
  o[6] = (s.timeValid ? 1 : 0) | (s.pairingOpen ? 2 : 0) | (s.hasWifi ? 4 : 0) | (s.stepsFresh ? 8 : 0) | (s.musicFresh ? 16 : 0);
  o[7] = s.bonds; o[8] = s.heapKb; o[9] = 0;
}


// =====================================================================================================================================
// Protocol additions. Everything below is NEW; the messages above are unchanged byte for byte.
// =====================================================================================================================================

// ---- SCREENTIME: phone screen time today (NOT the ESP32's own display-on time) ----
// [flags u8][totalMin u16][goalMin u16][nApps u8] then nApps x ([min u16][nameLen u8][name])
// flags: bit0 has data, bit1 needs Usage Access, bit2 unavailable (error), bit3 goal on
constexpr uint8_t ST_MAX_APPS = 4, ST_NAME = 14;
constexpr uint16_t ST_MAX_MIN = 1440;
struct ScreenTimeData {
  bool hasData = false, needsPermission = false, unavailable = false, goalOn = false;
  uint16_t totalMin = 0, goalMin = 0; uint8_t n = 0;
  uint16_t appMin[ST_MAX_APPS] = {0};
  char appName[ST_MAX_APPS][ST_NAME + 1] = {{0}};
};
inline bool parseScreenTime(const uint8_t* p, uint8_t n, ScreenTimeData& o) {
  if (n < 6) return false;
  uint8_t f = p[0]; uint16_t total = rd16(p + 1), goal = rd16(p + 3); uint8_t cnt = p[5];
  if (total > ST_MAX_MIN || goal > ST_MAX_MIN || cnt > ST_MAX_APPS) return false;
  ScreenTimeData t;
  size_t at = 6;
  for (uint8_t i = 0; i < cnt; i++) {
    if (at + 3 > n) return false;
    uint16_t m = rd16(p + at); uint8_t nl = p[at + 2]; at += 3;
    if (m > ST_MAX_MIN || nl > ST_NAME || at + nl > n) return false;
    t.appMin[i] = m; copyAscii(t.appName[i], sizeof t.appName[i], p + at, nl); at += nl;
  }
  if (at != n) return false;
  t.hasData = f & 1; t.needsPermission = f & 2; t.unavailable = f & 4; t.goalOn = (f & 8) && goal > 0;
  t.totalMin = total; t.goalMin = goal; t.n = cnt;
  o = t; return true;
}

// ---- NOTIFS: snapshot of the newest notifications, newest first, kept in RAM only (never written to flash) ----
// [flags u8][count u8] then count x ([ageMin u16][appLen u8][app][titleLen u8][title][textLen u8][text])
// flags: bit0 feature on, bit1 needs Notification Access
constexpr uint8_t NT_MAX = 8, NT_APP = 12, NT_TITLE = 24, NT_TEXT = 48;
struct NotifItem { uint16_t ageMin = 0; char app[NT_APP + 1] = ""; char title[NT_TITLE + 1] = ""; char text[NT_TEXT + 1] = ""; };
struct NotifData { bool enabled = false, needsAccess = false; uint8_t n = 0; NotifItem items[NT_MAX]; };
inline bool parseNotifs(const uint8_t* p, uint8_t n, NotifData& o) {
  if (n < 2) return false;
  uint8_t f = p[0], cnt = p[1];
  if (cnt > NT_MAX) return false;
  NotifData d;
  size_t at = 2;
  for (uint8_t i = 0; i < cnt; i++) {
    if (at + 3 > n) return false;
    d.items[i].ageMin = rd16(p + at); at += 2;
    uint8_t al = p[at++]; if (al > NT_APP || at + al + 1 > n) return false;
    copyAscii(d.items[i].app, sizeof d.items[i].app, p + at, al); at += al;
    uint8_t tl = p[at++]; if (tl > NT_TITLE || at + tl + 1 > n) return false;
    copyAscii(d.items[i].title, sizeof d.items[i].title, p + at, tl); at += tl;
    uint8_t xl = p[at++]; if (xl > NT_TEXT || at + xl > n) return false;
    copyAscii(d.items[i].text, sizeof d.items[i].text, p + at, xl); at += xl;
  }
  if (at != n) return false;
  d.enabled = f & 1; d.needsAccess = f & 2; d.n = cnt;
  o = d; return true;
}

// ---- MUSIC2: extras for the music screen: where the sound comes from, and lyric lines when a legitimate source provided them ----
// [flags u8][lyricState u8][lineIdx u16][srcLen u8][src][prevLen u8][prev][curLen u8][cur][nextLen u8][next]
// flags: bit0 album art on the display belongs to this track, bit1 lyrics are time-synchronised, bit2 this is a video (no lyrics zone;
//        the screen shows thumbnail, title, duration and a progress bar at the bottom)
// lyricState: 0 not applicable, 1 loading, 2 available (synchronised lines), 3 unavailable for this track, 4 lyrics provider off,
//             5 offline / error, 6 plain lyrics only (shown WITHOUT timing; the phone paginates them)
// The phone also sends a rendered 1-bit mask (MSG_LYRIC) of the three lines so every script and right-to-left text renders on the
// ASCII-only TFT. When that mask is not present the ASCII fields below are used as the fallback.
constexpr uint8_t M2_SRC = 14, M2_PREV = 40, M2_CUR = 56, M2_NEXT = 40;
struct Music2Data {
  bool hasArt = false, synced = false, video = false; uint8_t lyricState = 0; uint16_t lineIdx = 0;
  char src[M2_SRC + 1] = ""; char prev[M2_PREV + 1] = ""; char cur[M2_CUR + 1] = ""; char next[M2_NEXT + 1] = "";
};
inline bool parseMusic2(const uint8_t* p, uint8_t n, Music2Data& o) {
  if (n < 8) return false;
  Music2Data d; d.hasArt = p[0] & 1; d.synced = p[0] & 2; d.video = p[0] & 4; d.lyricState = p[1] > 6 ? 0 : p[1]; d.lineIdx = rd16(p + 2);
  size_t at = 4;
  struct F { char* dst; size_t cap; uint8_t max; } fields[4] = {{d.src, sizeof d.src, M2_SRC}, {d.prev, sizeof d.prev, M2_PREV}, {d.cur, sizeof d.cur, M2_CUR}, {d.next, sizeof d.next, M2_NEXT}};
  for (auto& f : fields) {
    if (at + 1 > n) return false;
    uint8_t l = p[at++]; if (l > f.max || at + l > n) return false;
    copyAscii(f.dst, f.cap, p + at, l); at += l;
  }
  if (at != n) return false;
  o = d; return true;
}

// ---- LYRIC: the three lyric lines rendered on the phone into a 1-bit mask (Devanagari, Arabic/Urdu RTL, Korean, Japanese, ...).
// The TFT's fonts are ASCII only; Android does the shaping and font fallback, so the device just blits the mask in the lyric zone.
// [token u8][seq u8][line u16][w u16][h u16][mask bytes]   (8-byte header, at most 190 bytes per frame)
// w/h are 224x80. `line` must equal MUSIC2's lineIdx for the mask to be shown, so a late chunk can never show the previous line.
// A 2-byte payload (0,0) clears the mask.
constexpr uint16_t LYR_W = 224, LYR_H = 80;
constexpr uint8_t LYR_ROW = LYR_W / 8, LYR_CHUNK = 190;
constexpr uint16_t LYR_BYTES = LYR_ROW * LYR_H;
struct LyricChunk { uint8_t token = 0, seq = 0; uint16_t line = 0, w = 0, h = 0; const uint8_t* data = nullptr; uint8_t len = 0; };
inline bool parseLyricChunk(const uint8_t* p, uint8_t n, LyricChunk& o) {
  if (n < 8) return false;
  o.token = p[0]; o.seq = p[1]; o.line = rd16(p + 2); o.w = rd16(p + 4); o.h = rd16(p + 6);
  o.data = p + 8; o.len = (uint8_t)(n - 8);
  if (o.w != LYR_W || o.h != LYR_H || o.len == 0) return false;
  const uint32_t off = (uint32_t)o.seq * LYR_CHUNK;
  if (off + o.len > LYR_BYTES) return false;
  return true;
}
inline bool isLyricClear(const uint8_t* p, uint8_t n) { return n == 2 && p[0] == 0 && p[1] == 0; }

// ---- image transfer (custom HTML screen and album art) ----
// BEGIN: [xfer u8][kind u8: 0 custom screen file, 1 album art raw RGB565][total u32][crc32 u32][chunk u16][w u16][h u16]   (16 bytes)
// DATA : [xfer u8][seq u16][bytes ...]   chunks are numbered from 0 and accepted strictly in order; anything else is dropped and the phone resends
// END  : [xfer u8]
// CTRL : [op u8]
// crc32 is the standard IEEE CRC-32 over all `total` bytes. The device answers through the status extension (encodeStatusExt).
constexpr uint8_t KIND_CUSTOM = 0, KIND_ART = 1;
constexpr uint8_t IMG_BEGIN_LEN = 16, IMG_MAX_CHUNK = 190;
constexpr uint8_t CTRL_ABORT = 1, CTRL_DELETE_CUSTOM = 2, CTRL_CLEAR_ART = 3;
constexpr uint8_t ART_MAX_SIDE = 128;
struct ImgBegin { uint8_t xfer = 0, kind = 0; uint32_t total = 0, crc = 0; uint16_t chunk = 0, w = 0, h = 0; };
inline bool parseImgBegin(const uint8_t* p, uint8_t n, ImgBegin& o) {
  if (n != IMG_BEGIN_LEN) return false;
  o.xfer = p[0]; o.kind = p[1]; o.total = rd32(p + 2); o.crc = rd32(p + 6); o.chunk = rd16(p + 10); o.w = rd16(p + 12); o.h = rd16(p + 14);
  return o.kind <= KIND_ART && o.chunk >= 1 && o.chunk <= IMG_MAX_CHUNK && o.total > 0;
}

enum XferState : uint8_t { XFER_IDLE = 0, XFER_RECEIVING = 1, XFER_DONE = 2, XFER_FAILED = 3 };
enum XferReason : uint8_t { XR_NONE = 0, XR_BAD_HEADER = 1, XR_TOO_LARGE = 2, XR_NO_SPACE = 3, XR_FLASH = 4, XR_CRC = 5, XR_SEQUENCE = 6, XR_BAD_IMAGE = 7, XR_NO_MEMORY = 8, XR_BUSY = 9 };

// Status extension, appended after the 10 status bytes: [state u8][xfer u8][nextSeq u16][reason u8][customCrc u32][artCrc u32]
// artCrc is the CRC-32 of the album art currently held in RAM (0 = none). Firmware before the art CRC sends 19 bytes; the phone
// treats a short status as "unknown", so both directions stay compatible.
constexpr uint8_t STATUS_EXT_LEN = 13, STATUS_FULL_LEN = STATUS_LEN + STATUS_EXT_LEN;
struct XferStatus { uint8_t state = XFER_IDLE, id = 0, reason = XR_NONE; uint16_t nextSeq = 0; uint32_t customCrc = 0, artCrc = 0; };
inline void encodeStatusExt(const XferStatus& x, uint8_t* o) {   // writes STATUS_EXT_LEN bytes at o (pass status + STATUS_LEN)
  o[0] = x.state; o[1] = x.id; wr16(o + 2, x.nextSeq); o[4] = x.reason; wr32(o + 5, x.customCrc); wr32(o + 9, x.artCrc);
}

}  // namespace meow
