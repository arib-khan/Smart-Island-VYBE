// Meow Island - ESP32 DevKit V1 + 2.8" ST7789 (240x320, SPI).
// Six screens: neon sleeping-cat CLOCK, STEP goals, MUSIC, phone SCREEN TIME, NOTIFICATIONS and a user-made CUSTOM screen (rendered from HTML on the phone),
// driven by the Meow Island Android app over Bluetooth LE.
//
// Libraries (Arduino IDE > Library Manager):  Adafruit GFX Library, Adafruit ST7735 and ST7789 Library, NimBLE-Arduino (version 2.x).
// Board: "ESP32 Dev Module". If the compiler says the sketch is too big, set Tools > Partition Scheme to "Minimal SPIFFS" or "Huge APP".
// Buttons: BOOT button (GPIO0): tap = next screen (the custom screen is skipped until a design is stored), hold 3 s = open pairing for a new phone, hold 10 s = forget phones and Wi-Fi.
// See firmware/README.md for wiring, flashing and pairing.

#include <WiFi.h>
#include <Preferences.h>
#include <time.h>
#include <sys/time.h>
#include "config.h"
#include "protocol.h"
#include "gfx_common.h"
#include "clock_screen.h"
#include "steps_screen.h"
#include "screentime_screen.h"
#include "notifs_screen.h"
#include "music_screen.h"      // also pulls in custom_store.h (LittleFS image storage, album art buffer, custom screen drawing)
#include "overlay_screen.h"
#include "device_link.h"

// ------------------------------- state -------------------------------------
static uint8_t curScreen = meow::SCREEN_CLOCK;           // the clock is the start-up screen and the fallback
enum Overlay : uint8_t { OV_NONE, OV_CODE, OV_MODE };
static Overlay overlay = OV_NONE;
static uint32_t overlayUntil = 0;
static bool needRedraw = true;

static StepsView stepsView;  static uint32_t stepsRxAt = 0;
static MusicView musicView;
static ScreenTimeView stView; static NotifsView ntView; static CustomView cuView;

static Preferences prefs;
static char wifiSsid[33] = "", wifiPass[64] = "";
static bool haveWifi = false;
static uint8_t wifiState = meow::WIFI_NONE, wifiReason = meow::REASON_NONE;
static uint32_t wifiBeganAt = 0, lastWifiTry = 0;
static bool ntpStarted = false, timeFromPhone = false;
static char tzCur[24];
static uint32_t pairingOpenUntil = 0;

static bool wifiUp() { return WiFi.status() == WL_CONNECTED; }
static bool timeValid() { return time(nullptr) > MIN_VALID_EPOCH; }

// ------------------------------- Wi-Fi credentials (stored only here, in the ESP32's own flash) ---------------------------
static void wifiLoad() {
  prefs.begin("meow", true);
  String s = prefs.getString("ssid", ""), p = prefs.getString("pass", "");
  prefs.end();
  strncpy(wifiSsid, s.c_str(), sizeof wifiSsid - 1); wifiSsid[sizeof wifiSsid - 1] = 0;
  strncpy(wifiPass, p.c_str(), sizeof wifiPass - 1); wifiPass[sizeof wifiPass - 1] = 0;
#if defined(WIFI_SSID) && defined(WIFI_PASS)            // optional development fallback from secrets.h
  if (!wifiSsid[0]) { strncpy(wifiSsid, WIFI_SSID, sizeof wifiSsid - 1); strncpy(wifiPass, WIFI_PASS, sizeof wifiPass - 1); }
#endif
  haveWifi = wifiSsid[0] != 0;
}
static void wifiSave(const meow::WifiCreds& c) {
  prefs.begin("meow", false); prefs.putString("ssid", c.ssid); prefs.putString("pass", c.pass); prefs.end();
  memcpy(wifiSsid, c.ssid, sizeof wifiSsid); memcpy(wifiPass, c.pass, sizeof wifiPass); haveWifi = true;
}
static void wifiForget() {
  prefs.begin("meow", false); prefs.remove("ssid"); prefs.remove("pass"); prefs.end();
  wifiSsid[0] = 0; wifiPass[0] = 0; haveWifi = false;
  WiFi.disconnect(true); wifiState = meow::WIFI_NONE; wifiReason = meow::REASON_NONE; ntpStarted = false;
}
static void wifiBeginNow(uint32_t now) {
  WiFi.disconnect();
  WiFi.begin(wifiSsid, wifiPass);
  lastWifiTry = now; wifiBeganAt = now; wifiState = meow::WIFI_CONNECTING; wifiReason = meow::REASON_NONE;
}

// Same behaviour as before (non-blocking retry every WIFI_RETRY_MS, NTP once connected), plus a state the app can show.
static void wifiService(uint32_t now) {
  if (!haveWifi) { wifiState = meow::WIFI_NONE; return; }
  int st = WiFi.status();
  if (st == WL_CONNECTED) {
    wifiState = meow::WIFI_CONNECTED; wifiReason = meow::REASON_NONE;
    if (!ntpStarted) { configTzTime(tzCur, NTP_1, NTP_2, NTP_3); ntpStarted = true; }
    return;
  }
  if (wifiState == meow::WIFI_CONNECTED) { wifiState = meow::WIFI_CONNECTING; wifiBeganAt = now; }
  if (st == WL_NO_SSID_AVAIL) wifiReason = meow::REASON_NOT_FOUND;
  else if (st == WL_CONNECT_FAILED) wifiReason = meow::REASON_AUTH;
  if (wifiState == meow::WIFI_CONNECTING && now - wifiBeganAt > WIFI_CONNECT_TIMEOUT_MS) {
    wifiState = meow::WIFI_FAILED; if (wifiReason == meow::REASON_NONE) wifiReason = meow::REASON_TIMEOUT;
  }
  if (now - lastWifiTry >= WIFI_RETRY_MS) {              // keeps trying quietly: the display is never blocked
    lastWifiTry = now; WiFi.disconnect(); WiFi.begin(wifiSsid, wifiPass);
  }
}

// ------------------------------- screens -----------------------------------
static void showScreen(uint32_t now) {
  overlay = OV_NONE; needRedraw = false;
  switch (curScreen) {
    case meow::SCREEN_STEPS: stepsView.linked = bleSecure; tft.fillScreen(C_BG); stepsRender(stepsView); break;
    case meow::SCREEN_MUSIC: musicView.linked = bleSecure; musicEnter(musicView, now); break;
    case meow::SCREEN_SCREENTIME: stView.linked = bleSecure; screenTimeRender(stView); break;
    case meow::SCREEN_NOTIFS: ntView.linked = bleSecure; notifsRender(ntView, now); break;
    case meow::SCREEN_CUSTOM: cuView.linked = bleSecure; customEnter(cuView); break;
    default: clockEnter(wifiUp()); break;
  }
}
// BOOT button order. The custom screen is skipped until a design is stored (the phone can still select it explicitly).
static uint8_t nextScreen(uint8_t s) {
  for (int i = 0; i < meow::SCREEN_COUNT; i++) { s = (uint8_t)((s + 1) % meow::SCREEN_COUNT); if (s == meow::SCREEN_CUSTOM && !customCrc) continue; return s; }
  return meow::SCREEN_CLOCK;
}
static void setScreen(uint8_t s) { if (s >= meow::SCREEN_COUNT) s = meow::SCREEN_CLOCK; if (s != curScreen) { curScreen = s; needRedraw = true; } }

// ------------------------------- commands from the phone -------------------------------
static void applyTime(const meow::TimeData& t) {
  meow::tzString(t.tzMin, tzCur);
  setenv("TZ", tzCur, 1); tzset();
  struct timeval tv = {(time_t)t.epoch, 0};
  if (!ntpStarted || !wifiUp()) { settimeofday(&tv, nullptr); timeFromPhone = true; }   // network time wins whenever it is available
}

static void handleFrames(uint32_t now) {
  meow::Frame f;
  if (bleTake(meow::MSG_TIME, f)) { meow::TimeData t; if (meow::parseTime(f.payload, f.len, t)) applyTime(t); }
  if (bleTake(meow::MSG_WIFI_CLEAR, f)) wifiForget();
  if (bleTake(meow::MSG_WIFI_SET, f)) {
    meow::WifiCreds c;
    if (meow::parseWifi(f.payload, f.len, c)) { wifiSave(c); ntpStarted = false; wifiBeginNow(now); }
    memset(&c, 0, sizeof c);                            // do not leave the password lying around in RAM
    memset(f.payload, 0, sizeof f.payload);
  }
  if (bleTake(meow::MSG_UNPAIR, f)) {
    if (f.len >= 1 && (f.payload[0] & 1)) wifiForget();
    bleForgetPhones();
    pairingOpenUntil = now + PAIRING_WINDOW_MS;
  }
  if (bleTake(meow::MSG_SCREEN, f) && f.len == 1) setScreen(f.payload[0]);
  if (bleTake(meow::MSG_STEPS, f)) {
    meow::StepsData d;
    if (meow::parseSteps(f.payload, f.len, d)) { stepsView.d = d; stepsView.haveData = true; stepsRxAt = now; if (curScreen == meow::SCREEN_STEPS && overlay == OV_NONE) { stepsView.linked = bleSecure; stepsRender(stepsView); } }
  }
  if (bleTake(meow::MSG_MUSIC, f)) {
    meow::MusicData d;
    if (meow::parseMusic(f.payload, f.len, d)) {
      if (strcmp(d.title, musicView.d.title) != 0 || strcmp(d.artist, musicView.d.artist) != 0) {      // a new track: lyric lines and art of the previous one must not linger
        char keep[sizeof musicView.d2.src]; memcpy(keep, musicView.d2.src, sizeof keep);
        musicView.d2 = meow::Music2Data(); memcpy(musicView.d2.src, keep, sizeof keep);
        lyrClear();
      }
      musicView.d = d; musicView.haveData = true; musicView.rxAt = now;
    }
  }
  if (bleTake(meow::MSG_MUSIC2, f)) { meow::Music2Data d; if (meow::parseMusic2(f.payload, f.len, d)) { musicView.d2 = d; musicView.haveData2 = true; } }
  // phone-rendered lyric mask chunks (queued in order; a burst may span several loop iterations)
  { meow::Frame lf; int guard = 0;
    while (guard++ < 24 && bleTakeLyric(lf)) {
      if (meow::isLyricClear(lf.payload, lf.len)) lyrClear();
      else { meow::LyricChunk c; if (meow::parseLyricChunk(lf.payload, lf.len, c)) lyrOnChunk(c, now); }
    }
  }
  if (bleTake(meow::MSG_SCREENTIME, f)) {
    meow::ScreenTimeData d;
    if (meow::parseScreenTime(f.payload, f.len, d)) { stView.d = d; stView.haveData = true; stView.rxAt = now; if (curScreen == meow::SCREEN_SCREENTIME && overlay == OV_NONE) { stView.linked = bleSecure; screenTimeRender(stView); } }
  }
  if (bleTake(meow::MSG_NOTIFS, f)) {
    meow::NotifData d;
    if (meow::parseNotifs(f.payload, f.len, d)) { ntView.d = d; ntView.haveData = true; ntView.rxAt = now; if (curScreen == meow::SCREEN_NOTIFS && overlay == OV_NONE) { ntView.linked = bleSecure; notifsRender(ntView, now); } }
  }
  // image transfer (custom screen, album art): every frame in order, never "latest wins"
  { meow::Frame im; int guard = 0;
    while (guard++ < 32 && bleTakeImg(im)) {
      if (im.type == meow::MSG_IMG_BEGIN) { meow::ImgBegin b; if (meow::parseImgBegin(im.payload, im.len, b)) imgRx.onBegin(b, now); }
      else if (im.type == meow::MSG_IMG_DATA) imgRx.onData(im.payload, im.len, now);
      else if (im.type == meow::MSG_IMG_END) imgRx.onEnd(im.payload, im.len);
      else if (im.type == meow::MSG_IMG_CTRL && im.len == 1) imgRx.onCtrl(im.payload[0]);
    }
  }
  if (bleTake(meow::MSG_HELLO, f)) { /* the status notification the app is already subscribed to answers the hello */ }
}

// ------------------------------- BOOT button -------------------------------
static void forgetEverything() {
  overlayReset();
  bleForgetPhones(); wifiForget();
  delay(1800);                                         // one-off, deliberate: the user is reading the message
  ESP.restart();
}
static void buttonService(uint32_t now) {
  static bool was = false; static uint32_t downAt = 0;
  bool down = digitalRead(PIN_BTN) == LOW;
  if (down && !was) { was = true; downAt = now; }
  else if (!down && was) {
    was = false; uint32_t held = now - downAt;
    if (held >= 10000) forgetEverything();
    else if (held >= 3000) {                           // open the pairing window for a new phone
      pairingOpenUntil = now + PAIRING_WINDOW_MS;
      overlay = OV_MODE; overlayUntil = now + 6000; overlayPairingMode(PAIRING_WINDOW_MS / 1000);
    } else if (held >= 40) { setScreen(nextScreen(curScreen)); }
  }
}

// ------------------------------- setup / loop --------------------------------
void setup() {
  Serial.begin(115200);
  if (PIN_BL >= 0) { pinMode(PIN_BL, OUTPUT); digitalWrite(PIN_BL, HIGH); }
  pinMode(PIN_BTN, INPUT_PULLUP);
  strncpy(tzCur, TZ_INFO, sizeof tzCur - 1);

  SPI.begin(PIN_SCK, PIN_MISO, PIN_MOSI, PIN_CS);
  tft.init(TFT_W, TFT_H, TFT_SPI_MODE);
  tft.setSPISpeed(SPI_HZ);
  tft.invertDisplay(TFT_INVERT);
  tft.setRotation(TFT_ROTATION);
  tft.fillScreen(C_BG);

  // The 60 KB canvas is allocated BEFORE Wi-Fi and Bluetooth start, while the heap is still one large block.
  canvas = new GFXcanvas16(CANVAS_W, CANVAS_H);
  if (!canvas->getBuffer()) { delete canvas; canvas = nullptr; }
  Serial.printf("Canvas: %s, free heap: %u\n", canvas ? "OK" : "FAILED (static cat)", ESP.getFreeHeap());

  wifiLoad();
  customBoot();                                         // mount LittleFS, find the stored custom design (if any)
  clockEnter(false);                                    // placeholder "--:--" until the time is known

  WiFi.mode(WIFI_STA);
  WiFi.persistent(false);
  WiFi.setAutoReconnect(true);
  if (haveWifi) wifiBeginNow(millis());
  bleBegin();
  Serial.printf("Ready, free heap: %u, phones paired: %d\n", ESP.getFreeHeap(), bleBonds());
}

void loop() {
  uint32_t now = millis();

  wifiService(now);
  buttonService(now);

  // pairing window: always open while no phone is paired; the button opens it for a limited time otherwise
  bool open = bleBonds() == 0 || (int32_t)(pairingOpenUntil - now) > 0;
  blePairingOpen = open;
  if (bleAuthDone) { bleAuthDone = false; pairingOpenUntil = 0; if (overlay != OV_NONE) { overlay = OV_NONE; needRedraw = true; } }

  meow::Status st;
  st.fwMajor = FW_MAJOR; st.fwMinor = FW_MINOR; st.screen = curScreen; st.wifiState = wifiState; st.wifiReason = wifiReason;
  st.timeValid = timeValid(); st.pairingOpen = open; st.hasWifi = haveWifi; st.bonds = (uint8_t)bleBonds();
  st.stepsFresh = stepsView.haveData && (now - stepsRxAt) < STEPS_STALE_MS; st.musicFresh = musicView.haveData && bleSecure;
  uint32_t kb = ESP.getFreeHeap() / 1024; st.heapKb = kb > 255 ? 255 : (uint8_t)kb;
  bleService(now, st, imgRx.status());
  handleFrames(now);
  imgRx.service(now);
  lyrService(now);

  // pairing passkey: shown for as long as the phone is still pairing
  uint32_t code;
  if (bleTakePasskey(code)) { overlay = OV_CODE; overlayUntil = now + PASSKEY_SHOW_MS; overlayPairingCode(code); }
  if (overlay != OV_NONE && ((int32_t)(overlayUntil - now) <= 0 || (overlay == OV_CODE && !bleLinked))) { overlay = OV_NONE; needRedraw = true; }

  // keep the screens' "phone connected" flag current
  static bool lastSecure = false;
  if (lastSecure != bleSecure) {
    lastSecure = bleSecure; stepsView.linked = bleSecure; stView.linked = bleSecure; ntView.linked = bleSecure; cuView.linked = bleSecure;
    if (!bleSecure) { if (imgRx.state() == meow::XFER_RECEIVING) imgRx.onCtrl(meow::CTRL_ABORT); bleFlushImg(); bleFlushLyric(); lyrClear(); }   // a half-sent image is dropped, the stored one stays
    if (overlay == OV_NONE) {
      if (curScreen == meow::SCREEN_STEPS) stepsRender(stepsView);
      else if (curScreen == meow::SCREEN_SCREENTIME) screenTimeRender(stView);
      else if (curScreen == meow::SCREEN_NOTIFS) notifsRender(ntView, now);
    }
  }
  musicView.linked = bleSecure;

  if (overlay != OV_NONE) return;                       // a message is on screen: leave it alone
  if (needRedraw) showScreen(now);
  switch (curScreen) {
    case meow::SCREEN_STEPS: break;                    // static: redrawn only when new data arrives
    case meow::SCREEN_SCREENTIME: break;               // static as well
    case meow::SCREEN_NOTIFS: { static uint32_t lastNt = 0; if (now - lastNt >= 60000UL) { lastNt = now; notifsRender(ntView, now); } break; }   // ages tick once a minute
    case meow::SCREEN_CUSTOM: customTick(cuView); break;
    case meow::SCREEN_MUSIC: musicTick(musicView, now); break;
    default: clockTick(now, wifiUp()); break;
  }
}
