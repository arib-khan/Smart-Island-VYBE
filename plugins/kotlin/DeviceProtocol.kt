package com.smartisland.app

import java.text.Normalizer

/**
 * Phone side of the Meow Island link protocol. Pure Kotlin (no android.* imports) so it is unit-tested on a desktop
 * (tests/kotlin/run_device.sh) and cross-checked against the firmware's protocol.h with tests/device_vectors.txt.
 * Frame = 0xA5 | type | len | payload. All integers little-endian. Keep this file and firmware/MeowIsland/protocol.h in sync.
 */
object DeviceProtocol {
    const val PROTO = 1
    const val MAGIC = 0xA5
    const val SERVICE_UUID = "4d656f77-1000-4a3b-9c2d-0e7f6a5b4c3d"
    const val RX_UUID = "4d656f77-1001-4a3b-9c2d-0e7f6a5b4c3d"       // phone -> device, write (encrypted + authenticated link required)
    const val STATUS_UUID = "4d656f77-1002-4a3b-9c2d-0e7f6a5b4c3d"   // device -> phone, read + notify (same protection)
    const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

    const val MSG_HELLO = 0x01; const val MSG_SCREEN = 0x02; const val MSG_STEPS = 0x03; const val MSG_MUSIC = 0x04
    const val MSG_WIFI_SET = 0x05; const val MSG_WIFI_CLEAR = 0x06; const val MSG_UNPAIR = 0x07; const val MSG_TIME = 0x08

    // protocol additions (firmware/MeowIsland/protocol.h documents every byte)
    const val MSG_SCREENTIME = 0x09; const val MSG_NOTIFS = 0x0A; const val MSG_MUSIC2 = 0x0B
    const val MSG_IMG_BEGIN = 0x0C; const val MSG_IMG_DATA = 0x0D; const val MSG_IMG_END = 0x0E; const val MSG_IMG_CTRL = 0x0F
    const val MSG_LYRIC = 0x10

    const val SCREEN_CLOCK = 0; const val SCREEN_STEPS = 1; const val SCREEN_MUSIC = 2
    const val SCREEN_SCREENTIME = 3; const val SCREEN_NOTIFS = 4; const val SCREEN_CUSTOM = 5; const val SCREEN_LAST = 5
    const val STEP_CELLS = 54
    const val MAX_TITLE = 48; const val MAX_ARTIST = 40
    const val STATUS_LEN = 10; const val STATUS_EXT_LEN = 13; const val STATUS_FULL_LEN = 23
    const val STATUS_EXT_LEGACY_LEN = 9                 // firmware before the art CRC: state/id/next/reason/customCrc
    const val ST_MAX_APPS = 4; const val ST_NAME = 14
    const val NT_MAX = 8; const val NT_APP = 12; const val NT_TITLE = 24; const val NT_TEXT = 48
    const val M2_SRC = 14; const val M2_PREV = 40; const val M2_CUR = 56; const val M2_NEXT = 40
    const val KIND_CUSTOM = 0; const val KIND_ART = 1
    const val CTRL_ABORT = 1; const val CTRL_DELETE_CUSTOM = 2; const val CTRL_CLEAR_ART = 3
    const val IMG_CHUNK = 190
    const val XFER_IDLE = 0; const val XFER_RECEIVING = 1; const val XFER_DONE = 2; const val XFER_FAILED = 3
    // lyric states carried by MUSIC2
    const val LY_NA = 0; const val LY_LOADING = 1; const val LY_AVAILABLE = 2; const val LY_UNAVAILABLE = 3; const val LY_PROVIDER_OFF = 4; const val LY_OFFLINE = 5; const val LY_UNSYNCED = 6
    // lyric band (rendered on the phone so every script, RTL and CJK render correctly on the ASCII-only TFT)
    const val LYR_W = 224; const val LYR_H = 80; const val LYR_ROW = LYR_W / 8; const val LYR_BYTES = LYR_ROW * LYR_H
    const val LYR_CHUNK = 190

    fun frame(type: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        require(payload.size <= 200) { "payload too large" }
        return byteArrayOf(MAGIC.toByte(), type.toByte(), payload.size.toByte()) + payload
    }

    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    private fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    fun hello() = frame(MSG_HELLO, byteArrayOf(PROTO.toByte()))
    fun screen(id: Int) = frame(MSG_SCREEN, byteArrayOf(id.coerceIn(0, SCREEN_LAST).toByte()))
    fun time(epochSec: Long, tzMinutes: Int) = frame(MSG_TIME, le32(epochSec) + le16(tzMinutes.coerceIn(-840, 840)))
    fun wifiClear() = frame(MSG_WIFI_CLEAR)
    fun unpair(clearWifi: Boolean) = frame(MSG_UNPAIR, byteArrayOf(if (clearWifi) 1 else 0))

    /** SSID 1..32 bytes, password empty (open network) or 8..63 bytes, exactly what the firmware accepts. Returns null when invalid. */
    fun wifiSet(ssid: String, pass: String): ByteArray? {
        val s = ssid.toByteArray(Charsets.UTF_8); val p = pass.toByteArray(Charsets.UTF_8)
        if (s.isEmpty() || s.size > 32) return null
        if (p.isNotEmpty() && p.size < 8 || p.size > 63) return null
        return frame(MSG_WIFI_SET, byteArrayOf(s.size.toByte()) + s + byteArrayOf(p.size.toByte()) + p)
    }

    /** levels: STEP_CELLS values 0..4 (oldest first, last = today), packed two per byte, low nibble first. */
    fun packLevels(levels: IntArray): ByteArray {
        val out = ByteArray(27)
        for (i in 0 until STEP_CELLS) {
            val v = (if (i < levels.size) levels[i] else 0).coerceIn(0, 4)
            out[i / 2] = (out[i / 2].toInt() or (if (i % 2 == 0) v else v shl 4)).toByte()
        }
        return out
    }

    class Steps(val steps: Long, val goal: Long, val hasSteps: Boolean, val goalOn: Boolean, val heatOn: Boolean, val happy: Boolean,
                val label: Int, val hint: Int, val subtitle: Int, val levels: IntArray)

    fun steps(s: Steps): ByteArray {
        val flags = (if (s.hasSteps) 1 else 0) or (if (s.goalOn) 2 else 0) or (if (s.heatOn) 4 else 0) or (if (s.happy) 8 else 0)
        return frame(MSG_STEPS, le32(s.steps.coerceIn(0, 4_000_000_000L)) + le32(s.goal.coerceIn(0, 10_000_000L)) +
            byteArrayOf(flags.toByte(), s.label.toByte(), s.hint.toByte(), s.subtitle.toByte()) + packLevels(s.levels))
    }

    /**
     * The TFT fonts only have printable ASCII, so text is reduced to that: accents are stripped (é -> e), typographic quotes/dashes
     * become plain ones, everything else (Devanagari, CJK, emoji...) is dropped. Returns "" when nothing readable is left.
     */
    fun ascii(text: String?, maxBytes: Int): String {
        if (text.isNullOrBlank()) return ""
        var t = text.replace('\u2018', '\'').replace('\u2019', '\'').replace('\u201C', '"').replace('\u201D', '"')
            .replace('\u2013', '-').replace('\u2014', '-').replace("\u2026", "...").replace('\u00A0', ' ')
        t = Normalizer.normalize(t, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val sb = StringBuilder(); var lastSpace = true
        for (ch in t) {
            val c = if (ch.code in 0x20..0x7E) ch else ' '
            if (c == ' ') { if (lastSpace) continue; lastSpace = true } else lastSpace = false
            if (sb.length >= maxBytes) break
            sb.append(c)
        }
        return sb.toString().trimEnd()
    }

    fun music(session: Boolean, playing: Boolean, posSec: Int, durSec: Int, title: String?, artist: String?): ByteArray {
        val t = ascii(title, MAX_TITLE).toByteArray(Charsets.US_ASCII); val a = ascii(artist, MAX_ARTIST).toByteArray(Charsets.US_ASCII)
        val flags = (if (session) 1 else 0) or (if (playing) 2 else 0)
        return frame(MSG_MUSIC, byteArrayOf(flags.toByte()) + le16(posSec.coerceIn(0, 65535)) + le16(durSec.coerceIn(0, 65535)) +
            byteArrayOf(t.size.toByte()) + t + byteArrayOf(a.size.toByte()) + a)
    }

    // ------------------------------------------------------------------ screen time (PHONE usage today, not the display-on time of the device)
    class AppUse(val name: String, val min: Int)

    /** flags: bit0 has data, bit1 needs Usage Access, bit2 unavailable, bit3 goal on. At most 4 apps, names ASCII-folded to 14 characters. */
    fun screenTime(hasData: Boolean, needsPermission: Boolean, unavailable: Boolean, totalMin: Int, goalMin: Int, apps: List<AppUse>): ByteArray {
        val shown = apps.take(ST_MAX_APPS)
        val goal = goalMin.coerceIn(0, 1440)
        val flags = (if (hasData) 1 else 0) or (if (needsPermission) 2 else 0) or (if (unavailable) 4 else 0) or (if (goal > 0) 8 else 0)
        var out = byteArrayOf(flags.toByte()) + le16(totalMin.coerceIn(0, 1440)) + le16(goal) + byteArrayOf(shown.size.toByte())
        for (a in shown) {
            val n = ascii(a.name, ST_NAME).ifEmpty { "App" }.toByteArray(Charsets.US_ASCII)
            out += le16(a.min.coerceIn(0, 1440)) + byteArrayOf(n.size.toByte()) + n
        }
        return frame(MSG_SCREENTIME, out)
    }

    // ------------------------------------------------------------------ notifications (newest first, as many as fit one frame)
    class Notif(val ageMin: Long, val app: String, val title: String, val text: String)

    fun notifs(enabled: Boolean, needsAccess: Boolean, items: List<Notif>): ByteArray {
        val flags = (if (enabled) 1 else 0) or (if (needsAccess) 2 else 0)
        val body = ArrayList<ByteArray>()
        var size = 2
        for (n in items) {
            if (body.size >= NT_MAX) break
            val a = ascii(n.app, NT_APP).toByteArray(Charsets.US_ASCII); val t = ascii(n.title, NT_TITLE).toByteArray(Charsets.US_ASCII); val x = ascii(n.text, NT_TEXT).toByteArray(Charsets.US_ASCII)
            val e = le16(n.ageMin.coerceIn(0L, 65535L).toInt()) + byteArrayOf(a.size.toByte()) + a + byteArrayOf(t.size.toByte()) + t + byteArrayOf(x.size.toByte()) + x
            if (size + e.size > 200) break
            body.add(e); size += e.size
        }
        var out = byteArrayOf(flags.toByte(), body.size.toByte())
        for (e in body) out += e
        return frame(MSG_NOTIFS, out)
    }

    // ------------------------------------------------------------------ music extras: source name + lyric lines
    fun music2(hasArt: Boolean, synced: Boolean, lyricState: Int, lineIdx: Int, src: String?, prev: String?, cur: String?, next: String?, video: Boolean = false): ByteArray {
        fun s(t: String?, max: Int) = ascii(t, max).toByteArray(Charsets.US_ASCII)
        val a = s(src, M2_SRC); val p = s(prev, M2_PREV); val c = s(cur, M2_CUR); val n = s(next, M2_NEXT)
        val flags = (if (hasArt) 1 else 0) or (if (synced) 2 else 0) or (if (video) 4 else 0)
        return frame(MSG_MUSIC2, byteArrayOf(flags.toByte(), lyricState.coerceIn(0, 6).toByte()) + le16(lineIdx.coerceIn(0, 65535)) +
            byteArrayOf(a.size.toByte()) + a + byteArrayOf(p.size.toByte()) + p + byteArrayOf(c.size.toByte()) + c + byteArrayOf(n.size.toByte()) + n)
    }

    /**
     * One chunk of the phone-rendered lyric mask (1 bit per pixel, MSB first, 224x80). [line] ties the mask to the MUSIC2 line
     * index so a late chunk from the previous line can never be shown as the current one. Every chunk is sent in order; the frame
     * fits one BLE write at the negotiated MTU.
     */
    fun lyricBand(token: Int, seq: Int, line: Int, data: ByteArray, off: Int, len: Int): ByteArray {
        require(len in 1..LYR_CHUNK) { "bad lyric chunk" }
        require(off >= 0 && off + len <= data.size) { "bad lyric range" }
        val head = byteArrayOf((token and 0xFF).toByte(), (seq and 0xFF).toByte()) + le16(line) + le16(LYR_W) + le16(LYR_H)
        return frame(MSG_LYRIC, head + data.copyOfRange(off, off + len))
    }

    /** Tells the display to drop the rendered lyric band (track ended, lyrics unavailable, video started). */
    fun lyricClear(): ByteArray = frame(MSG_LYRIC, byteArrayOf(0, 0))

    // ------------------------------------------------------------------ image transfer (custom screen file, album art)
    fun crc32(data: ByteArray): Long { val c = java.util.zip.CRC32(); c.update(data, 0, data.size); return c.value }
    fun imgBegin(xfer: Int, kind: Int, total: Int, crc: Long, chunk: Int, w: Int, h: Int) =
        frame(MSG_IMG_BEGIN, byteArrayOf(xfer.toByte(), kind.toByte()) + le32(total.toLong()) + le32(crc) + le16(chunk) + le16(w) + le16(h))
    fun imgData(xfer: Int, seq: Int, data: ByteArray, off: Int, len: Int) = frame(MSG_IMG_DATA, byteArrayOf(xfer.toByte()) + le16(seq) + data.copyOfRange(off, off + len))
    fun imgEnd(xfer: Int) = frame(MSG_IMG_END, byteArrayOf(xfer.toByte()))
    fun imgCtrl(op: Int) = frame(MSG_IMG_CTRL, byteArrayOf(op.toByte()))

    /** Splits a byte stream into BLE writes of at most [size] bytes (the device reassembles frames from the stream). */
    fun chunks(data: ByteArray, size: Int): List<ByteArray> {
        val n = size.coerceAtLeast(20); val out = ArrayList<ByteArray>()
        var i = 0
        while (i < data.size) { out.add(data.copyOfRange(i, minOf(data.size, i + n))); i += n }
        return out
    }

    class Status(val proto: Int, val fwMajor: Int, val fwMinor: Int, val screen: Int, val wifiState: Int, val wifiReason: Int,
                 val timeValid: Boolean, val pairingOpen: Boolean, val hasWifi: Boolean, val stepsFresh: Boolean, val musicFresh: Boolean,
                 val bonds: Int, val heapKb: Int,
                 // transfer feedback (bytes 10..18); hasExt = false on firmware that predates the image transfer
                 val hasExt: Boolean = false, val xferState: Int = 0, val xferId: Int = 0, val xferNext: Int = 0, val xferReason: Int = 0, val customCrc: Long = 0L,
                 // bytes 19..22 on firmware that knows the art CRC: CRC-32 of the album art currently in the device's RAM (0 = none)
                 val artCrc: Long = 0L)

    fun parseStatus(b: ByteArray?): Status? {
        if (b == null || b.size < STATUS_LEN) return null
        fun u(i: Int) = b[i].toInt() and 0xFF
        if (u(0) != PROTO) return null
        val f = u(6)
        val ext = b.size >= STATUS_LEN + STATUS_EXT_LEGACY_LEN
        val withArt = b.size >= STATUS_FULL_LEN
        fun u32(i: Int) = u(i).toLong() or (u(i + 1).toLong() shl 8) or (u(i + 2).toLong() shl 16) or (u(i + 3).toLong() shl 24)
        return Status(u(0), u(1), u(2), u(3).coerceIn(0, SCREEN_LAST), u(4).coerceIn(0, 3), u(5), f and 1 != 0, f and 2 != 0, f and 4 != 0, f and 8 != 0, f and 16 != 0, u(7), u(8),
            ext, if (ext) u(10) else 0, if (ext) u(11) else 0, if (ext) u(12) or (u(13) shl 8) else 0, if (ext) u(14) else 0,
            if (ext) u32(15) else 0L, if (withArt) u32(19) else 0L)
    }

    /** Messages for the reason codes the device reports (protocol.h XferReason). */
    fun xferReasonText(code: Int) = when (code) {
        1 -> "The device did not accept the picture header. Update the device firmware."
        2 -> "The design is too large for the device."
        3 -> "The device is out of storage space. Flash it with the Minimal SPIFFS or Default partition scheme."
        4 -> "The device could not write to its flash memory."
        5 -> "The data arrived damaged (checksum mismatch). Try again closer to the device."
        6 -> "The device stopped responding during the transfer."
        7 -> "The device rejected the picture as invalid."
        8 -> "The device is low on memory."
        else -> "The transfer failed."
    }

    fun wifiReasonText(code: Int) = when (code) { 1 -> "That Wi-Fi network was not found"; 2 -> "The Wi-Fi password was not accepted"; 3 -> "The Wi-Fi network did not answer in time"; 4 -> "The Wi-Fi connection failed"; else -> "" }
}
