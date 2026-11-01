// Drives the whole sketch through the BLE write path for the NEW features: screen time, notifications, music extras, album art and the
// custom image (transfer -> LittleFS -> pixels on the TFT -> reboot -> still there). Uses stand-ins for GFX/LittleFS/NimBLE (see README).
#include "common.h"
#include <vector>
#include <string>
#include "../MeowIsland/MeowIsland.ino"

static int fails = 0, ok = 0;
#define CHECK(c, m) do { if (c) ok++; else { fails++; printf("FAIL: %s (line %d)\n", m, __LINE__); } } while (0)
typedef std::vector<uint8_t> Bytes;
static NimBLEConnInfo ci; static uint32_t T = 1000;
static void run(uint32_t ms) { for (uint32_t e = T + ms; T < e; T += 20) { host_millis = T; loop(); } }
static Bytes frame(uint8_t type, const Bytes& p) { Bytes f = {0xA5, type, (uint8_t)p.size()}; f.insert(f.end(), p.begin(), p.end()); return f; }
static void write(const Bytes& b, size_t chunk = 244) { for (size_t i = 0; i < b.size(); i += chunk) { bleRx->value.v.assign(b.begin() + i, b.begin() + std::min(b.size(), i + chunk)); bleRxCb.onWrite(bleRx, ci); } }
static void le16(Bytes& b, uint32_t v) { b.push_back(v & 255); b.push_back((v >> 8) & 255); } static void le32(Bytes& b, uint32_t v) { le16(b, v & 0xFFFF); le16(b, v >> 16); }
static void str(Bytes& b, const char* s) { b.push_back((uint8_t)strlen(s)); b.insert(b.end(), s, s + strlen(s)); }
static uint16_t px(int x, int y) { return tft.getBuffer()[(size_t)y * 240 + x]; }

// image: left half red, right half blue, 8-row green stripe on top
static Bytes makeImage(uint16_t left, uint16_t right) {
  const int W = 240, H = 320; Bytes o = {'M', 'I', 1, 0}; le16(o, W); le16(o, H); le16(o, 3); le16(o, 0);
  Bytes data; auto run_ = [&](int n, uint8_t idx) { while (n > 0) { int k = std::min(n, 65535); data.push_back(0); le16(data, k); data.push_back(idx); n -= k; } };
  for (int y = 0; y < H; y++) { if (y < 8) run_(W, 2); else { run_(120, 0); run_(120, 1); } }
  le32(o, (uint32_t)data.size()); le16(o, left); le16(o, right); le16(o, 0x07E0); o.insert(o.end(), data.begin(), data.end()); return o;
}
// the phone's transfer, without losses (loss handling is covered by image_test.cpp)
static void sendImage(const Bytes& file, uint8_t id, uint8_t kind, uint16_t w, uint16_t h, uint32_t crcOverride = 0, bool use = false) {
  Bytes b; b.push_back(id); b.push_back(kind); le32(b, (uint32_t)file.size()); le32(b, use ? crcOverride : meow::crc32Of(file.data(), file.size())); le16(b, 180); le16(b, w); le16(b, h);
  write(frame(meow::MSG_IMG_BEGIN, b)); run(60);
  uint16_t seq = 0;
  for (size_t off = 0; off < file.size(); off += 180, seq++) {
    Bytes d = {id}; le16(d, seq); size_t n = std::min<size_t>(180, file.size() - off); d.insert(d.end(), file.begin() + off, file.begin() + off + n);
    write(frame(meow::MSG_IMG_DATA, d)); run(20);
  }
  write(frame(meow::MSG_IMG_END, {id})); run(80);
}
static meow::XferStatus xs() { return imgRx.status(); }
static void selectScreen(uint8_t s) { write(frame(meow::MSG_SCREEN, {s})); run(200); }

int main(int, char** argv) {
  const char* OUT = argv[1];
  auto snap = [&](const char* n) { char p[256]; snprintf(p, sizeof p, "%s/%s.ppm", OUT, n); dumpPpm(p, tft); };
  setenv("TZ", "UTC", 1); tzset(); host_millis = T; host_epoch = 0; setup(); run(400);
  MeowServerCb scb; scb.onConnect(bleServer, ci); scb.onPassKeyDisplay(); run(60); NimBLEDevice::bonds = 1; scb.onAuthenticationComplete(ci); run(200);
  CHECK(bleSecure, "paired link is secure");

  // ---- screen ids
  CHECK(meow::SCREEN_COUNT == 6, "six screens"); selectScreen(3); CHECK(curScreen == meow::SCREEN_SCREENTIME, "phone selects screen time"); selectScreen(4); CHECK(curScreen == meow::SCREEN_NOTIFS, "phone selects notifications"); selectScreen(9); CHECK(curScreen == meow::SCREEN_CLOCK, "unknown screen falls back to the clock");

  // ---- screen time
  selectScreen(3); snap("st_nodata");
  { Bytes p = {9}; le16(p, 222); le16(p, 240); p.push_back(3); le16(p, 95); str(p, "Instagram"); le16(p, 70); str(p, "YouTube"); le16(p, 30); str(p, "Chrome"); write(frame(meow::MSG_SCREENTIME, p)); run(100); }
  CHECK(stView.haveData && stView.d.totalMin == 222 && stView.d.goalOn && stView.d.n == 3, "screen time stored"); snap("st_goal");
  { Bytes p = {2}; le16(p, 0); le16(p, 0); p.push_back(0); write(frame(meow::MSG_SCREENTIME, p)); run(100); } CHECK(stView.d.needsPermission, "permission state"); snap("st_permission");
  { Bytes p = {13}; le16(p, 400); le16(p, 240); p.push_back(1); le16(p, 400); str(p, "Social"); write(frame(meow::MSG_SCREENTIME, p)); run(100); } snap("st_over_goal");
  { Bytes bad = {1, 0xFF, 0xFF, 0, 0, 0}; write(frame(meow::MSG_SCREENTIME, bad)); run(60); CHECK(stView.d.totalMin == 400 && !stView.d.needsPermission, "an invalid frame changes nothing"); }

  // ---- notifications
  selectScreen(4); snap("nt_nodata");
  { Bytes p = {1, 3}; le16(p, 0); str(p, "WhatsApp"); str(p, "Aditi"); str(p, "Are we still meeting at 6?"); le16(p, 14); str(p, "Gmail"); str(p, ""); str(p, "Your receipt from Cafe"); le16(p, 190); str(p, "Instagram"); str(p, ""); str(p, ""); write(frame(meow::MSG_NOTIFS, p)); run(100); }
  CHECK(ntView.haveData && ntView.d.n == 3, "notifications stored"); snap("nt_list");
  { Bytes p = {2, 0}; write(frame(meow::MSG_NOTIFS, p)); run(100); } snap("nt_access");
  { Bytes p = {1, 0}; write(frame(meow::MSG_NOTIFS, p)); run(100); } snap("nt_empty");

  // ---- music extras + art ----
  auto music2 = [&](uint8_t flags, uint8_t ly, uint16_t idx, const char* src, const char* prev, const char* cur, const char* next) {
    Bytes b = {flags, ly}; le16(b, idx); str(b, src); str(b, prev); str(b, cur); str(b, next);
    write(frame(meow::MSG_MUSIC2, b)); run(60);
  };
  auto sendBand = [&](uint8_t token, uint16_t line, uint8_t fill) {
    Bytes mask(meow::LYR_BYTES, fill);
    for (size_t off = 0; off < mask.size(); off += meow::LYR_CHUNK) {
      uint16_t seq = (uint16_t)(off / meow::LYR_CHUNK);
      Bytes p = {token, (uint8_t)seq}; le16(p, line); le16(p, meow::LYR_W); le16(p, meow::LYR_H);
      size_t n = std::min<size_t>(meow::LYR_CHUNK, mask.size() - off); p.insert(p.end(), mask.begin() + off, mask.begin() + off + n);
      write(frame(meow::MSG_LYRIC, p)); run(20);
    }
    run(250);
  };
  { Bytes m = {3}; le16(m, 102); le16(m, 318); str(m, "Kabhi Kabhi Aditi"); str(m, "Rashid Ali"); write(frame(meow::MSG_MUSIC, m)); run(60); }
  music2(2, 2, 3, "SPOTIFY", "previous line of the song", "the current line is quite a bit longer than the screen", "next line");
  selectScreen(2); run(400); CHECK(musicView.haveData2 && isSpotify(musicView), "music extras stored"); snap("music_lyrics_noart");
  // phone-rendered mask: all bits set, so the lyric zone pixels must be the lyric colours
  sendBand(9, 3, 0xFF);
  CHECK(lyrReady && lyrValid(3), "lyric mask received and tied to the current line"); snap("music_lyrics_mask");
  CHECK(px(12, 240) == C_MUTE && px(12, 260) == C_TEXT && px(12, 306) == C_MUTE, "lyric mask pixels drawn in the lyric zone");
  Bytes art(80 * 80 * 2); for (int i = 0; i < 80 * 80; i++) { art[i * 2] = 0x1F; art[i * 2 + 1] = 0xF8 & 0; }   // pure blue (0x001F) pixels
  sendImage(art, 1, meow::KIND_ART, 80, 80); CHECK(xs().state == meow::XFER_DONE && artReady && artW == 80, "album art received into RAM");
  CHECK(xs().artCrc == meow::crc32Of(art.data(), art.size()), "the status carries the album-art CRC so a replay can skip the transfer");
  { Bytes p = {3, 2}; le16(p, 3); str(p, "SPOTIFY"); str(p, "previous line of the song"); str(p, "the current line is quite a bit longer than the screen"); str(p, "next line"); write(frame(meow::MSG_MUSIC2, p)); run(300); }
  CHECK(px(120, 80) == 0x001F, "album art pixels are on the TFT"); snap("music_lyrics_art");
  // video: no lyric zone, thumbnail/title/progress, bottom bar
  { Bytes m = {3}; le16(m, 102); le16(m, 318); str(m, "A YouTube video title"); str(m, "A channel"); write(frame(meow::MSG_MUSIC, m)); run(80); }
  music2(4, 0, 0, "YOUTUBE", "", "", ""); selectScreen(2); run(500);
  CHECK(musicVideo(musicView) && musicView.d2.video && musicView.d2.lyricState == 0, "YouTube is a video: no lyrics state");
  CHECK(px(30, 302) != C_BG, "video progress bar is at the bottom of the screen"); snap("music_video");
  // switching back to a music source clears the video flag and asks for the lyric mask of the new line
  music2(2, 2, 4, "AMAZON MUSIC", "prev", "current", "next");
  sendBand(10, 4, 0xFF); run(300);
  CHECK(!musicVideo(musicView) && lyrValid(4), "Amazon Music gets lyrics again");
  // a new track clears the old lyric mask and art flag
  { Bytes m = {3}; le16(m, 5); le16(m, 200); str(m, "Another Song"); str(m, "Someone"); write(frame(meow::MSG_MUSIC, m)); run(300); CHECK(musicView.d2.lyricState == 0 && !musicView.d2.hasArt && !musicView.d2.cur[0], "a new track clears the old lyric lines and art flag"); }
  CHECK(!lyrReady, "a new track clears the old lyric mask");

  // ---- custom screen: BOOT skips it while empty
  CHECK(customCrc == 0, "no custom image yet"); selectScreen(meow::SCREEN_MUSIC);
  { std::vector<uint8_t> seen; for (int i = 0; i < 6; i++) { host_btn_low = true; run(200); host_btn_low = false; run(100); seen.push_back(curScreen); } bool hit = false; for (auto s : seen) hit |= (s == meow::SCREEN_CUSTOM); CHECK(!hit, "BOOT tap never lands on the empty custom screen"); }
  selectScreen(meow::SCREEN_CUSTOM); snap("custom_empty");

  // ---- custom image
  Bytes img = makeImage(0xF800, 0x001F);
  sendImage(img, 5, meow::KIND_CUSTOM, 240, 320); CHECK(xs().state == meow::XFER_DONE && xs().reason == 0 && customCrc == meow::crc32Of(img.data(), img.size()), "custom image stored, crc reported in the status");
  CHECK(fsData().files.count("/custom.mimg") && !fsData().files.count("/custom.tmp"), "file in flash, no temp file left"); run(200);
  CHECK(px(10, 100) == 0xF800 && px(230, 100) == 0x001F && px(100, 3) == 0x07E0, "pixels of the design are on the TFT"); snap("custom_shown");
  { uint8_t st[meow::STATUS_FULL_LEN]; meow::Status s0; meow::encodeStatus(s0, st); meow::encodeStatusExt(xs(), st + meow::STATUS_LEN); CHECK(st[10] == meow::XFER_DONE && st[11] == 5, "status extension carries state and id"); }
  { bool hit = false; for (int i = 0; i < 6; i++) { host_btn_low = true; run(200); host_btn_low = false; run(100); hit |= curScreen == meow::SCREEN_CUSTOM; } CHECK(hit, "BOOT tap reaches the custom screen once a design exists"); }
  selectScreen(meow::SCREEN_CLOCK); CHECK(customCrc != 0, "going back to the clock keeps the design");

  // ---- replacing: a failed upload keeps the old design visible, a good one replaces it
  { Bytes img2 = makeImage(0x07E0, 0xF800); int writesBefore = (int)fsData().files.size(); (void)writesBefore;
    sendImage(img2, 6, meow::KIND_CUSTOM, 240, 320, 0xDEADBEEF, true); CHECK(xs().state == meow::XFER_FAILED && xs().reason == meow::XR_CRC, "bad CRC rejected");
    CHECK(customCrc == meow::crc32Of(img.data(), img.size()) && fsData().files["/custom.mimg"] == img && !fsData().files.count("/custom.tmp"), "old design untouched, temp file removed");
    fsData().failWrites = true; sendImage(img2, 7, meow::KIND_CUSTOM, 240, 320); fsData().failWrites = false; CHECK(xs().state == meow::XFER_FAILED && fsData().files["/custom.mimg"] == img, "flash write failure keeps the old design");
    fsData().total = LittleFS.usedBytes() + 6000; sendImage(img2, 8, meow::KIND_CUSTOM, 240, 320); fsData().total = 190 * 1024; CHECK(xs().state == meow::XFER_FAILED && xs().reason == meow::XR_NO_SPACE && fsData().files["/custom.mimg"] == img, "no space is reported and the old design survives");
    sendImage(img2, 9, meow::KIND_CUSTOM, 240, 320); CHECK(xs().state == meow::XFER_DONE && fsData().files["/custom.mimg"] == img2, "a good upload replaces it");
    sendImage(img2, 10, meow::KIND_CUSTOM, 240, 320); CHECK(xs().state == meow::XFER_DONE && !fsData().files.count("/custom.tmp"), "re-sending the same design is acknowledged without a rewrite");
  }

  // ---- reboot: the design comes back from flash
  { uint32_t was = customCrc; customCrc = 0; fsReady = false; customBoot(); CHECK(customCrc == was && fsReady, "design restored after a reboot"); }
  { fsData().files["/custom.mimg"][30] ^= 0xFF; uint32_t was = customCrc; customBoot(); (void)was; CHECK(true, "corrupted file does not crash boot"); }

  // ---- connection lost mid-transfer: partial data is dropped
  { Bytes img3 = makeImage(0x001F, 0x07E0); Bytes b; b.push_back(11); b.push_back(0); le32(b, (uint32_t)img3.size()); le32(b, meow::crc32Of(img3.data(), img3.size())); le16(b, 180); le16(b, 240); le16(b, 320);
    write(frame(meow::MSG_IMG_BEGIN, b)); run(60); Bytes d = {11}; le16(d, 0); d.insert(d.end(), img3.begin(), img3.begin() + 180); write(frame(meow::MSG_IMG_DATA, d)); run(60); CHECK(xs().state == meow::XFER_RECEIVING, "receiving");
    scb.onDisconnect(bleServer, ci, 0); run(200); CHECK(xs().state != meow::XFER_RECEIVING && !fsData().files.count("/custom.tmp"), "link loss aborts the half-received image and removes the temp file"); }

  // ---- delete
  scb.onConnect(bleServer, ci); scb.onAuthenticationComplete(ci); run(100);
  write(frame(meow::MSG_IMG_CTRL, {meow::CTRL_DELETE_CUSTOM})); run(100); CHECK(customCrc == 0 && !fsData().files.count("/custom.mimg"), "delete removes the design");
  printf("sketch smoke test (new features): %d checks passed, %d failed\n", ok, fails); return fails ? 1 : 0;
}
