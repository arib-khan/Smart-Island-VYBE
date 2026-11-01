// Flash / RAM storage behind the image transfer, and the custom-screen drawing.
//   * custom screen: one file in LittleFS (/custom.mimg). A new upload goes to /custom.tmp first and replaces the old file only after the CRC
//     matched and the whole file validated, so a failed upload never destroys the design that is on the display.
//   * album art: a small raw RGB565 block in RAM (never written to flash), refreshed per track.
//   * the same bytes are never written twice: ImageReceiver answers DONE straight away when the CRC equals the stored one.
// LittleFS needs a data partition: use Tools > Partition Scheme > "Minimal SPIFFS" (190 KB) or "Huge APP"/"Default" (see firmware/README.md).
#pragma once
#include <LittleFS.h>
#include "gfx_common.h"
#include "protocol.h"
#include "mimg.h"
#include "image_rx.h"

static const char* const CUSTOM_PATH = "/custom.mimg";
static const char* const CUSTOM_TMP  = "/custom.tmp";
constexpr uint32_t FLASH_MARGIN = 8192;           // LittleFS metadata + block rounding headroom

static bool     fsReady = false;
static uint32_t customCrc = 0;                    // 0 = no (valid) custom image
static uint32_t customRev = 0;                    // bumps whenever the stored image changes (the screen redraws)
static uint16_t* artBuf = nullptr; static uint16_t artW = 0, artH = 0; static bool artReady = false; static uint32_t artRev = 0;
static uint32_t artCrc = 0;                       // CRC-32 of the art in RAM, 0 = none: lets the phone skip a repeated transfer

// Colours arrive as RGB565 from the phone; boards with swapped red/blue need the fields exchanged.
static inline uint16_t fixColor(uint16_t c) { return SWAP_RED_BLUE ? (uint16_t)(((c & 0x1F) << 11) | (c & 0x07E0) | (c >> 11)) : c; }

static size_t fileRead(void* ctx, uint8_t* buf, size_t n) { return ((File*)ctx)->read(buf, n); }

// Reads a whole stored file once: structure check + CRC-32 of its bytes. Returns 0 when it is not a valid MIMG for this display.
static uint32_t customInspect(const char* path) {
  File f = LittleFS.open(path, "r"); if (!f) return 0;
  uint32_t len = (uint32_t)f.size();
  bool ok = meow::mimgValidate(fileRead, &f, len, TFT_W, TFT_H);
  uint32_t crc = 0;
  if (ok) {
    f.seek(0); uint8_t b[128]; uint32_t c = meow::crc32Begin(); size_t n;
    while ((n = f.read(b, sizeof b)) > 0) c = meow::crc32Update(c, b, n);
    crc = meow::crc32End(c); if (crc == 0) crc = 1;       // 0 is reserved for "none"
  }
  f.close();
  return crc;
}

// Mounts the file system and finds the stored image. Also finishes a replacement that was interrupted by a power cut between "remove old"
// and "rename new".
static void customBoot() {
  fsReady = LittleFS.begin(true);                          // true = format on first use
  if (!fsReady) { Serial.println("LittleFS: mount failed, custom screen disabled"); return; }
  customCrc = customInspect(CUSTOM_PATH);
  if (!customCrc) {
    LittleFS.remove(CUSTOM_PATH);
    if (customInspect(CUSTOM_TMP)) { LittleFS.rename(CUSTOM_TMP, CUSTOM_PATH); customCrc = customInspect(CUSTOM_PATH); }
  }
  LittleFS.remove(CUSTOM_TMP);
  customRev++;
  Serial.printf("Custom screen: %s\n", customCrc ? "stored image found" : "none");
}

struct FlashSink : meow::RxSink {
  File tmp; uint8_t artKind = 0; uint32_t artWant = 0, artAt = 0;
  uint32_t storedCustomCrc() override { return customCrc; }
  uint32_t storedArtCrc() override { return artCrc; }
  bool open(uint8_t kind, uint32_t total, uint16_t w, uint16_t h, uint8_t& reason) override {
    if (kind == meow::KIND_CUSTOM) {
      if (!fsReady) { reason = meow::XR_FLASH; return false; }
      size_t freeB = LittleFS.totalBytes() - LittleFS.usedBytes();
      if (freeB < (size_t)total + FLASH_MARGIN) { reason = meow::XR_NO_SPACE; return false; }
      LittleFS.remove(CUSTOM_TMP);
      tmp = LittleFS.open(CUSTOM_TMP, "w");
      if (!tmp) { reason = meow::XR_FLASH; return false; }
      return true;
    }
    artReady = false;                                       // the old art is overwritten from now on
    artCrc = 0;                                             // the phone must not think the previous cover is still current
    if (!artBuf || artW != w || artH != h) {
      if (artBuf) { free(artBuf); artBuf = nullptr; }
      artBuf = (uint16_t*)malloc((size_t)w * h * 2);
      if (!artBuf) { reason = meow::XR_NO_MEMORY; artW = artH = 0; return false; }
      artW = w; artH = h;
    }
    artAt = 0; artWant = total; return true;
  }
  bool write(uint8_t kind, const uint8_t* d, size_t n) override {
    if (kind == meow::KIND_CUSTOM) return tmp && tmp.write(d, n) == n;
    if (!artBuf || artAt + n > artWant) return false;
    memcpy((uint8_t*)artBuf + artAt, d, n); artAt += (uint32_t)n; return true;
  }
  bool commit(uint8_t kind, uint32_t crc, uint8_t& reason) override {
    if (kind == meow::KIND_ART) {
      if (artAt != artWant) { reason = meow::XR_SEQUENCE; return false; }
      for (size_t i = 0; i < (size_t)artW * artH; i++) artBuf[i] = fixColor(artBuf[i]);    // little-endian bytes from the phone are already host order on the ESP32
      artReady = true; artCrc = crc ? crc : 1; artRev++; return true;
    }
    tmp.flush(); tmp.close();
    if (!customInspect(CUSTOM_TMP)) { LittleFS.remove(CUSTOM_TMP); reason = meow::XR_BAD_IMAGE; return false; }
    if (!LittleFS.rename(CUSTOM_TMP, CUSTOM_PATH)) {          // littlefs normally replaces the target atomically; if this build refuses, remove first
      LittleFS.remove(CUSTOM_PATH);
      if (!LittleFS.rename(CUSTOM_TMP, CUSTOM_PATH)) { reason = meow::XR_FLASH; customCrc = 0; customRev++; return false; }   // /custom.tmp is valid and is promoted at the next boot
    }
    customCrc = crc ? crc : 1; customRev++; return true;
  }
  void abort(uint8_t kind) override { if (kind == meow::KIND_CUSTOM) { if (tmp) tmp.close(); LittleFS.remove(CUSTOM_TMP); } else { artReady = false; artCrc = 0; } }
  void deleteCustom() override { if (fsReady) { LittleFS.remove(CUSTOM_PATH); LittleFS.remove(CUSTOM_TMP); } customCrc = 0; customRev++; }
  void clearArt() override { artReady = false; artCrc = 0; artRev++; }
};
static FlashSink flashSink;
static meow::ImageReceiver imgRx(&flashSink, TFT_W, TFT_H);

// ------------------------------- custom screen drawing -------------------------------
struct CustomView { bool linked = false; uint32_t drawnRev = 0xFFFFFFFF; bool drawnOk = true; };

static void customMessage(const char* l1, const char* l2) {
  G = &tft; Y0 = 0; tft.fillScreen(C_BG);
  drawPill("CUSTOM SCREEN", C_DATE, 6);
  gText(120, 150, l1, &FreeSansBold12pt7b, C_TEXT, 1);
  if (l2 && l2[0]) gText(120, 182, l2, &FreeSans9pt7b, C_TIME, 1);
  drawStar(30, 100, 9, C_LAV); drawStar(210, 108, 7, C_LAV);
}

// Streams the stored image to the TFT 8 rows at a time (about 3.8 KB of RAM). Returns false when the file could not be shown.
static bool customDraw() {
  static uint16_t rowsBuf[240 * 8];
  if (!fsReady || !customCrc) { customMessage("No custom design yet", "Send one from the app"); return true; }
  File f = LittleFS.open(CUSTOM_PATH, "r");
  meow::MimgDecoder d;
  if (!f || !d.begin(fileRead, &f, (uint32_t)f.size(), TFT_W, TFT_H)) { if (f) f.close(); customMessage("Design unreadable", "Send it again from the app"); return false; }
  G = &tft; Y0 = 0; int y = 0;
  while (y < d.header().h) {
    int n = d.rows(rowsBuf, 8); if (n <= 0) break;
    for (int i = 0; i < n * d.header().w; i++) rowsBuf[i] = fixColor(rowsBuf[i]);
    tft.drawRGBBitmap(0, y, rowsBuf, d.header().w, n); y += n;
  }
  bool ok = d.finished() && !d.failed(); f.close();
  if (!ok) customMessage("Design unreadable", "Send it again from the app");
  return ok;
}
// Called when the screen is entered or the stored image changed; draws only then (the image is static).
static void customEnter(CustomView& v) { v.drawnOk = customDraw(); v.drawnRev = customRev; }
static void customTick(CustomView& v) { if (v.drawnRev != customRev) customEnter(v); }
