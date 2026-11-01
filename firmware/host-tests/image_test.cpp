// Desktop tests for firmware/MeowIsland/mimg.h + image_rx.h + the new protocol messages. No Arduino, no GFX library needed.
// Build: g++ -std=c++17 -fsanitize=address,undefined -I ../MeowIsland image_test.cpp -o out/image_test && out/image_test ../../tests/mimg_vectors.txt ../../tests/device_vectors.txt
#include "../MeowIsland/protocol.h"
#include "../MeowIsland/mimg.h"
#include "../MeowIsland/image_rx.h"
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>
#include <fstream>
#include <random>
using namespace meow;
typedef std::vector<uint8_t> Bytes;

static int passed = 0, failed = 0;
#define CHECK(c, msg) do { if (c) passed++; else { failed++; printf("FAIL: %s  (line %d)\n", msg, __LINE__); } } while (0)

static Bytes unhex(const std::string& h) { Bytes v; for (size_t i = 0; i + 1 < h.size(); i += 2) v.push_back((uint8_t)strtol(h.substr(i, 2).c_str(), nullptr, 16)); return v; }

// ---- memory reader for the decoder ----
struct Mem { const Bytes* b; size_t at = 0; };
static size_t memRead(void* c, uint8_t* buf, size_t n) { Mem* m = (Mem*)c; size_t k = std::min(n, m->b->size() - m->at); if (k) memcpy(buf, m->b->data() + m->at, k); m->at += k; return k; }
static bool validate(const Bytes& f, uint16_t w = 0, uint16_t h = 0) { Mem m{&f}; return mimgValidate(memRead, &m, (uint32_t)f.size(), w, h); }
static bool decodeAll(const Bytes& f, std::vector<uint16_t>& px, uint16_t& w, uint16_t& h) {
  Mem m{&f}; MimgDecoder d; if (!d.begin(memRead, &m, (uint32_t)f.size())) return false;
  w = d.header().w; h = d.header().h; px.assign((size_t)w * h, 0);
  for (int y = 0; y < h;) { int n = d.rows(&px[(size_t)y * w], std::min(7, h - y)); if (n == 0) return false; y += n; }
  return !d.failed();
}

// ---- tiny reference encoder used only by the tests (the real one is src/core/customscreen.ts) ----
static Bytes encode(const std::vector<uint16_t>& px, uint16_t w, uint16_t h, std::vector<uint16_t> pal) {
  Bytes o = {'M', 'I', 1, 0}; auto put16 = [&](Bytes& b, uint16_t v) { b.push_back(v & 255); b.push_back(v >> 8); };
  put16(o, w); put16(o, h); put16(o, (uint16_t)pal.size()); put16(o, 0);
  Bytes data; size_t i = 0; Bytes lit;
  auto idxOf = [&](uint16_t c) { for (size_t k = 0; k < pal.size(); k++) if (pal[k] == c) return (uint8_t)k; return (uint8_t)0; };
  auto flush = [&]() { size_t p = 0; while (p < lit.size()) { size_t n = std::min<size_t>(128, lit.size() - p); data.push_back(0x80 | (n - 1)); data.insert(data.end(), lit.begin() + p, lit.begin() + p + n); p += n; } lit.clear(); };
  while (i < px.size()) {
    size_t r = 1; while (i + r < px.size() && px[i + r] == px[i] && r < 65535) r++;
    if (r >= 3) { flush(); uint8_t ix = idxOf(px[i]); if (r > 127) { data.push_back(0); put16(data, (uint16_t)r); data.push_back(ix); } else { data.push_back((uint8_t)r); data.push_back(ix); } i += r; }
    else { lit.push_back(idxOf(px[i])); i++; }
  }
  flush();
  o.push_back(data.size() & 255); o.push_back((data.size() >> 8) & 255); o.push_back((data.size() >> 16) & 255); o.push_back((data.size() >> 24) & 255);
  for (auto c : pal) put16(o, c);
  o.insert(o.end(), data.begin(), data.end());
  return o;
}
static std::vector<uint16_t> sampleImage(uint16_t w, uint16_t h, std::vector<uint16_t>& pal) {
  pal = {0x0000, 0xFFFF, 0xF800, 0x07E0, 0x781F};
  std::vector<uint16_t> px((size_t)w * h, pal[0]);
  for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) { if ((x / 8 + y / 8) % 2 == 0 && y > 20 && y < h - 20) px[(size_t)y * w + x] = pal[1]; if (y < 10) px[(size_t)y * w + x] = pal[2 + (x / 40) % 3]; }
  return px;
}

// ---- in-memory sink ----
struct MemSink : RxSink {
  Bytes stored, tmp, art; uint32_t crc = 0, artCrc = 0; bool open_ = false; bool failOpen = false; uint8_t openReason = XR_NO_SPACE; bool failWrite = false; int commits = 0, writes = 0, aborts = 0;
  uint16_t W = 240, H = 320;
  uint32_t storedCustomCrc() override { return crc; }
  uint32_t storedArtCrc() override { return artCrc; }
  bool open(uint8_t, uint32_t, uint16_t, uint16_t, uint8_t& reason) override { if (failOpen) { reason = openReason; return false; } tmp.clear(); open_ = true; return true; }
  bool write(uint8_t kind, const uint8_t* d, size_t n) override { if (failWrite) return false; writes++; tmp.insert(tmp.end(), d, d + n); (void)kind; return true; }
  bool commit(uint8_t kind, uint32_t c, uint8_t& reason) override {
    if (kind == KIND_CUSTOM) { if (!validate(tmp, W, H)) { reason = XR_BAD_IMAGE; tmp.clear(); return false; } stored = tmp; crc = c; commits++; }
    else { art = tmp; artCrc = c; }
    tmp.clear(); open_ = false; return true;
  }
  void abort(uint8_t) override { tmp.clear(); open_ = false; aborts++; }
  void deleteCustom() override { stored.clear(); crc = 0; }
  void clearArt() override { art.clear(); artCrc = 0; }
};

// ---- the phone's sender, mirroring CustomScreen.kt: window of 8 chunks, rewinds to the device's nextSeq after a stall ----
struct Frame2 { uint8_t type; Bytes p; };
struct Channel {   // lossy, duplicating, reordering link: each frame is delivered 0, 1 or 2 times, possibly swapped with the next one
  std::mt19937 rng; double drop, dup; Channel(uint32_t s, double d, double u) : rng(s), drop(d), dup(u) {}
  std::vector<Frame2> pass(std::vector<Frame2> in) {
    std::vector<Frame2> out; std::uniform_real_distribution<double> u(0, 1);
    for (auto& f : in) { if (u(rng) < drop) continue; out.push_back(f); if (u(rng) < dup) out.push_back(f); }
    for (size_t i = 0; i + 1 < out.size(); i++) if (u(rng) < 0.1) std::swap(out[i], out[i + 1]);
    return out;
  }
};
static void deliver(ImageReceiver& rx, const Frame2& f, uint32_t now) {
  if (f.type == MSG_IMG_BEGIN) { ImgBegin b; if (parseImgBegin(f.p.data(), (uint8_t)f.p.size(), b)) rx.onBegin(b, now); }
  else if (f.type == MSG_IMG_DATA) rx.onData(f.p.data(), (uint8_t)f.p.size(), now);
  else if (f.type == MSG_IMG_END) rx.onEnd(f.p.data(), (uint8_t)f.p.size());
}
static Bytes beginPayload(uint8_t id, uint8_t kind, const Bytes& file, uint16_t chunk, uint16_t w, uint16_t h, uint32_t crcOverride = 0, bool useOverride = false) {
  Bytes p(IMG_BEGIN_LEN); p[0] = id; p[1] = kind; wr32(&p[2], (uint32_t)file.size()); wr32(&p[6], useOverride ? crcOverride : crc32Of(file.data(), file.size())); wr16(&p[10], chunk); wr16(&p[12], w); wr16(&p[14], h); return p;
}
// Runs a whole transfer through the channel. Returns the final device state; `steps` counts loop iterations.
static uint8_t runTransfer(ImageReceiver& rx, const Bytes& file, uint8_t id, uint8_t kind, uint16_t w, uint16_t h, Channel& ch, int& steps, uint32_t crcOverride = 0, bool useOverride = false) {
  const uint16_t CHUNK = 180; const int WINDOW = 8; uint32_t now = 1000;
  uint16_t chunks = (uint16_t)((file.size() + CHUNK - 1) / CHUNK); int nextToSend = 0; int stalls = 0; uint16_t lastAcked = 0; bool endSent = false;
  for (steps = 0; steps < 5000; steps++) {
    now += 50; XferStatus st = rx.status();
    if (st.id == id && (st.state == XFER_DONE || st.state == XFER_FAILED)) return st.state;
    std::vector<Frame2> out;
    if (st.state != XFER_RECEIVING || st.id != id) out.push_back({MSG_IMG_BEGIN, beginPayload(id, kind, file, CHUNK, w, h, crcOverride, useOverride)});
    else {
      uint16_t acked = st.nextSeq;
      if (acked != lastAcked) { lastAcked = acked; stalls = 0; } else stalls++;
      if (stalls >= 3) { nextToSend = acked; endSent = false; stalls = 0; }              // 150 ms without progress: rewind to what the device really has
      if (nextToSend < acked) nextToSend = acked;
      while (nextToSend < chunks && nextToSend - acked < WINDOW) {
        size_t off = (size_t)nextToSend * CHUNK; size_t n = std::min<size_t>(CHUNK, file.size() - off);
        Bytes p = {id, (uint8_t)(nextToSend & 255), (uint8_t)(nextToSend >> 8)}; p.insert(p.end(), file.begin() + off, file.begin() + off + n); out.push_back({MSG_IMG_DATA, p}); nextToSend++;
      }
      if (acked == chunks && (!endSent || stalls == 2)) { out.push_back({MSG_IMG_END, {id}}); endSent = true; }
    }
    for (auto& f : ch.pass(out)) deliver(rx, f, now);
    rx.service(now);
  }
  return 0xFF;   // gave up
}

static int g_vectorsChecked = 0;

int main(int argc, char** argv) {
  // ------------------------------------------------------------------ CRC-32
  CHECK(crc32Of((const uint8_t*)"123456789", 9) == 0xCBF43926u, "crc32 check value of the IEEE polynomial");
  CHECK(crc32Of((const uint8_t*)"", 0) == 0, "crc32 of nothing");
  { Bytes a(1000); for (size_t i = 0; i < a.size(); i++) a[i] = (uint8_t)(i * 31 + 7); uint32_t c = crc32Begin(); c = crc32Update(c, a.data(), 333); c = crc32Update(c, a.data() + 333, 667); CHECK(crc32End(c) == crc32Of(a.data(), a.size()), "crc32 is incremental"); }

  // ------------------------------------------------------------------ image format
  std::vector<uint16_t> pal; auto px = sampleImage(240, 320, pal); Bytes img = encode(px, 240, 320, pal);
  { std::vector<uint16_t> out; uint16_t w, h; CHECK(decodeAll(img, out, w, h) && w == 240 && h == 320 && out == px, "encode -> decode round trip, 240x320"); CHECK(img.size() < 20000, "a busy 8px checkerboard design still fits far inside the 64 KB limit"); CHECK(validate(img, 240, 320), "valid file validates"); CHECK(!validate(img, 320, 240), "wrong expected size rejected"); }
  { Bytes b = img; b.resize(b.size() - 1); CHECK(!validate(b), "truncated file rejected"); b = img; b.push_back(0); CHECK(!validate(b), "extra byte rejected (length must match the header exactly)"); }
  { Bytes b = img; b[0] = 'X'; CHECK(!validate(b), "bad magic"); b = img; b[2] = 2; CHECK(!validate(b), "unknown version"); b = img; b[3] = 1; CHECK(!validate(b), "reserved flags"); b = img; b[8] = 0; b[9] = 0; CHECK(!validate(b), "zero colours"); b = img; b[8] = 1; b[9] = 1; CHECK(!validate(b), "257 colours"); b = img; b[4] = 0; b[5] = 0; CHECK(!validate(b), "zero width"); }
  { Bytes b = img; size_t dataAt = 16 + 2 * pal.size(); b[dataAt + 1] = 200; CHECK(!validate(b), "palette index out of range"); }
  { // a run that overshoots the pixel count, and a stream that ends early
    std::vector<uint16_t> p2(10 * 10, 0x1234), pl = {0x1234}; Bytes ok = encode(p2, 10, 10, pl); CHECK(validate(ok), "10x10 flat image");
    Bytes over = {'M','I',1,0, 10,0, 10,0, 1,0, 0,0, 4,0,0,0, 0x34,0x12, 0x00, 101, 0, 0};  // long run of 101 > 100 pixels
    CHECK(!validate(over), "run longer than the image is rejected");
    Bytes under = {'M','I',1,0, 10,0, 10,0, 1,0, 0,0, 3,0,0,0, 0x34,0x12, 99, 0};       // only 99 pixels
    under[12] = 2; CHECK(!validate(under), "stream shorter than the image is rejected");
    Bytes zeroRun = {'M','I',1,0, 10,0, 10,0, 1,0, 0,0, 4,0,0,0, 0x34,0x12, 0x00, 0, 0, 0}; CHECK(!validate(zeroRun), "zero-length long run rejected"); }
  { std::mt19937 rng(42); int accepted = 0;     // random corruption must never crash (ASAN/UBSAN) and almost never validate
    for (int t = 0; t < 3000; t++) { Bytes b = img; int flips = 1 + rng() % 6; for (int k = 0; k < flips; k++) b[rng() % b.size()] ^= (uint8_t)(1u << (rng() % 8)); if (rng() % 7 == 0) b.resize(rng() % b.size()); accepted += validate(b) ? 1 : 0; }
    CHECK(accepted < 3000, "corrupted images are mostly rejected, none crash");
    for (int t = 0; t < 3000; t++) { Bytes b(rng() % 300); for (auto& x : b) x = (uint8_t)rng(); if (b.size() > 4 && rng() % 2) { b[0] = 'M'; b[1] = 'I'; b[2] = 1; b[3] = 0; } validate(b); } passed++; }

  // ------------------------------------------------------------------ vectors produced by the TypeScript encoder (src/core/customscreen.ts)
  if (argc > 1) {
    std::ifstream in(argv[1]); std::string line;
    while (std::getline(in, line)) {
      size_t a = line.find('|'), b = line.find('|', a + 1), c = line.find('|', b + 1); if (a == std::string::npos || b == std::string::npos || c == std::string::npos) continue;
      std::string name = line.substr(0, a); Bytes file = unhex(line.substr(a + 1, b - a - 1)); uint32_t pixCrc = (uint32_t)strtoul(line.substr(b + 1, c - b - 1).c_str(), nullptr, 16); std::string dims = line.substr(c + 1);
      std::vector<uint16_t> out; uint16_t w = 0, h = 0; bool ok = decodeAll(file, out, w, h);
      Bytes raw; for (auto v : out) { raw.push_back(v & 255); raw.push_back(v >> 8); }
      CHECK(ok, (name + ": decoded").c_str()); CHECK(dims == std::to_string(w) + "x" + std::to_string(h), (name + ": size").c_str());
      CHECK(crc32Of(raw.data(), raw.size()) == pixCrc, (name + ": pixels identical to the TypeScript source image").c_str()); g_vectorsChecked++;
    }
    CHECK(g_vectorsChecked >= 5, "TypeScript vectors loaded");
  }

  // ------------------------------------------------------------------ new protocol messages
  { ScreenTimeData d; Bytes p = {0x09, 0x84, 0x03, 0x58, 0x02, 2, 0x5A, 0x00, 8, 'I','n','s','t','a','g','r','a', 0x1E, 0x00, 3, 'Y','o','u'}; p[0] = 9;
    // flags 9 = has data + goal on, total 0x0384 = 900 min, goal 0x0258 = 600 min
    CHECK(parseScreenTime(p.data(), (uint8_t)p.size(), d) && d.hasData && d.goalOn && d.totalMin == 900 && d.goalMin == 600 && d.n == 2 && d.appMin[0] == 90 && strcmp(d.appName[0], "Instagra") == 0 && strcmp(d.appName[1], "You") == 0, "screen time frame parses");
    for (size_t cut = 0; cut < p.size(); cut++) { ScreenTimeData t; CHECK(!parseScreenTime(p.data(), (uint8_t)cut, t), "screen time: every truncation is rejected"); }
    Bytes extra = p; extra.push_back(0); CHECK(!parseScreenTime(extra.data(), (uint8_t)extra.size(), d), "screen time: trailing byte rejected");
    Bytes big = p; big[1] = 0xFF; big[2] = 0xFF; CHECK(!parseScreenTime(big.data(), (uint8_t)big.size(), d), "screen time: more than 24 h rejected");
    Bytes five = {1, 0, 0, 0, 0, 5}; CHECK(!parseScreenTime(five.data(), (uint8_t)five.size(), d), "screen time: five apps rejected");
    Bytes none = {2, 0, 0, 0, 0, 0}; ScreenTimeData z; CHECK(parseScreenTime(none.data(), 6, z) && !z.hasData && z.needsPermission && z.n == 0, "screen time: permission-needed state");
    Bytes nogoal = {9, 10, 0, 0, 0, 0}; CHECK(parseScreenTime(nogoal.data(), 6, z) && !z.goalOn, "screen time: goal flag without a goal is ignored"); }
  { NotifData d; Bytes p = {1, 2, 5, 0, 8, 'W','h','a','t','s','A','p','p', 3, 'A','n','a', 5, 'H','e','l','l','o', 0x78, 0, 5, 'G','m','a','i','l', 0, 0}; CHECK(parseNotifs(p.data(), (uint8_t)p.size(), d) && d.enabled && !d.needsAccess && d.n == 2 && d.items[0].ageMin == 5 && strcmp(d.items[0].title, "Ana") == 0 && d.items[1].ageMin == 120 && d.items[1].title[0] == 0 && d.items[1].text[0] == 0, "notification snapshot parses, empty title/text allowed");
    for (size_t cut = 0; cut < p.size(); cut++) { NotifData t; CHECK(!parseNotifs(p.data(), (uint8_t)cut, t), "notifs: every truncation is rejected"); }
    Bytes none = {2, 0}; NotifData z; CHECK(parseNotifs(none.data(), 2, z) && z.needsAccess && z.n == 0, "notifs: needs-access state");
    Bytes nine = {1, 9}; CHECK(!parseNotifs(nine.data(), 2, z), "notifs: nine entries rejected");
    Bytes longApp = {1, 1, 0, 0, 13}; longApp.insert(longApp.end(), 13, 'a'); longApp.insert(longApp.end(), {0, 0}); CHECK(!parseNotifs(longApp.data(), (uint8_t)longApp.size(), z), "notifs: over-long app name rejected"); }
  { Music2Data d; Bytes p = {2, 2, 7, 0, 7, 'S','P','O','T','I','F','Y', 3, 'o','n','e', 3, 't','w','o', 5, 't','h','r','e','e'}; CHECK(parseMusic2(p.data(), (uint8_t)p.size(), d) && d.synced && !d.hasArt && d.lyricState == 2 && d.lineIdx == 7 && strcmp(d.src, "SPOTIFY") == 0 && strcmp(d.cur, "two") == 0 && strcmp(d.next, "three") == 0, "music extras parse");
    for (size_t cut = 0; cut < p.size(); cut++) { Music2Data t; CHECK(!parseMusic2(p.data(), (uint8_t)cut, t), "music2: every truncation is rejected"); }
    Bytes bad = p; bad[1] = 99; CHECK(parseMusic2(bad.data(), (uint8_t)bad.size(), d) && d.lyricState == 0, "music2: unknown lyric state falls back to 0"); }
  { ImgBegin b; Bytes p(16, 0); p[0] = 3; p[1] = 0; wr32(&p[2], 5000); wr32(&p[6], 0xDEADBEEF); wr16(&p[10], 180); wr16(&p[12], 240); wr16(&p[14], 320);
    CHECK(parseImgBegin(p.data(), 16, b) && b.xfer == 3 && b.total == 5000 && b.crc == 0xDEADBEEF && b.chunk == 180 && b.w == 240 && b.h == 320, "image begin parses");
    CHECK(!parseImgBegin(p.data(), 15, b), "begin wrong length"); p[1] = 2; CHECK(!parseImgBegin(p.data(), 16, b), "begin unknown kind"); p[1] = 0; wr16(&p[10], 191); CHECK(!parseImgBegin(p.data(), 16, b), "begin chunk too big"); wr16(&p[10], 0); CHECK(!parseImgBegin(p.data(), 16, b), "begin zero chunk"); }
  { XferStatus s; s.state = XFER_DONE; s.id = 9; s.nextSeq = 0x1234; s.reason = XR_CRC; s.customCrc = 0xA1B2C3D4; s.artCrc = 0x44332211; uint8_t o[STATUS_EXT_LEN]; encodeStatusExt(s, o);
    const uint8_t want[] = {2, 9, 0x34, 0x12, 5, 0xD4, 0xC3, 0xB2, 0xA1, 0x11, 0x22, 0x33, 0x44}; CHECK(memcmp(o, want, sizeof want) == 0, "status extension bytes"); CHECK(STATUS_FULL_LEN == 23, "status is 23 bytes"); }

  // ------------------------------------------------------------------ receiver: clean link
  {
    MemSink sink; ImageReceiver rx(&sink, 240, 320); Channel clean(1, 0, 0); int steps;
    CHECK(runTransfer(rx, img, 1, KIND_CUSTOM, 240, 320, clean, steps) == XFER_DONE && sink.stored == img && sink.commits == 1 && rx.status().customCrc == crc32Of(img.data(), img.size()), "clean transfer stores exactly the file");
    int writesBefore = sink.writes; CHECK(runTransfer(rx, img, 2, KIND_CUSTOM, 240, 320, clean, steps) == XFER_DONE && sink.writes == writesBefore && sink.commits == 1, "sending the same image again writes nothing (flash is not rewritten)");
  }
  // ------------------------------------------------------------------ receiver: hostile links
  for (uint32_t seed = 1; seed <= 25; seed++) {
    MemSink sink; ImageReceiver rx(&sink, 240, 320); Channel bad(seed, 0.25, 0.2); int steps;
    uint8_t r = runTransfer(rx, img, (uint8_t)seed, KIND_CUSTOM, 240, 320, bad, steps);
    CHECK(r == XFER_DONE && sink.stored == img, ("25% loss + 20% duplicates + reordering still ends with the exact file (seed " + std::to_string(seed) + ")").c_str());
  }
  {   // replacing an image over a bad link: the old one stays valid until the new one is complete
    MemSink sink; ImageReceiver rx(&sink, 240, 320); Channel clean(7, 0, 0); int steps;
    runTransfer(rx, img, 1, KIND_CUSTOM, 240, 320, clean, steps); Bytes old = sink.stored;
    std::vector<uint16_t> pal2; auto px2 = sampleImage(240, 320, pal2); for (auto& v : px2) if (v == 0xFFFF) v = 0x001F; Bytes img2 = encode(px2, 240, 320, pal2);
    CHECK(runTransfer(rx, img2, 2, KIND_CUSTOM, 240, 320, clean, steps, 0x12345678, true) == XFER_FAILED && rx.status().reason == XR_CRC && sink.stored == old && sink.crc == crc32Of(old.data(), old.size()), "wrong CRC: transfer fails with CRC reason, the previous image is untouched");
    Bytes corrupt = img2; corrupt[40] ^= 0x55; uint32_t good = crc32Of(corrupt.data(), corrupt.size());   // CRC matches but the structure is broken
    corrupt[16 + 2 * pal2.size() + 1] = 250;  good = crc32Of(corrupt.data(), corrupt.size());
    CHECK(runTransfer(rx, corrupt, 3, KIND_CUSTOM, 240, 320, clean, steps) == XFER_FAILED && rx.status().reason == XR_BAD_IMAGE && sink.stored == old, "valid CRC but invalid image data: rejected, previous image untouched"); (void)good;
    CHECK(runTransfer(rx, img2, 4, KIND_CUSTOM, 240, 320, clean, steps) == XFER_DONE && sink.stored == img2, "a later good upload replaces it");
  }
  {   // refusals
    MemSink sink; ImageReceiver rx(&sink, 240, 320); Channel clean(1, 0, 0); int steps;
    Bytes huge(MIMG_MAX_FILE + 1, 0); CHECK(runTransfer(rx, huge, 1, KIND_CUSTOM, 240, 320, clean, steps) == XFER_FAILED && rx.status().reason == XR_TOO_LARGE, "oversized file refused at BEGIN, nothing written");
    CHECK(runTransfer(rx, img, 2, KIND_CUSTOM, 320, 240, clean, steps) == XFER_FAILED && rx.status().reason == XR_BAD_HEADER, "wrong screen size refused");
    sink.failOpen = true; sink.openReason = XR_NO_SPACE; CHECK(runTransfer(rx, img, 3, KIND_CUSTOM, 240, 320, clean, steps) == XFER_FAILED && rx.status().reason == XR_NO_SPACE, "no flash space is reported as such");
    sink.failOpen = false; sink.failWrite = true; CHECK(runTransfer(rx, img, 4, KIND_CUSTOM, 240, 320, clean, steps) == XFER_FAILED && rx.status().reason == XR_FLASH, "a flash write error fails the transfer");
    CHECK(sink.stored.empty(), "no failed attempt ever published a file");
  }
  {   // timeouts, abort, repeated BEGIN, foreign transfer ids
    MemSink sink; ImageReceiver rx(&sink, 240, 320); ImgBegin b; Bytes bp = beginPayload(5, KIND_CUSTOM, img, 180, 240, 320); parseImgBegin(bp.data(), 16, b);
    rx.onBegin(b, 1000); CHECK(rx.state() == XFER_RECEIVING, "receiving after BEGIN");
    Bytes d0 = {5, 0, 0}; d0.insert(d0.end(), img.begin(), img.begin() + 180); rx.onData(d0.data(), (uint8_t)d0.size(), 1100); CHECK(rx.status().nextSeq == 1, "first chunk accepted");
    rx.onBegin(b, 1200); CHECK(rx.state() == XFER_RECEIVING && rx.status().nextSeq == 1, "a repeated BEGIN keeps the progress");
    rx.onData(d0.data(), (uint8_t)d0.size(), 1300); CHECK(rx.status().nextSeq == 1, "duplicate chunk ignored");
    Bytes foreign = d0; foreign[0] = 9; foreign[1] = 1; rx.onData(foreign.data(), (uint8_t)foreign.size(), 1400); CHECK(rx.status().nextSeq == 1, "chunk of another transfer ignored");
    Bytes gap = {5, 3, 0, 1, 2, 3}; rx.onData(gap.data(), (uint8_t)gap.size(), 1500); CHECK(rx.status().nextSeq == 1, "chunk with a gap ignored");
    rx.service(1200 + RX_TIMEOUT_MS - 10); CHECK(rx.state() == XFER_RECEIVING, "not timed out yet"); rx.service(1200 + RX_TIMEOUT_MS + 10); CHECK(rx.state() == XFER_FAILED && rx.status().reason == XR_SEQUENCE && sink.stored.empty(), "a silent transfer times out and leaves nothing behind");
    rx.onBegin(b, 20000); rx.onCtrl(CTRL_ABORT); CHECK(rx.state() == XFER_IDLE, "abort returns to idle");
    ImageReceiver rx2(&sink, 240, 320); Channel clean(3, 0, 0); int steps; runTransfer(rx2, img, 1, KIND_CUSTOM, 240, 320, clean, steps); CHECK(sink.crc != 0, "stored"); rx2.onCtrl(CTRL_DELETE_CUSTOM); CHECK(sink.crc == 0 && sink.stored.empty(), "delete removes the stored image");
  }
  {   // album art goes to RAM, exact size only
    MemSink sink; ImageReceiver rx(&sink, 240, 320); Channel bad(11, 0.2, 0.1); int steps; Bytes art(96 * 96 * 2); for (size_t i = 0; i < art.size(); i++) art[i] = (uint8_t)(i * 13);
    CHECK(runTransfer(rx, art, 1, KIND_ART, 96, 96, bad, steps) == XFER_DONE && sink.art == art && sink.stored.empty(), "album art arrives intact and does not touch the custom screen");
    Bytes wrong(100); CHECK(runTransfer(rx, wrong, 2, KIND_ART, 96, 96, bad, steps) == XFER_FAILED && rx.status().reason == XR_BAD_HEADER, "album art with a size that does not match w x h x 2 refused");
    Bytes toobig(200 * 200 * 2); CHECK(runTransfer(rx, toobig, 3, KIND_ART, 200, 200, bad, steps) == XFER_FAILED, "album art larger than the buffer refused");
    rx.onCtrl(CTRL_CLEAR_ART); CHECK(sink.art.empty(), "clear art");
  }
  {   // fuzz: any byte soup through the receiver is harmless
    std::mt19937 rng(99); MemSink sink; ImageReceiver rx(&sink, 240, 320);
    for (int t = 0; t < 20000; t++) { Bytes p(rng() % 40); for (auto& x : p) x = (uint8_t)rng(); uint8_t k = rng() % 4; if (k == 0) { ImgBegin b; if (parseImgBegin(p.data(), (uint8_t)p.size(), b)) rx.onBegin(b, t); } else if (k == 1) rx.onData(p.data(), (uint8_t)p.size(), t); else if (k == 2) rx.onEnd(p.data(), (uint8_t)p.size()); else rx.onCtrl(p.empty() ? 0 : p[0]); rx.service(t); }
    passed++;
  }

  printf("image / protocol-extension tests: %d passed, %d failed (%d TypeScript vectors)\n", passed, failed, g_vectorsChecked);
  return failed ? 1 : 0;
}
