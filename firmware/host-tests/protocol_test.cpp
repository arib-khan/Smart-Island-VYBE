// Desktop test of firmware/MeowIsland/protocol.h against the bytes produced by the phone-side Kotlin code (tests/device_vectors.txt).
#include "../MeowIsland/protocol.h"
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>
#include <fstream>
using namespace meow;

static int passed = 0, failed = 0;
#define CHECK(c, msg) do { if (c) passed++; else { failed++; printf("FAIL: %s  (line %d)\n", msg, __LINE__); } } while (0)

static std::vector<uint8_t> unhex(const std::string& h) { std::vector<uint8_t> v; for (size_t i = 0; i + 1 < h.size(); i += 2) v.push_back((uint8_t)strtol(h.substr(i, 2).c_str(), nullptr, 16)); return v; }

static std::string summarize(const Frame& f) {
  char b[512];
  switch (f.type) {
    case MSG_HELLO: snprintf(b, sizeof b, "proto=%d", f.payload[0]); return b;
    case MSG_SCREEN: snprintf(b, sizeof b, "screen=%d", f.payload[0]); return b;
    case MSG_WIFI_CLEAR: return "wificlear";
    case MSG_UNPAIR: snprintf(b, sizeof b, "flags=%d", f.payload[0]); return b;
    case MSG_TIME: { TimeData t; if (!parseTime(f.payload, f.len, t)) return "REJECTED"; char tz[16]; tzString(t.tzMin, tz); snprintf(b, sizeof b, "epoch=%u tz=%d tzs=%s", t.epoch, t.tzMin, tz); return b; }
    case MSG_STEPS: {
      StepsData s; if (!parseSteps(f.payload, f.len, s)) return "REJECTED";
      int fl = (s.hasSteps ? 1 : 0) | (s.goalOn ? 2 : 0) | (s.heatOn ? 4 : 0) | (s.happy ? 8 : 0);
      std::string lv; for (int i = 0; i < STEP_CELLS; i++) lv += (char)('0' + s.levels[i]);
      snprintf(b, sizeof b, "steps=%u goal=%u f=%d label=%d hint=%d sub=%d lv=%s", s.steps, s.goal, fl, s.label, s.hint, s.subtitle, lv.c_str()); return b;
    }
    case MSG_MUSIC: { MusicData m; if (!parseMusic(f.payload, f.len, m)) return "REJECTED";
      snprintf(b, sizeof b, "sess=%d play=%d pos=%d dur=%d title=[%s] artist=[%s]", m.session, m.playing, m.posSec, m.durSec, m.title, m.artist); return b; }
    case MSG_SCREENTIME: { ScreenTimeData s; if (!parseScreenTime(f.payload, f.len, s)) return "REJECTED";
      std::string apps; for (int i = 0; i < s.n; i++) { if (i) apps += ","; apps += std::string(s.appName[i]) + ":" + std::to_string(s.appMin[i]); }
      snprintf(b, sizeof b, "has=%d perm=%d unavail=%d goalOn=%d total=%u goal=%u apps=%s", s.hasData, s.needsPermission, s.unavailable, s.goalOn, s.totalMin, s.goalMin, apps.c_str()); return b; }
    case MSG_NOTIFS: { NotifData d; if (!parseNotifs(f.payload, f.len, d)) return "REJECTED";
      std::string items; for (int i = 0; i < d.n; i++) { if (i) items += ";"; items += std::to_string(d.items[i].ageMin) + ":" + d.items[i].app + "|" + d.items[i].title + "|" + d.items[i].text; }
      snprintf(b, sizeof b, "on=%d access=%d n=%d %s", d.enabled, d.needsAccess, d.n, items.c_str()); return b; }
    case MSG_MUSIC2: { Music2Data m; if (!parseMusic2(f.payload, f.len, m)) return "REJECTED";
      snprintf(b, sizeof b, "art=%d sync=%d video=%d ly=%d idx=%d src=[%s] prev=[%s] cur=[%s] next=[%s]", m.hasArt, m.synced, m.video, m.lyricState, m.lineIdx, m.src, m.prev, m.cur, m.next); return b; }
    case MSG_LYRIC: { if (isLyricClear(f.payload, f.len)) return "clear";
      LyricChunk c; if (!parseLyricChunk(f.payload, f.len, c)) return "REJECTED";
      snprintf(b, sizeof b, "token=%d seq=%d line=%d w=%d h=%d n=%d", c.token, c.seq, c.line, c.w, c.h, c.len); return b; }
    case MSG_IMG_BEGIN: { ImgBegin x; if (!parseImgBegin(f.payload, f.len, x)) return "REJECTED";
      snprintf(b, sizeof b, "xfer=%d kind=%d total=%u crc=%08x chunk=%d w=%d h=%d", x.xfer, x.kind, x.total, x.crc, x.chunk, x.w, x.h); return b; }
    case MSG_IMG_DATA: { if (f.len < 3) return "REJECTED"; std::string hx; for (int i = 3; i < f.len; i++) { char c[4]; snprintf(c, sizeof c, "%02x", f.payload[i]); hx += c; } snprintf(b, sizeof b, "xfer=%d seq=%d n=%d data=%s", f.payload[0], rd16(f.payload + 1), f.len - 3, hx.c_str()); return b; }
    case MSG_IMG_END: snprintf(b, sizeof b, "xfer=%d", f.payload[0]); return b;
    case MSG_IMG_CTRL: snprintf(b, sizeof b, "op=%d", f.payload[0]); return b;
    case MSG_WIFI_SET: { WifiCreds w; if (!parseWifi(f.payload, f.len, w)) return "REJECTED"; snprintf(b, sizeof b, "ssid=[%s] pass=[%s]", w.ssid, w.pass); return b; }
  }
  return "UNKNOWN";
}

struct Vec { std::string name; std::vector<uint8_t> bytes; std::string expect; };

static std::vector<std::string> feedAll(const std::vector<uint8_t>& stream, size_t chunk) {
  FrameAssembler a; Frame f; std::vector<std::string> out; uint32_t t = 0;
  for (size_t i = 0; i < stream.size(); i += chunk)
    for (size_t j = i; j < stream.size() && j < i + chunk; j++) if (a.push(stream[j], t++, f)) out.push_back(summarize(f));
  return out;
}

int main(int argc, char** argv) {
  std::ifstream in(argc > 1 ? argv[1] : "../../tests/device_vectors.txt");
  if (!in) { printf("cannot open vectors file\n"); return 2; }
  std::vector<Vec> vecs; std::string line;
  while (std::getline(in, line)) { size_t a = line.find('|'), b = line.find('|', a + 1); if (a == std::string::npos || b == std::string::npos) continue; vecs.push_back({line.substr(0, a), unhex(line.substr(a + 1, b - a - 1)), line.substr(b + 1)}); }
  CHECK(vecs.size() >= 15, "vectors loaded");
  { int fresh = 0; for (auto& v : vecs) if (v.name.rfind("SCREENTIME", 0) == 0 || v.name.rfind("NOTIFS", 0) == 0 || v.name.rfind("MUSIC2", 0) == 0 || v.name.rfind("LYRIC", 0) == 0 || v.name.rfind("IMG_", 0) == 0 || v.name == "STATUS_EXT") fresh++; CHECK(fresh >= 16, "vectors for the new messages are present"); }

  // 1. every vector parses to exactly the values the phone encoded
  std::vector<uint8_t> stream; std::vector<std::string> expected;
  for (auto& v : vecs) {
    if (v.name == "STATUS") {
      Status s; s.screen = 2; s.wifiState = 2; s.wifiReason = 0; s.timeValid = true; s.hasWifi = true; s.musicFresh = true; s.bonds = 1; s.heapKb = 120; s.fwMajor = 1; s.fwMinor = 0;
      uint8_t o[STATUS_LEN]; encodeStatus(s, o);
      CHECK(memcmp(o, v.bytes.data(), STATUS_LEN) == 0 && v.bytes.size() == STATUS_LEN, "status bytes identical to the phone's expectation");
      continue;
    }
    if (v.name == "STATUS_EXT") {   // identical to what the phone parses: 10 status bytes + 13 transfer bytes
      Status s; s.screen = 5; s.wifiState = 2; s.timeValid = true; s.hasWifi = true; s.musicFresh = true; s.bonds = 1; s.heapKb = 120; s.fwMajor = 1; s.fwMinor = 0;
      XferStatus x; x.state = XFER_RECEIVING; x.id = 7; x.nextSeq = 300; x.reason = XR_NONE; x.customCrc = 0xCBF43926u; x.artCrc = 0x11223344u;
      uint8_t o[STATUS_FULL_LEN]; encodeStatus(s, o); encodeStatusExt(x, o + STATUS_LEN);
      CHECK(v.bytes.size() == STATUS_FULL_LEN && memcmp(o, v.bytes.data(), STATUS_FULL_LEN) == 0, "extended status bytes identical to the phone's expectation");
      continue;
    }
    auto got = feedAll(v.bytes, v.bytes.size());
    CHECK(got.size() == 1, (v.name + ": exactly one frame").c_str());
    if (got.size() == 1 && got[0] != v.expect) { failed++; printf("FAIL: %s\n  got      %s\n  expected %s\n", v.name.c_str(), got[0].c_str(), v.expect.c_str()); } else if (got.size() == 1) passed++;
    stream.insert(stream.end(), v.bytes.begin(), v.bytes.end()); expected.push_back(v.expect);
  }

  // 2. the same stream split into writes of any size (BLE MTU 23 gives 20-byte writes, 1-byte is the worst case)
  for (size_t chunk : {1, 2, 3, 7, 20, 100, 4096}) {
    auto got = feedAll(stream, chunk);
    CHECK(got == expected, ("stream split into " + std::to_string(chunk) + "-byte writes decodes identically").c_str());
  }

  // 3. noise before / between frames, truncated frames and impossible lengths never wedge the parser
  { FrameAssembler a; Frame f; std::vector<uint8_t> s = {0x00, 0x13, 0xFF, 0xA5, 0x02, 0xC9, 0x00, 0xA5, 0x02, 0x01, 0x01}; int frames = 0; uint32_t t = 0;
    for (auto b : s) if (a.push(b, t++, f)) frames++;
    CHECK(frames == 1 && f.type == MSG_SCREEN && f.payload[0] == 1, "noise skipped, impossible length (201) resynchronises, next frame decoded"); }
  { FrameAssembler a; Frame f; uint8_t part[] = {0xA5, 0x04, 0x20, 1, 2, 3}; for (auto b : part) a.push(b, 100, f);
    a.expire(100 + FRAME_TIMEOUT_MS + 1);
    uint8_t whole[] = {0xA5, 0x02, 0x01, 0x02}; bool got = false; for (auto b : whole) got = a.push(b, 5000, f) || got;
    CHECK(got && f.type == MSG_SCREEN, "a stalled half frame is dropped after the timeout and the next frame still works"); }
  { FrameAssembler a; Frame f; bool got = false; uint8_t z[] = {0xA5, 0x06, 0x00}; for (auto b : z) got = a.push(b, 1, f) || got; CHECK(got && f.type == MSG_WIFI_CLEAR && f.len == 0, "empty payload frame"); }

  // 4. malformed payloads are rejected, never partially applied
  { MusicData m; uint8_t bad1[] = {3, 1, 0, 2, 0, 5, 'a'}; CHECK(!parseMusic(bad1, sizeof bad1, m), "music: title longer than data");
    uint8_t bad2[] = {3, 1, 0, 2, 0, 1, 'a', 9, 'b'}; CHECK(!parseMusic(bad2, sizeof bad2, m), "music: artist longer than data");
    uint8_t bad3[] = {3, 1, 0, 2, 0, 1, 'a', 1, 'b', 'X'}; CHECK(!parseMusic(bad3, sizeof bad3, m), "music: trailing bytes");
    uint8_t ctl[] = {3, 1, 0, 2, 0, 4, 'a', 0x07, 0xC3, 'b', 0}; CHECK(parseMusic(ctl, sizeof ctl, m) && strcmp(m.title, "a b") == 0 && m.artist[0] == 0, "music: control and non-ASCII bytes become a single space"); }
  { WifiCreds w; uint8_t ok[] = {2, 'a', 'b', 8, '1', '2', '3', '4', '5', '6', '7', '8'}; CHECK(parseWifi(ok, sizeof ok, w) && strcmp(w.ssid, "ab") == 0 && strcmp(w.pass, "12345678") == 0, "wifi ok");
    uint8_t shortp[] = {2, 'a', 'b', 3, '1', '2', '3'}; CHECK(!parseWifi(shortp, sizeof shortp, w), "wifi: 3-char password rejected");
    uint8_t zero[] = {0, 0}; CHECK(!parseWifi(zero, sizeof zero, w), "wifi: empty ssid rejected");
    uint8_t trunc[] = {5, 'a', 'b'}; CHECK(!parseWifi(trunc, sizeof trunc, w), "wifi: truncated rejected");
    uint8_t nul[] = {2, 'a', 0, 0}; CHECK(!parseWifi(nul, sizeof nul, w), "wifi: NUL inside ssid rejected"); }
  { StepsData s; uint8_t p[STEPS_LEN] = {0}; CHECK(parseSteps(p, STEPS_LEN, s), "steps zero ok"); CHECK(!parseSteps(p, STEPS_LEN - 1, s), "steps wrong length");
    wr32(p + 4, 99999999UL); CHECK(!parseSteps(p, STEPS_LEN, s), "steps absurd goal rejected");
    wr32(p + 4, 8000); p[9] = 77; p[10] = 77; p[11] = 77; p[12] = 0xFF; CHECK(parseSteps(p, STEPS_LEN, s) && s.label == 0 && s.hint == 0 && s.subtitle == 0 && s.levels[0] == 0, "steps out-of-range codes and levels fall back to 0"); }
  { TimeData t; uint8_t p[6]; wr32(p, 12345); wr16(p + 4, 0); CHECK(!parseTime(p, 6, t), "time before 2023 rejected"); wr32(p, 1800000000UL); wr16(p + 4, (uint16_t)(int16_t)(15 * 60)); CHECK(!parseTime(p, 6, t), "offset beyond +14h rejected"); }
  { char tz[16]; tzString(-570, tz); CHECK(strcmp(tz, "<-0930>+9:30") == 0, "negative half-hour offset"); tzString(765, tz); CHECK(strcmp(tz, "<+1245>-12:45") == 0, "+12:45 (Chatham)"); tzString(60, tz); CHECK(strcmp(tz, "<+0100>-1") == 0, "whole hour"); }
  { LyricChunk c; uint8_t p[8 + LYR_CHUNK] = {0}; wr16(p + 4, LYR_W); wr16(p + 6, LYR_H);
    CHECK(parseLyricChunk(p, sizeof p, c) && c.len == LYR_CHUNK && c.seq == 0, "lyric chunk parses");
    uint8_t clear[] = {0, 0}; CHECK(isLyricClear(clear, 2), "lyric clear marker");
    uint8_t badw[8 + 4] = {0}; wr16(badw + 4, LYR_W - 1); wr16(badw + 6, LYR_H); CHECK(!parseLyricChunk(badw, sizeof badw, c), "wrong lyric mask width rejected");
    uint8_t badseq[8 + 4] = {0}; badseq[1] = 13; wr16(badseq + 4, LYR_W); wr16(badseq + 6, LYR_H); CHECK(!parseLyricChunk(badseq, sizeof badseq, c), "lyric chunk beyond the mask rejected");
    uint8_t shortc[8] = {0}; wr16(shortc + 4, LYR_W); wr16(shortc + 6, LYR_H); CHECK(!parseLyricChunk(shortc, sizeof shortc, c), "empty lyric chunk rejected"); }

  printf("firmware protocol tests: %d passed, %d failed\n", passed, failed);
  return failed ? 1 : 0;
}
