// Bluetooth LE link to the phone, built on NimBLE-Arduino 2.x (written against the 2.5.2 headers).
//
// Security model
//   * Both characteristics require an ENCRYPTED and AUTHENTICATED link (LE Secure Connections, MITM protected, bonded).
//   * The device is "display only": during pairing it shows a random 6-digit passkey on the TFT and the phone user types it in
//     (the standard Android passkey dialog). Nobody who cannot see the screen can pair.
//   * New phones may pair only while the pairing window is open (no phone paired yet, or the BOOT button was held 3 s). When it is
//     closed a pairing attempt gets a code that is never displayed, so it cannot succeed.
//   * Wi-Fi details travel only over this encrypted link. Nothing here ever sends them back.
// Threading: NimBLE callbacks run on the BLE host task. They never touch the display; they only copy data into mailboxes that the main
// loop drains (one "latest frame" slot per message type, so a slow loop can never overflow anything).
#pragma once
#include <NimBLEDevice.h>
#include "protocol.h"
#include "config.h"

static const char* const BLE_SERVICE_UUID = "4d656f77-1000-4a3b-9c2d-0e7f6a5b4c3d";
static const char* const BLE_RX_UUID      = "4d656f77-1001-4a3b-9c2d-0e7f6a5b4c3d";
static const char* const BLE_STATUS_UUID  = "4d656f77-1002-4a3b-9c2d-0e7f6a5b4c3d";

static NimBLEServer* bleServer = nullptr;
static NimBLECharacteristic* bleRx = nullptr;
static NimBLECharacteristic* bleStatus = nullptr;
static char bleName[20] = "MeowIsland";

static portMUX_TYPE bleMux = portMUX_INITIALIZER_UNLOCKED;
static meow::Frame bleSlot[16];                      // indexed by message type 1..15 (state messages: only the newest of each type matters)
static volatile uint16_t bleDirty = 0;               // bit n = bleSlot[n] holds an unread frame
// Image transfer frames (BEGIN/DATA/END/CTRL) must NOT be "latest wins": every chunk matters. They go through a small bounded queue; when it is
// full a frame is dropped and the phone simply resends (the receiver only accepts chunks in order and reports where it is).
constexpr uint8_t IMGQ_N = 20;
static meow::Frame imgQ[IMGQ_N];
static volatile uint8_t imgQHead = 0, imgQTail = 0;
// The phone-rendered lyric mask also arrives as a burst of chunks that must all be handled. It has its own small queue so a big
// image transfer cannot make the lyrics stutter (and vice versa).
constexpr uint8_t LYRQ_N = 16;
static meow::Frame lyrQ[LYRQ_N];
static volatile uint8_t lyrQHead = 0, lyrQTail = 0;
static meow::FrameAssembler bleAssembler;            // touched only by the BLE task

static volatile bool bleLinked = false;              // a phone is connected
static volatile bool bleSecure = false;              // ...and the link is encrypted + authenticated
static volatile bool blePairingOpen = false;         // written by the main loop, read by the BLE task
static volatile bool bleAuthDone = false;            // a pairing just completed (main loop closes the window)
static volatile bool bleAdvDirty = true;             // advertising data needs rebuilding
static volatile uint16_t bleConn = 0xFFFF;           // connection handle of the current phone
static volatile uint32_t blePasskey = 0;
static volatile bool blePasskeyReady = false;
// Link hygiene. A phone that connects but never completes security must not hold the (only) connection forever, and a pairing attempt made while
// the pairing window is closed must fail at once instead of leaving the phone waiting for a code nobody can see.
constexpr uint32_t BLE_INSECURE_MAX_MS = 20000;      // a link that is still not encrypted + authenticated after this long is dropped
constexpr uint32_t BLE_PAIRING_MAX_MS  = 120000;     // ...unless a pairing is in progress (the user is typing the code): then this long
static volatile uint32_t bleLinkedAt = 0;            // millis() when the current phone connected
static volatile bool blePairingActive = false;       // a passkey was displayed for the current link
static volatile bool bleRejectPairing = false;       // pairing was attempted while the window is closed: the main loop drops the link

class MeowServerCb : public NimBLEServerCallbacks {
 public:
  void onConnect(NimBLEServer*, NimBLEConnInfo& info) override { bleConn = info.getConnHandle(); bleLinked = true; bleSecure = false; bleAssembler.reset(); bleLinkedAt = millis(); blePairingActive = false; bleRejectPairing = false; }
  void onDisconnect(NimBLEServer*, NimBLEConnInfo&, int) override { bleLinked = false; bleSecure = false; bleAssembler.reset(); bleAdvDirty = true; blePairingActive = false; bleRejectPairing = false; bleConn = 0xFFFF; imgQTail = imgQHead; lyrQTail = lyrQHead; }
  uint32_t onPassKeyDisplay() override {
    uint32_t code = 100000 + (esp_random() % 900000);
    if (blePairingOpen) { blePasskey = code; blePasskeyReady = true; blePairingActive = true; bleLinkedAt = millis(); }   // shown on the TFT by the main loop
    else bleRejectPairing = true;                                             // closed: nobody could ever read this code, so refuse right away
    return code;
  }
  void onAuthenticationComplete(NimBLEConnInfo& info) override {
    blePairingActive = false;
    if (info.isEncrypted() && info.isAuthenticated()) { bleSecure = true; if (info.isBonded()) bleAuthDone = true; }
    else if (bleServer) bleServer->disconnect(info);                          // unauthenticated link: drop it
  }
};

class MeowRxCb : public NimBLECharacteristicCallbacks {
 public:
  void onWrite(NimBLECharacteristic* chr, NimBLEConnInfo& info) override {
    if (!info.isEncrypted() || !info.isAuthenticated()) { if (bleServer) bleServer->disconnect(info); return; }   // defence in depth
    const NimBLEAttValue& v = chr->getValue();
    const uint8_t* d = v.data(); size_t n = v.length();
    meow::Frame f;
    for (size_t i = 0; i < n; i++) {
      if (bleAssembler.push(d[i], millis(), f) && f.type >= 1 && f.type <= meow::MSG_LAST) {
        portENTER_CRITICAL(&bleMux);
        if (f.type == meow::MSG_LYRIC) { uint8_t nx = (uint8_t)((lyrQHead + 1) % LYRQ_N); if (nx != lyrQTail) { lyrQ[lyrQHead] = f; lyrQHead = nx; } }
        else if (f.type >= meow::MSG_IMG_BEGIN && f.type < meow::MSG_LYRIC) { uint8_t nx = (uint8_t)((imgQHead + 1) % IMGQ_N); if (nx != imgQTail) { imgQ[imgQHead] = f; imgQHead = nx; } }
        else { bleSlot[f.type] = f; bleDirty |= (uint16_t)(1u << f.type); }
        portEXIT_CRITICAL(&bleMux);
      }
    }
  }
};
static MeowServerCb bleServerCb;
static MeowRxCb bleRxCb;

// Takes the newest unread frame of `type`, if any.
static bool bleTake(uint8_t type, meow::Frame& out) {
  bool got = false;
  portENTER_CRITICAL(&bleMux);
  if (bleDirty & (1u << type)) { out = bleSlot[type]; bleDirty &= (uint16_t)~(1u << type); got = true; }
  portEXIT_CRITICAL(&bleMux);
  return got;
}
// Pops the oldest queued image frame, if any.
static bool bleTakeImg(meow::Frame& out) {
  bool got = false;
  portENTER_CRITICAL(&bleMux);
  if (imgQTail != imgQHead) { out = imgQ[imgQTail]; imgQTail = (uint8_t)((imgQTail + 1) % IMGQ_N); got = true; }
  portEXIT_CRITICAL(&bleMux);
  return got;
}
static void bleFlushImg() { portENTER_CRITICAL(&bleMux); imgQTail = imgQHead; portEXIT_CRITICAL(&bleMux); }
// Pops the oldest queued lyric chunk, if any.
static bool bleTakeLyric(meow::Frame& out) {
  bool got = false;
  portENTER_CRITICAL(&bleMux);
  if (lyrQTail != lyrQHead) { out = lyrQ[lyrQTail]; lyrQTail = (uint8_t)((lyrQTail + 1) % LYRQ_N); got = true; }
  portEXIT_CRITICAL(&bleMux);
  return got;
}
static void bleFlushLyric() { portENTER_CRITICAL(&bleMux); lyrQTail = lyrQHead; portEXIT_CRITICAL(&bleMux); }
static bool bleTakePasskey(uint32_t& code) { if (!blePasskeyReady) return false; code = blePasskey; blePasskeyReady = false; return true; }
static int  bleBonds() { return NimBLEDevice::getNumBonds(); }

static void bleBuildAdvertising(bool pairingOpen) {
  NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
  adv->stop();
  NimBLEAdvertisementData ad, sr;
  ad.setFlags(BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP);
  ad.addServiceUUID(BLE_SERVICE_UUID);
  sr.setName(bleName);
  const uint8_t mfg[] = {0xFF, 0xFF, 'M', 'I', (uint8_t)(pairingOpen ? 1 : 0), (uint8_t)bleBonds()};   // company id 0xFFFF (test), tag, flags, bonds
  sr.setManufacturerData(mfg, sizeof mfg);
  adv->setAdvertisementData(ad);
  adv->setScanResponseData(sr);
  adv->enableScanResponse(true);
  adv->start();
  bleAdvDirty = false;
}

static void bleBegin() {
  uint64_t mac = ESP.getEfuseMac();
  snprintf(bleName, sizeof bleName, "MeowIsland-%02X%02X", (unsigned)((mac >> 32) & 0xFF), (unsigned)((mac >> 40) & 0xFF));
  NimBLEDevice::init(bleName);
  NimBLEDevice::setSecurityAuth(true, true, true);              // bonding + MITM protection + LE Secure Connections
  NimBLEDevice::setSecurityIOCap(BLE_HS_IO_DISPLAY_ONLY);       // we can show a code but cannot type one
  NimBLEDevice::setMTU(247);
  bleServer = NimBLEDevice::createServer();
  bleServer->setCallbacks(&bleServerCb);
  bleServer->advertiseOnDisconnect(true);
  NimBLEService* svc = bleServer->createService(BLE_SERVICE_UUID);
  bleRx = svc->createCharacteristic(BLE_RX_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_ENC | NIMBLE_PROPERTY::WRITE_AUTHEN);
  bleRx->setCallbacks(&bleRxCb);
  bleStatus = svc->createCharacteristic(BLE_STATUS_UUID, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::READ_ENC | NIMBLE_PROPERTY::READ_AUTHEN | NIMBLE_PROPERTY::NOTIFY);
  uint8_t zero[meow::STATUS_FULL_LEN] = {0};
  bleStatus->setValue(zero, sizeof zero);
  bleServer->start();
  bleBuildAdvertising(true);
}

// Called every loop: keeps the advertisement truthful (pairing open? how many phones?), publishes the status, drops stalled frames.
static void bleService(uint32_t now, const meow::Status& st, const meow::XferStatus& xs) {
  static uint8_t lastStatus[meow::STATUS_FULL_LEN] = {0};
  static bool lastOpen = true; static int lastBonds = -1;
  bleAssembler.expire(now);
  if (bleLinked && bleServer && bleConn != 0xFFFF) {
    bool drop = false;
    if (bleRejectPairing) { bleRejectPairing = false; drop = true; }
    else if (!bleSecure) { uint32_t limit = blePairingActive ? BLE_PAIRING_MAX_MS : BLE_INSECURE_MAX_MS; if ((uint32_t)(now - bleLinkedAt) > limit) drop = true; }
    static uint32_t lastDrop = 0;
    if (drop && (uint32_t)(now - lastDrop) >= 1000) { lastDrop = now; bleServer->disconnect(bleConn); }      // retried once a second until the stack reports the disconnect
  }
  int bonds = bleBonds();
  if (!bleLinked && (bleAdvDirty || lastOpen != blePairingOpen || lastBonds != bonds)) { lastOpen = blePairingOpen; lastBonds = bonds; bleBuildAdvertising(blePairingOpen); }
  uint8_t cur[meow::STATUS_FULL_LEN]; meow::encodeStatus(st, cur); meow::encodeStatusExt(xs, cur + meow::STATUS_LEN);
  if (memcmp(cur, lastStatus, sizeof cur) != 0) {
    memcpy(lastStatus, cur, sizeof cur);
    bleStatus->setValue(cur, sizeof cur);
    if (bleLinked && bleSecure) bleStatus->notify();
  }
}

// Forget every paired phone (and drop the current link). The window then opens automatically because no phone is paired.
static void bleForgetPhones() {
  if (bleServer && bleLinked) bleServer->disconnect(bleConn);
  NimBLEDevice::deleteAllBonds();
  bleAdvDirty = true;
}
