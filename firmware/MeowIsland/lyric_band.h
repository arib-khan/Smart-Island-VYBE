// Phone-rendered lyric mask. Android does the text shaping, bidi ordering and font fallback, so Devanagari, Arabic/Urdu RTL, Korean,
// Japanese and every other script render correctly on the ASCII-only TFT. The mask is 1 bit per pixel, 224x80, MSB first, 28 bytes
// per row: rows 0..17 = previous line, 20..59 = current line (up to two wrapped lines), 64..79 = next line.
//
// Chunks are numbered from 0 and accepted strictly in order. A chunk burst whose token changed resets the buffer; an out-of-order
// chunk drops the half-received band (the phone sends the next line shortly). `line` must match MUSIC2's lineIdx before the mask is
// shown, so a late burst can never display the previous lyric line.
#pragma once
#include <string.h>
#include "gfx_common.h"
#include "protocol.h"

constexpr int LYR_X = 8, LYR_TOP = 238;
constexpr int LYR_PREV_H = 18, LYR_CUR_Y = 20, LYR_CUR_H = 40, LYR_NEXT_Y = 64, LYR_NEXT_H = 16;

static uint8_t lyrBuf[meow::LYR_BYTES];
static bool lyrReady = false;
static uint32_t lyrRev = 0;
static uint8_t lyrToken = 0;
static uint16_t lyrLine = 0, lyrGot = 0;
static uint32_t lyrAt = 0;

static void lyrClear() {
  lyrReady = false; lyrGot = 0; lyrToken = 0; lyrLine = 0; lyrRev++;
}

static bool lyrOnChunk(const meow::LyricChunk& c, uint32_t now) {
  if (c.w != meow::LYR_W || c.h != meow::LYR_H || c.len == 0) return false;
  const uint16_t off = (uint16_t)((uint32_t)c.seq * meow::LYR_CHUNK);
  if (c.seq == 0) { lyrGot = 0; lyrToken = c.token; lyrLine = c.line; lyrReady = false; lyrAt = now; }
  if (c.token != lyrToken || off != lyrGot || (uint32_t)off + c.len > meow::LYR_BYTES) { lyrClear(); return false; }
  memcpy(lyrBuf + off, c.data, c.len);
  lyrGot += c.len; lyrAt = now;
  if (lyrGot >= meow::LYR_BYTES) { lyrReady = true; lyrRev++; }
  return true;
}

/** A burst that stopped halfway (link hiccup) is dropped instead of lingering. */
static void lyrService(uint32_t now) { if (!lyrReady && lyrGot > 0 && (uint32_t)(now - lyrAt) > 3000) lyrClear(); }

static bool lyrValid(uint16_t line) { return lyrReady && lyrLine == line; }

/** Draws the three strips in the existing lyric-zone colours. `G`/`Y0` may be the TFT or the band canvas. */
static void lyrDraw() {
  if (!lyrReady) return;
  G->drawBitmap(LYR_X, LYR_TOP - Y0, lyrBuf, meow::LYR_W, LYR_PREV_H, C_MUTE);
  G->drawBitmap(LYR_X, LYR_TOP + LYR_CUR_Y - Y0, lyrBuf + (size_t)LYR_CUR_Y * meow::LYR_ROW, meow::LYR_W, LYR_CUR_H, C_TEXT);
  G->drawBitmap(LYR_X, LYR_TOP + LYR_NEXT_Y - Y0, lyrBuf + (size_t)LYR_NEXT_Y * meow::LYR_ROW, meow::LYR_W, LYR_NEXT_H, C_MUTE);
}
