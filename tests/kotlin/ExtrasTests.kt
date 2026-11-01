import com.smartisland.app.DeviceProtocol as P
import com.smartisland.app.ImageSender
import com.smartisland.app.Lrc
import com.smartisland.app.LyricsMatcher
import com.smartisland.app.LyricsProvider
import com.smartisland.app.LyricsQuery
import com.smartisland.app.LyricsResult
import com.smartisland.app.LyricsService
import com.smartisland.app.NowPlaying
import com.smartisland.app.PlainLyrics
import com.smartisland.app.PlainPager
import com.smartisland.app.Provider
import java.io.ByteArrayOutputStream
import java.util.Random

// Called from DeviceProtocolTest.kt's main(): LRC parsing, multilingual lyric matching, provider fallback and the image sender
// against a simulated lossy device.

private fun u32(f: ByteArray, at: Int): Long = (f[at].toLong() and 0xFF) or ((f[at + 1].toLong() and 0xFF) shl 8) or ((f[at + 2].toLong() and 0xFF) shl 16) or ((f[at + 3].toLong() and 0xFF) shl 24)
private fun u16(f: ByteArray, at: Int): Int = (f[at].toInt() and 0xFF) or ((f[at + 1].toInt() and 0xFF) shl 8)

/** Mirrors firmware/MeowIsland/image_rx.h closely enough to exercise the sender: in-order chunks only, CRC check at END, same ids/states/reasons. */
private class SimDevice(var storedCrc: Long = 0L) {
    var id = 0; var kind = 0; var state = P.XFER_IDLE; var next = 0; var reason = 0; var total = 0; var want = 0L
    var buf = ByteArrayOutputStream()
    fun status() = P.Status(1, 1, 0, 0, 0, 0, false, false, false, false, false, 1, 100, true, state, id, next, reason, storedCrc)
    fun onFrame(f: ByteArray) {
        val type = f[1].toInt() and 0xFF; val p = f.copyOfRange(3, f.size)
        when (type) {
            P.MSG_IMG_BEGIN -> {
                val xid = p[0].toInt() and 0xFF
                if (state == P.XFER_RECEIVING && xid == id) return                 // a repeated BEGIN keeps the progress
                id = xid; kind = p[1].toInt(); total = u32(p, 2).toInt(); want = u32(p, 6); next = 0; reason = 0; buf = ByteArrayOutputStream()
                state = if (kind == P.KIND_CUSTOM && storedCrc != 0L && storedCrc == want) P.XFER_DONE else P.XFER_RECEIVING
            }
            P.MSG_IMG_DATA -> if (state == P.XFER_RECEIVING && (p[0].toInt() and 0xFF) == id && u16(p, 1) == next) { buf.write(p, 3, p.size - 3); next++ }
            P.MSG_IMG_END -> if (state == P.XFER_RECEIVING && (p[0].toInt() and 0xFF) == id) {
                val got = buf.toByteArray()
                if (got.size != total) { state = P.XFER_FAILED; reason = 6 }
                else if (P.crc32(got) != want) { state = P.XFER_FAILED; reason = 5 }
                else { state = P.XFER_DONE; if (kind == P.KIND_CUSTOM) storedCrc = want }
            }
            P.MSG_IMG_CTRL -> if (p[0].toInt() == P.CTRL_ABORT && state == P.XFER_RECEIVING) state = P.XFER_IDLE
        }
    }
}

private class Outcome(var ok: Boolean? = null, var reason: Int = -1, var message: String = "", var lastProgress: Int = 0)

private fun runTransfer(data: ByteArray, kind: Int, dev: SimDevice, drop: Double, dup: Double, seed: Long, wantStatusPush: Boolean = true): Outcome {
    val rnd = Random(seed); val res = Outcome(); var now = 1000L
    val wire = ArrayList<ByteArray>()
    val s = ImageSender(9, kind, data, 240, 320, { wire.add(it) }, { ok, reason, msg -> res.ok = ok; res.reason = reason; res.message = msg }, { d, _ -> res.lastProgress = d })
    s.start(now)
    var steps = 0
    while (!s.done && steps++ < 20000) {
        now += 250
        val batch = ArrayList(wire); wire.clear()
        for (f in batch) { if (rnd.nextDouble() < drop) continue; dev.onFrame(f); if (rnd.nextDouble() < dup) dev.onFrame(f) }
        if (wantStatusPush) s.onStatus(dev.status(), now)
        s.tick(now)
    }
    return res
}

fun extrasTests() {
    // ---- LRC ----
    val lrc = "[ar:Someone]\n[ti:Song]\n[00:12.50]First line\n[00:20.00]Second line\n[01:05.5]Third\n[00:30.00][00:40.00]Repeat\n[02:00.00]\n"
    val l = Lrc.parse(lrc)
    check(l.size == 6 && l[0].text == "First line" && l[0].ms == 12_500L && l[4].ms == 65_500L && l[5].text == "", "lrc: tags skipped, multiple timestamps expanded, sorted")
    check(Lrc.indexAt(l, 0) == -1 && Lrc.indexAt(l, 12_500) == 0 && Lrc.indexAt(l, 19_999) == 0 && Lrc.indexAt(l, 20_000) == 1 && Lrc.indexAt(l, 9_999_999) == 5, "lrc: current line follows the position")
    check(Lrc.neighbour(l, 1, -1) == "First line" && Lrc.neighbour(l, 4, 1) == "" && Lrc.neighbour(l, 0, -1) == "", "lrc: neighbours skip blank gaps and stop at the ends")
    check(Lrc.parse("just\nplain\nlyrics").isEmpty() && Lrc.parse("[00:01.00]only one").isEmpty() && Lrc.parse(null).isEmpty() && Lrc.parse("").isEmpty(), "lrc: text without timestamps is NOT treated as synchronised")
    check(Lrc.parse("[00:01.5]a\n[00:02.50]b")[0].ms == 1500L && Lrc.parse("[00:01]a\n[00:02:25]b")[1].ms == 2250L, "lrc: fraction lengths")

    // ---- image sender over hostile links ----
    val data = ByteArray(5000) { (it * 31 + 7).toByte() }
    val crc = P.crc32(data)
    run { val dev = SimDevice(); val r = runTransfer(data, P.KIND_CUSTOM, dev, 0.0, 0.0, 1); check(r.ok == true && dev.storedCrc == crc && dev.buf.toByteArray().contentEquals(data), "sender: clean link delivers the exact bytes") }
    for (seed in 1L..20L) {
        val dev = SimDevice(); val r = runTransfer(data, P.KIND_CUSTOM, dev, 0.25, 0.2, seed)
        check(r.ok == true && dev.buf.toByteArray().contentEquals(data), "sender: 25% loss and 20% duplicates still end with the exact bytes (seed $seed)")
    }
    run { val dev = SimDevice(storedCrc = crc); val r = runTransfer(data, P.KIND_CUSTOM, dev, 0.0, 0.0, 3); check(r.ok == true && dev.buf.size() == 0, "sender: a design the device already has is acknowledged without sending data") }
    run { val dev = SimDevice(); val bad = data.copyOf(); bad[10] = 0; val s2 = runTransfer(bad, P.KIND_CUSTOM, dev, 0.0, 0.0, 4); check(s2.ok == true, "sender: sanity (different content is fine)") }
    run { val dev = SimDevice(); val r = runTransfer(data, P.KIND_CUSTOM, dev, 1.0, 0.0, 5); check(r.ok == false && r.reason == ImageSender.R_STALLED, "sender: a device that never answers ends as 'stopped responding', not an endless loop") }
    run {   // a device that reports a failure (e.g. CRC)
        var failed: Int? = null
        val s = ImageSender(4, P.KIND_CUSTOM, data, 240, 320, { }, { ok, reason, _ -> if (!ok) failed = reason })
        s.start(1000)
        s.onStatus(P.Status(1, 1, 0, 0, 0, 0, false, false, false, false, false, 1, 100, true, P.XFER_FAILED, 4, 0, 3, 0L), 1500)
        check(failed == 3 && s.done, "sender: device failure reason is passed on")
        s.onStatus(P.Status(1, 1, 0, 0, 0, 0, false, false, false, false, false, 1, 100, true, P.XFER_DONE, 4, 0, 0, crc), 1600)
        check(failed == 3, "sender: nothing happens after the transfer ended")
    }
    run {   // a status of an older transfer is ignored; DONE with a different stored crc is not a success for the custom screen
        var res: Boolean? = null
        val s = ImageSender(4, P.KIND_CUSTOM, data, 240, 320, { }, { ok, _, _ -> res = ok })
        s.start(0); s.onStatus(P.Status(1, 1, 0, 0, 0, 0, false, false, false, false, false, 1, 100, true, P.XFER_DONE, 3, 0, 0, crc), 10)
        check(res == null, "sender: a stale DONE of another transfer id is ignored")
        s.onStatus(P.Status(1, 1, 0, 0, 0, 0, false, false, false, false, false, 1, 100, true, P.XFER_DONE, 4, 0, 0, crc + 1), 20)
        check(res == false, "sender: DONE with a different stored checksum is not reported as success")
    }
    run { var lost = false; val s = ImageSender(2, P.KIND_ART, ByteArray(100), 80, 80, { }, { ok, _, msg -> lost = !ok && msg.contains("connection")}); s.start(0); s.linkLost(); check(lost && s.done, "sender: link loss ends the transfer with an explanation") }
    run { val dev = SimDevice(); val r = runTransfer(ByteArray(80 * 80 * 2) { it.toByte() }, P.KIND_ART, dev, 0.2, 0.1, 8); check(r.ok == true && dev.storedCrc == 0L, "sender: album art transfers and does not touch the stored custom design") }

    // ---- provider-independent model ----
    check(Provider.isVideo("com.google.android.youtube") && Provider.isVideo("com.google.android.apps.youtube.music") && !Provider.isVideo("com.amazon.mp3"), "video apps are excluded from lyrics, music apps are not")
    check(Provider.sourceLabel("com.amazon.mp3") == "AMAZON MUSIC" && Provider.sourceLabel("com.spotify.music") == "SPOTIFY" && Provider.sourceLabel("com.example.player", "My Player") == "MY PLAYER", "provider labels")
    val np = NowPlaying(title = "Song", durationMs = 100_000, positionMs = 90_000, positionAt = 1000, playing = true)
    check(np.positionNow(15_000) == 100_000L && np.active && np.lyricsEligible, "position is clamped to the track length")
    check(NowPlaying(pkg = "com.google.android.apps.youtube.music", title = "Video").lyricsEligible.not(), "YouTube Music never gets lyrics")
    check(Provider.cacheKey("p", "", "Song  Name", "A", "B") == Provider.cacheKey("p", "", "song name", "a", "b") && Provider.cacheKey("p", "id1", "Song", "A", "B") != Provider.cacheKey("p", "id2", "Song", "A", "B"), "art/lyrics keys are stable but include the player's track id")

    // ---- plain lyrics, multilingual and RTL ----
    val plain = PlainLyrics.split("<p>[Verse 1]</p><p>Pehli line &amp; second</p><br>[Jungkook]<br>[version romanisée]<br>कभी कभी अदिति<br>میرے دل میں<br>사랑해요<br>愛してる")
    check(plain.size == 5 && plain[0] == "Pehli line & second", "plain lyrics: tags and section labels removed, entities decoded")
    check(plain[1] == "कभी कभी अदिति" && plain[2] == "میرے دل میں" && plain[3] == "사랑해요" && plain[4] == "愛してる", "plain lyrics: Devanagari, Urdu, Korean and Japanese pass through unchanged")
    check(PlainLyrics.split("just one line") == emptyList<String>(), "a single fragment is not treated as lyrics")
    val page = PlainPager.at(listOf("one", "two", "three", "four"), 0)
    check(page.idx == 0 && page.prev == "" && page.cur == "one" && page.next == "two", "plain pager starts at the first line")
    val page2 = PlainPager.at(listOf("one", "two", "three", "four"), 12_500)
    check(page2.idx == 2 && page2.prev == "two" && page2.cur == "three" && page2.next == "four", "plain pager advances one line every six seconds")
    check(PlainPager.at(listOf("one"), 99_000).idx == 0 && PlainPager.at(emptyList(), 5000).cur == "", "plain pager clamps to the text")

    // ---- accurate matching: title/artist/album/length + version markers ----
    fun q(title: String, artist: String = "Arijit Singh", album: String = "", dur: Int = 240) = LyricsQuery(title, artist, album, dur)
    fun c(title: String, artist: String = "Arijit Singh", album: String = "", dur: Int = 240, synced: String = "[00:01.00]a\n[00:02.00]b", plain: String = "") =
        LyricsMatcher.Candidate(title, artist, album, dur, synced, plain)
    check(LyricsMatcher.pick(q("Tum Hi Ho"), listOf(c("Tum Hi Ho"))) != null, "exact title and artist matches")
    check(LyricsMatcher.pick(q("Tum Hi Ho"), listOf(c("Tum Hi Ho (Live)", artist = "Arijit Singh"))) == null, "a live version is not accepted for the studio track")
    check(LyricsMatcher.pick(q("Tum Hi Ho (Live)"), listOf(c("Tum Hi Ho"))) == null, "the studio take is not accepted for a live track")
    check(LyricsMatcher.pick(q("Tum Hi Ho (Live)"), listOf(c("Tum Hi Ho (Live)"))) != null, "the matching live version is accepted")
    check(LyricsMatcher.pick(q("Tum Hi Ho"), listOf(c("Tum Hi Ho (Remastered 2011)"))) != null, "a remaster is not a different lyric version")
    check(LyricsMatcher.pick(q("Tum Hi Ho"), listOf(c("Tum Hi Ho", artist = "Some Cover Band"))) == null, "a cover by another artist is rejected")
    check(LyricsMatcher.pick(q("Tum Hi Ho"), listOf(c("Tum Hi Ho", dur = 400))) == null, "a two-minute-longer recording is not the same version")
    check(LyricsMatcher.pick(q("Tum Hi Ho"), listOf(c("Tum Hi Ho", dur = 250))) != null, "a near-identical length is accepted")
    check(LyricsMatcher.pick(q("Kesariya", artist = "Arijit Singh", dur = 268), listOf(c("Kesariya", "Arijit Singh", "Brahmastra", 268, synced = ""), c("Kesariya", "Arijit Singh", "Brahmastra", 268)))?.synced?.isNotEmpty() == true, "the synced candidate wins over a plain one")
    check(LyricsMatcher.versionCompatible("Song", "Song (Live)").not() && LyricsMatcher.versionCompatible("Song (Live)", "Song").not() && LyricsMatcher.versionCompatible("Song (Live)", "Song (Live at Wembley)"), "version compatibility is symmetric")
    check(LyricsMatcher.fold("कभी कभी") == "कभी कभी" && LyricsMatcher.fold("میرے دل") == "میرے دل", "text folding keeps Devanagari matras and Arabic letters")
    check(LyricsMatcher.similarity("사랑해요", "사랑해요") == 1.0 && LyricsMatcher.similarity("愛してる", "愛してる") == 1.0, "CJK titles compare exactly")

    // ---- provider fallback ----
    class Fake(override val name: String, override val supportsSynced: Boolean, private val r: LyricsResult, private val calls: MutableList<String>? = null) : LyricsProvider {
        override fun fetch(query: LyricsQuery): LyricsResult { calls?.add(name); return r }
    }
    val calls = ArrayList<String>()
    val offline = Fake("a", true, LyricsResult(P.LY_OFFLINE, detail = "no network"), calls)
    val plainOnly = Fake("b", false, LyricsResult(P.LY_UNSYNCED, plain = listOf("one", "two"), provider = "b"), calls)
    val synced = Fake("c", true, LyricsResult(P.LY_AVAILABLE, synced = Lrc.parse("[00:01.00]a\n[00:02.00]b"), provider = "c"), calls)
    val r1 = LyricsService(listOf(offline, plainOnly)).fetch(q("Song"))
    check(r1.state == P.LY_UNSYNCED && r1.plain.size == 2 && calls == listOf("a", "b"), "a network failure falls through to the plain provider")
    val r2 = LyricsService(listOf(offline, synced)).fetch(q("Song"))
    check(r2.state == P.LY_AVAILABLE && r2.synced.size == 2, "a later synced provider wins")
    val r3 = LyricsService(listOf(Fake("x", true, LyricsResult(P.LY_UNAVAILABLE, detail = "none")), Fake("y", true, LyricsResult(P.LY_UNAVAILABLE, detail = "none")))).fetch(q("Song"))
    check(r3.state == P.LY_UNAVAILABLE, "two 'not found' answers are UNAVAILABLE, never OFFLINE")
    val r4 = LyricsService(listOf(Fake("x", true, LyricsResult(P.LY_OFFLINE)), Fake("y", true, LyricsResult(P.LY_OFFLINE)))).fetch(q("Song"))
    check(r4.state == P.LY_OFFLINE, "no source reachable is OFFLINE")
}
