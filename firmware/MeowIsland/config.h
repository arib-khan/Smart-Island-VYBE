// Meow Island - hardware and look configuration. Pins, display and palette are exactly the values of the original working clock sketch.
#pragma once
#include <Arduino.h>
#include <SPI.h>

// ---- Optional development fallback: create secrets.h with  #define WIFI_SSID "..."  and  #define WIFI_PASS "..."
// Normal use needs NO file: the app sends the Wi-Fi details over the paired Bluetooth link and the ESP32 stores them itself.
#if __has_include("secrets.h")
  #include "secrets.h"
#endif

// POSIX timezone used until the phone sends its own offset. India (IST, UTC+5:30, no DST):
const char* TZ_INFO = "IST-5:30";
const char* NTP_1 = "pool.ntp.org";
const char* NTP_2 = "time.google.com";
const char* NTP_3 = "time.nist.gov";

constexpr bool     TIME_24H          = true;   // false = 12-hour clock
constexpr bool     LEADING_ZERO_12H  = false;  // 12-hour mode: "9:41" instead of "09:41"
constexpr uint32_t WIFI_RETRY_MS     = 20000;  // reconnect attempt interval
constexpr uint32_t WIFI_CONNECT_TIMEOUT_MS = 25000;   // a new Wi-Fi setup that has not connected by then is reported as failed

// ---- Pins (your existing wiring, unchanged; these are the default VSPI pins)
constexpr int8_t PIN_CS   = 5;
constexpr int8_t PIN_DC   = 2;
constexpr int8_t PIN_RST  = 4;
constexpr int8_t PIN_SCK  = 18;
constexpr int8_t PIN_MOSI = 23;
constexpr int8_t PIN_MISO = 19;   // not needed for display-only use
constexpr int8_t PIN_BL   = -1;   // -1 = backlight wired straight to 3.3V
constexpr int8_t PIN_BTN  = 0;    // the DevKit's BOOT button (active low): tap = next screen, hold 3 s = pairing mode, hold 10 s = forget everything

// ---- Display module variant (change here if your module differs)
constexpr uint16_t TFT_W         = 240;
constexpr uint16_t TFT_H         = 320;
constexpr uint8_t  TFT_ROTATION  = 0;        // 0 portrait; 2 = portrait rotated 180
constexpr uint8_t  TFT_SPI_MODE  = SPI_MODE0;// try SPI_MODE3 if the screen stays white/black
constexpr bool     TFT_INVERT    = false;    // flip if colours look negative
constexpr bool     SWAP_RED_BLUE = false;    // true if red and blue are swapped
constexpr uint32_t SPI_HZ        = 40000000; // lower to 20000000 if you see glitches

// ---- Pairing / link
constexpr uint32_t PAIRING_WINDOW_MS = 120000;   // how long a new phone may pair after the button hold (a device with no phone paired is always open)
constexpr uint32_t PASSKEY_SHOW_MS   = 90000;    // the 6-digit code stays on screen this long
constexpr uint32_t STEPS_STALE_MS    = 600000;   // steps older than this (phone gone) get a note
constexpr uint8_t  FW_MAJOR = 1, FW_MINOR = 2;   // 1.2: album-art CRC in the status, lyric mask (MSG_LYRIC), video mode

constexpr uint16_t RGB(uint8_t r, uint8_t g, uint8_t b) {
  return SWAP_RED_BLUE
    ? (uint16_t)(((b & 0xF8) << 8) | ((g & 0xFC) << 3) | (r >> 3))
    : (uint16_t)(((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3));
}

// ------------------------------- palette (clock) ----------------------------
constexpr uint16_t C_BG      = RGB(3, 1, 6);
constexpr uint16_t C_GLOW2   = RGB(36, 1, 80);
constexpr uint16_t C_GLOW1   = RGB(76, 12, 170);
constexpr uint16_t C_LINE    = RGB(189, 77, 248);
constexpr uint16_t C_LINE_HI = RGB(221, 130, 255);
constexpr uint16_t C_LAV     = RGB(221, 170, 252);
constexpr uint16_t C_PINK    = RGB(255, 55, 225);
constexpr uint16_t C_NOSE    = RGB(255, 85, 205);
constexpr uint16_t C_MOUTH   = RGB(255, 105, 215);
constexpr uint16_t C_TOE     = RGB(70, 35, 130);
constexpr uint16_t C_DATE    = RGB(190, 125, 255);
constexpr uint16_t C_DGLOW2  = RGB(34, 4, 75);
constexpr uint16_t C_DGLOW1  = RGB(105, 25, 195);

// ------------------- palette (steps + music screens: the phone widgets' colours) -------------------
constexpr uint16_t C_PANEL      = RGB(15, 10, 27);     // heat-map panel
constexpr uint16_t C_PANEL_LINE = RGB(42, 30, 74);
constexpr uint16_t C_CARD       = RGB(17, 12, 31);     // bottom card
constexpr uint16_t C_CARD_LINE  = RGB(59, 44, 110);
constexpr uint16_t C_TEXT       = RGB(255, 255, 255);
constexpr uint16_t C_SUB        = RGB(127, 114, 166);  // subtitles
constexpr uint16_t C_DIM        = RGB(138, 127, 168);
constexpr uint16_t C_MUTE       = RGB(111, 101, 144);
constexpr uint16_t C_ARTIST     = RGB(156, 150, 200);
constexpr uint16_t C_TIME       = RGB(185, 179, 218);
constexpr uint16_t C_TRACK      = RGB(46, 48, 64);
constexpr uint16_t C_TILE       = RGB(36, 36, 44);
constexpr uint16_t C_TILE_LINE  = RGB(58, 58, 71);
constexpr uint16_t C_WARN       = RGB(255, 190, 90);
constexpr uint16_t C_VIOLET     = RGB(124, 77, 255);
constexpr uint16_t C_ORCHID     = RGB(208, 112, 242);
constexpr uint16_t C_RING       = RGB(155, 107, 255);
