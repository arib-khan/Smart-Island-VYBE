import com.smartisland.app.DeviceProtocol as P
import java.io.File

var passed = 0
fun check(c: Boolean, m: String) { if (!c) throw AssertionError("FAIL: $m"); passed++ }
fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

fun main(args: Array<String>) {
    val out = StringBuilder()
    fun vec(name: String, frame: ByteArray, summary: String) { out.append("$name|${hex(frame)}|$summary\n") }

    // ---- framing ----
    check(hex(P.hello()) == "a5010101", "hello frame")
    check(hex(P.screen(2)) == "a5020102" && hex(P.screen(9)) == "a5020105" && hex(P.screen(-3)) == "a5020100", "screen is clamped")
    vec("HELLO", P.hello(), "proto=1")
    for (s in 0..5) vec("SCREEN", P.screen(s), "screen=$s")
    vec("TIME_IST", P.time(1_800_000_000L, 330), "epoch=1800000000 tz=330 tzs=<+0530>-5:30")
    vec("TIME_EST", P.time(1_800_000_000L, -300), "epoch=1800000000 tz=-300 tzs=<-0500>+5")
    vec("TIME_UTC", P.time(1_800_000_000L, 0), "epoch=1800000000 tz=0 tzs=<+0000>-0")
    vec("WIFI_CLEAR", P.wifiClear(), "wificlear")
    vec("UNPAIR", P.unpair(false), "flags=0"); vec("UNPAIR_ALL", P.unpair(true), "flags=1")

    // ---- steps ----
    val levels = IntArray(54) { (it * 7 + 1) % 5 }
    val st = P.Steps(12_345, 10_000, true, true, true, true, 0, 0, 2, levels)
    val f = P.steps(st)
    check(f.size == 3 + 39, "steps frame size")
    vec("STEPS_FULL", f, "steps=12345 goal=10000 f=15 label=0 hint=0 sub=2 lv=" + levels.joinToString(""))
    vec("STEPS_OFF", P.steps(P.Steps(0, 8000, false, false, false, false, 1, 1, 0, IntArray(54))), "steps=0 goal=8000 f=0 label=1 hint=1 sub=0 lv=" + "0".repeat(54))
    check(P.packLevels(IntArray(54) { 4 }).all { it.toInt() == 0x44 }, "levels pack two per byte")
    check(P.packLevels(intArrayOf(1, 2, 3, 9, -4)).take(3) == listOf<Byte>(0x21, 0x43, 0x00), "levels clamp and low nibble first")

    // ---- text ----
    check(P.ascii("Kabhi Kabhi Aditi", 48) == "Kabhi Kabhi Aditi", "plain text unchanged")
    check(P.ascii("Beyonc\u00E9 \u2013 It\u2019s \u201Cok\u201D\u2026", 48) == "Beyonce - It's \"ok\"...", "accents stripped, typographic punctuation made plain")
    check(P.ascii("\u0915\u092D\u0940 \u0915\u092D\u0940", 48) == "", "scripts the TFT cannot draw leave nothing")
    check(P.ascii("Song \u0915\u092D\u0940 Name \uD83D\uDE00", 48) == "Song Name", "unsupported characters dropped, spaces collapsed")
    check(P.ascii("  a   b  ", 48) == "a b" && P.ascii(null, 5) == "" && P.ascii("abcdefghij", 5) == "abcde", "trim, null, length limit")
    vec("MUSIC_PLAY", P.music(true, true, 102, 318, "Kabhi Kabhi Aditi", "Rashid Ali, Warbartan - Topic"), "sess=1 play=1 pos=102 dur=318 title=[Kabhi Kabhi Aditi] artist=[Rashid Ali, Warbartan - Topic]")
    vec("MUSIC_PAUSED", P.music(true, false, 5, 0, "Caf\u00E9 del Mar", "\u0915\u092D\u0940"), "sess=1 play=0 pos=5 dur=0 title=[Cafe del Mar] artist=[]")
    vec("MUSIC_NONE", P.music(false, false, 0, 0, null, null), "sess=0 play=0 pos=0 dur=0 title=[] artist=[]")
    vec("MUSIC_LONG", P.music(true, true, 65535, 65535, "T".repeat(80), "A".repeat(80)), "sess=1 play=1 pos=65535 dur=65535 title=[" + "T".repeat(48) + "] artist=[" + "A".repeat(40) + "]")

    // ---- wifi ----
    vec("WIFI_OK", P.wifiSet("HomeNet", "correct horse")!!, "ssid=[HomeNet] pass=[correct horse]")
    vec("WIFI_OPEN", P.wifiSet("Cafe", "")!!, "ssid=[Cafe] pass=[]")
    check(P.wifiSet("", "password1") == null && P.wifiSet("x".repeat(33), "password1") == null, "ssid length validated")
    check(P.wifiSet("ok", "short") == null && P.wifiSet("ok", "p".repeat(64)) == null, "password length validated")
    check(P.wifiSet("ok", "p".repeat(63)) != null && P.wifiSet("x".repeat(32), "12345678") != null, "boundary lengths accepted")

    // ---- chunking ----
    val big = P.music(true, true, 1, 2, "x".repeat(40), "y".repeat(30))
    val ch = P.chunks(big, 20)
    check(ch.all { it.size <= 20 } && ch.fold(ByteArray(0)) { a, b -> a + b }.contentEquals(big), "chunks cover the frame exactly")
    check(P.chunks(big, 5).all { it.size <= 20 }, "chunk size never below the minimum MTU payload")

    // ---- status ----
    val sb = byteArrayOf(1, 1, 0, 2, 2, 0, 0b10101, 1, 120, 0)
    val ps = P.parseStatus(sb)!!
    check(ps.screen == 2 && ps.wifiState == 2 && ps.timeValid && !ps.pairingOpen && ps.hasWifi && !ps.stepsFresh && ps.musicFresh && ps.bonds == 1 && ps.heapKb == 120, "status decoded")
    check(P.parseStatus(byteArrayOf(2, 1, 0, 0, 0, 0, 0, 0, 0, 0)) == null && P.parseStatus(ByteArray(3)) == null && P.parseStatus(null) == null, "bad status rejected")
    out.append("STATUS|${hex(sb)}|screen=2 wifi=2 reason=0 flags=21 bonds=1 heap=120\n")


    // ---- protocol additions: screen time, notifications, music extras, image transfer (the firmware test reads these vectors) ----
    val apps3 = listOf(P.AppUse("Instagram", 95), P.AppUse("YouTube", 70), P.AppUse("Chrome", 30))
    vec("SCREENTIME_FULL", P.screenTime(true, false, false, 222, 240, apps3), "has=1 perm=0 unavail=0 goalOn=1 total=222 goal=240 apps=Instagram:95,YouTube:70,Chrome:30")
    vec("SCREENTIME_PERM", P.screenTime(false, true, false, 0, 240, emptyList()), "has=0 perm=1 unavail=0 goalOn=1 total=0 goal=240 apps=")
    vec("SCREENTIME_NOGOAL", P.screenTime(true, false, false, 60, 0, listOf(P.AppUse("Maps", 60))), "has=1 perm=0 unavail=0 goalOn=0 total=60 goal=0 apps=Maps:60")
    vec("SCREENTIME_FOLD", P.screenTime(true, false, false, 8, 240, listOf(P.AppUse("Caf\u00E9 B\u00E4r \u00DCnicode Name", 5), P.AppUse("\u65E5\u672C\u8A9E", 3))), "has=1 perm=0 unavail=0 goalOn=1 total=8 goal=240 apps=Cafe Bar Unico:5,App:3")
    check(P.screenTime(true, false, false, 99999, 99999, List(9) { P.AppUse("A$it", 99999) }).size == 3 + 6 + 4 * (3 + 2), "screen time: numbers clamped to 24 h, at most 4 apps")
    vec("NOTIFS_LIST", P.notifs(true, false, listOf(P.Notif(0, "WhatsApp", "Aditi", "Are we still meeting at 6?"), P.Notif(14, "Gmail", "", "Your receipt from Cafe"))), "on=1 access=0 n=2 0:WhatsApp|Aditi|Are we still meeting at 6?;14:Gmail||Your receipt from Cafe")
    vec("NOTIFS_OFF", P.notifs(false, false, emptyList()), "on=0 access=0 n=0 ")
    vec("NOTIFS_NEEDS_ACCESS", P.notifs(true, true, emptyList()), "on=1 access=1 n=0 ")
    vec("NOTIFS_TRUNC", P.notifs(true, false, listOf(P.Notif(999_999, "Instagram", "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmn", "x".repeat(80)))), "on=1 access=0 n=1 65535:Instagram|ABCDEFGHIJKLMNOPQRSTUVWX|" + "x".repeat(48))
    val many = P.notifs(true, false, List(8) { P.Notif(1, "a".repeat(12), "t".repeat(24), "x".repeat(48)) })
    check(many.size <= 203 && many[4].toInt() == 2, "notifications: only as many entries as fit one frame, newest first")
    vec("MUSIC2_LYRICS", P.music2(true, true, 2, 3, "SPOTIFY", "previous line", "the current line", "next line"), "art=1 sync=1 video=0 ly=2 idx=3 src=[SPOTIFY] prev=[previous line] cur=[the current line] next=[next line]")
    vec("MUSIC2_PLAIN", P.music2(false, false, 6, 2, "AMAZON MUSIC", "plain line before", "plain line now", "plain line after"), "art=0 sync=0 video=0 ly=6 idx=2 src=[AMAZON MUSIC] prev=[plain line before] cur=[plain line now] next=[plain line after]")
    vec("MUSIC2_VIDEO", P.music2(true, false, 0, 0, "YOUTUBE", "", "", "", video = true), "art=1 sync=0 video=1 ly=0 idx=0 src=[YOUTUBE] prev=[] cur=[] next=[]")
    vec("MUSIC2_OFF", P.music2(false, false, 4, 0, "AMAZON MUSIC", "", "", ""), "art=0 sync=0 video=0 ly=4 idx=0 src=[AMAZON MUSIC] prev=[] cur=[] next=[]")
    check(P.music2(false, false, 99, 0, null, null, "c".repeat(200), null).size <= 203, "music extras never exceed one frame")
    val band = ByteArray(P.LYR_BYTES) { (it * 13 + 5).toByte() }
    vec("LYRIC_BAND", P.lyricBand(9, 0, 3, band, 0, P.LYR_CHUNK), "token=9 seq=0 line=3 w=224 h=80 n=190")
    vec("LYRIC_CLEAR", P.lyricClear(), "clear")
    check(P.lyricBand(9, 0, 3, band, 0, P.LYR_CHUNK).size == 3 + 8 + P.LYR_CHUNK, "a lyric chunk fits one frame")
    vec("IMG_BEGIN", P.imgBegin(7, 0, 5000, 0xDEADBEEFL, P.IMG_CHUNK, 240, 320), "xfer=7 kind=0 total=5000 crc=deadbeef chunk=190 w=240 h=320")
    vec("IMG_BEGIN_ART", P.imgBegin(8, 1, 12800, 0x12345678L, P.IMG_CHUNK, 80, 80), "xfer=8 kind=1 total=12800 crc=12345678 chunk=190 w=80 h=80")
    vec("IMG_DATA", P.imgData(7, 300, byteArrayOf(0, 1, 2, 3, 4, 5), 1, 4), "xfer=7 seq=300 n=4 data=01020304")
    vec("IMG_END", P.imgEnd(7), "xfer=7")
    vec("IMG_CTRL_DELETE", P.imgCtrl(P.CTRL_DELETE_CUSTOM), "op=2")
    check(P.crc32("123456789".toByteArray()) == 0xCBF43926L, "crc32 check value")
    check(P.imgData(1, 0, ByteArray(190), 0, 190).size == 3 + 3 + 190, "a data chunk fits one frame")
    val sbx = byteArrayOf(1, 1, 0, 5, 2, 0, 0x15, 1, 120, 0, 1, 7, 0x2C, 0x01, 0, 0x26, 0x39, 0xF4.toByte(), 0xCB.toByte(), 0x44, 0x33, 0x22, 0x11)
    val px = P.parseStatus(sbx)!!
    check(px.screen == 5 && px.hasExt && px.xferState == P.XFER_RECEIVING && px.xferId == 7 && px.xferNext == 300 && px.xferReason == 0 && px.customCrc == 0xCBF43926L, "extended status decoded")
    check(px.artCrc == 0x11223344L, "album-art CRC decoded from the extended status")
    check(!P.parseStatus(sb)!!.hasExt && P.parseStatus(sb)!!.customCrc == 0L && P.parseStatus(sb)!!.artCrc == 0L, "a 10-byte status from older firmware has no transfer fields")
    out.append("STATUS_EXT|${hex(sbx)}|screen=5 receiving xfer=7 next=300 crc=cbf43926 art=11223344\n")
    extrasTests()

    if (args.isNotEmpty()) File(args[0]).writeText(out.toString())
    println("Device protocol tests: $passed checks passed" + (if (args.isNotEmpty()) ", vectors written" else ""))
}
