// Receiver side of the image transfer protocol (BEGIN / DATA / END / CTRL). Pure C++: all storage goes through RxSink, so the same state machine
// runs on the ESP32 (LittleFS file for the custom screen, RAM block for album art) and in the desktop tests (in-memory sink, lossy channel).
//
// Rules the phone can rely on
//   * chunks are accepted strictly in order (seq == nextSeq); duplicates, gaps and chunks of another transfer are dropped silently;
//     the phone learns the truth from the status extension (nextSeq) and resends from there
//   * nothing replaces the stored custom image until the whole file arrived, its CRC-32 matched and its structure validated; on any failure
//     the previous image stays untouched and visible
//   * a BEGIN whose CRC equals the stored custom image answers DONE immediately and writes nothing (no repeated flash writes)
//   * a transfer that goes quiet for RX_TIMEOUT_MS is abandoned
#pragma once
#include "protocol.h"
#include "mimg.h"

namespace meow {

constexpr uint32_t RX_TIMEOUT_MS = 6000;

struct RxSink {
  virtual ~RxSink() {}
  virtual uint32_t storedCustomCrc() = 0;                                          // CRC-32 of the stored custom file, 0 when there is none
  virtual uint32_t storedArtCrc() = 0;                                             // CRC-32 of the album art in RAM, 0 when there is none
  virtual bool open(uint8_t kind, uint32_t total, uint16_t w, uint16_t h, uint8_t& reason) = 0;   // prepare storage (temp file / RAM block)
  virtual bool write(uint8_t kind, const uint8_t* d, size_t n) = 0;
  virtual bool commit(uint8_t kind, uint32_t crc, uint8_t& reason) = 0;            // validate + publish; false keeps the old content
  virtual void abort(uint8_t kind) = 0;                                            // discard the partial data
  virtual void deleteCustom() = 0;
  virtual void clearArt() = 0;
};

class ImageReceiver {
 public:
  ImageReceiver(RxSink* sink, uint16_t customW, uint16_t customH) : sink_(sink), cw_(customW), ch_(customH) {}

  void onBegin(const ImgBegin& b, uint32_t now) {
    if (state_ == XFER_RECEIVING && b.xfer == id_ && kind_ == b.kind && total_ == b.total && crcWant_ == b.crc) { last_ = now; return; }   // a repeated BEGIN keeps the progress
    if (state_ == XFER_RECEIVING) { sink_->abort(kind_); state_ = XFER_IDLE; }
    id_ = b.xfer; kind_ = b.kind; total_ = b.total; crcWant_ = b.crc; got_ = 0; next_ = 0; crc_ = crc32Begin(); reason_ = XR_NONE; last_ = now;
    if (b.kind == KIND_CUSTOM) {
      if (b.total < MIMG_HDR + 3 || b.total > MIMG_MAX_FILE || b.w != cw_ || b.h != ch_) return fail(b.total > MIMG_MAX_FILE ? XR_TOO_LARGE : XR_BAD_HEADER);
      if (sink_->storedCustomCrc() != 0 && sink_->storedCustomCrc() == b.crc) { state_ = XFER_DONE; return; }   // already on the device: no flash write
    } else {
      if (b.w == 0 || b.h == 0 || b.w > ART_MAX_SIDE || b.h > ART_MAX_SIDE || b.total != (uint32_t)b.w * b.h * 2) return fail(XR_BAD_HEADER);
    }
    uint8_t why = XR_FLASH;
    if (!sink_->open(b.kind, b.total, b.w, b.h, why)) return fail(why);
    state_ = XFER_RECEIVING;
  }

  void onData(const uint8_t* p, uint8_t len, uint32_t now) {
    if (state_ != XFER_RECEIVING || len < 3 || p[0] != id_) return;
    uint16_t seq = rd16(p + 1);
    if (seq != next_) return;                                                       // duplicate or gap: ignore, the status tells the phone where we are
    size_t n = len - 3;
    if (n == 0 || got_ + n > total_) return fail(XR_SEQUENCE);
    if (!sink_->write(kind_, p + 3, n)) return fail(XR_FLASH);
    crc_ = crc32Update(crc_, p + 3, n); got_ += n; next_++; last_ = now;
  }

  void onEnd(const uint8_t* p, uint8_t len) {
    if (state_ != XFER_RECEIVING || len != 1 || p[0] != id_) return;
    if (got_ != total_) return fail(XR_SEQUENCE);
    if (crc32End(crc_) != crcWant_) return fail(XR_CRC);
    uint8_t why = XR_BAD_IMAGE;
    if (!sink_->commit(kind_, crcWant_, why)) return fail(why);
    state_ = XFER_DONE;
  }

  void onCtrl(uint8_t op) {
    if (op == CTRL_ABORT) { if (state_ == XFER_RECEIVING) sink_->abort(kind_); state_ = XFER_IDLE; reason_ = XR_NONE; }
    else if (op == CTRL_DELETE_CUSTOM) { if (state_ == XFER_RECEIVING && kind_ == KIND_CUSTOM) { sink_->abort(kind_); state_ = XFER_IDLE; } sink_->deleteCustom(); }
    else if (op == CTRL_CLEAR_ART) sink_->clearArt();
  }

  void service(uint32_t now) { if (state_ == XFER_RECEIVING && (uint32_t)(now - last_) > RX_TIMEOUT_MS) fail(XR_SEQUENCE); }

  XferStatus status() const { XferStatus s; s.state = state_; s.id = id_; s.reason = reason_; s.nextSeq = next_; s.customCrc = sink_->storedCustomCrc(); s.artCrc = sink_->storedArtCrc(); return s; }
  uint8_t state() const { return state_; }
  uint8_t kind() const { return kind_; }

 private:
  void fail(uint8_t why) { if (state_ == XFER_RECEIVING) sink_->abort(kind_); state_ = XFER_FAILED; reason_ = why; }
  RxSink* sink_; uint16_t cw_, ch_;
  uint8_t state_ = XFER_IDLE, id_ = 0, kind_ = 0, reason_ = XR_NONE; uint32_t total_ = 0, got_ = 0, crcWant_ = 0, crc_ = 0, last_ = 0; uint16_t next_ = 0;
};

}  // namespace meow
