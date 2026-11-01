package com.smartisland.app

import java.text.Normalizer
import java.util.Locale

/**
 * Lyrics, without any Android or network code (unit-tested on a desktop): parsing synchronised LRC text, picking the line for a
 * playback position, normalising multilingual text, matching a search answer to the track that is actually playing, and falling
 * back between providers. Only text that carries real timestamps counts as synchronised; plain text is reported separately and is
 * never presented as if it were in time.
 */
object Lrc {
    class Line(val ms: Long, val text: String)

    private val TS = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val META = Regex("""^\[[A-Za-z]{2,}:.*]$""")           // [ar:..] [ti:..] [by:..] and friends: not lyrics

    /** Lines sorted by time. Empty when the text has fewer than two timestamped lines (= not synchronised). */
    fun parse(text: String?): List<Line> {
        if (text.isNullOrBlank()) return emptyList()
        val out = ArrayList<Line>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || META.matches(line)) continue
            val stamps = TS.findAll(line).toList()
            if (stamps.isEmpty()) continue
            val body = line.substring(stamps.last().range.last + 1).trim()
            for (m in stamps) {
                val min = m.groupValues[1].toLong(); val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
                out.add(Line(min * 60_000L + sec * 1000L + ms, body))
            }
        }
        out.sortBy { it.ms }
        return if (out.size >= 2) out else emptyList()
    }

    /** Index of the line that is current at [posMs] (the last one whose time has come), or -1 before the first line. */
    fun indexAt(lines: List<Line>, posMs: Long): Int {
        var lo = 0; var hi = lines.size - 1; var ans = -1
        while (lo <= hi) { val mid = (lo + hi) ushr 1; if (lines[mid].ms <= posMs) { ans = mid; lo = mid + 1 } else hi = mid - 1 }
        return ans
    }

    /** Non-blank text of a neighbouring line (instrumental gaps are skipped), or "". */
    fun neighbour(lines: List<Line>, idx: Int, dir: Int): String {
        var i = idx + dir
        while (i >= 0 && i < lines.size) { if (lines[i].text.isNotBlank()) return lines[i].text; i += dir }
        return ""
    }
}

/** What a lyrics source answered for one track. [plain] is only ever shown without claiming it is in time. */
class LyricsResult(
    val state: Int,
    val synced: List<Lrc.Line> = emptyList(),
    val plain: List<String> = emptyList(),
    val detail: String = "",
    val provider: String = ""
) {
    val hasLyrics: Boolean get() = synced.isNotEmpty() || plain.isNotEmpty()
}

/** Everything a provider may use to identify a track. Providers must only send what they are documented to accept. */
class LyricsQuery(val title: String, val artist: String = "", val album: String = "", val durationSec: Int = 0)

/**
 * A lyrics source. Implementations must be legitimate, documented services with their own terms; nothing here scrapes a player
 * or bypasses access control. Android's media session carries no lyrics, and Spotify has no public lyrics API.
 * [fetch] blocks: call it from a background thread.
 */
interface LyricsProvider {
    val name: String
    /** True when this source can return time-synchronised lyrics. Plain-only sources are skipped once a plain answer exists. */
    val supportsSynced: Boolean
    fun fetch(query: LyricsQuery): LyricsResult
}

/**
 * Turns a provider's plain lyrics into display lines. HTML from web APIs is reduced to text, entities are decoded, and section
 * labels ("[Verse 1]", "Chorus:") are dropped. Unicode is never transliterated or simplified: Devanagari, Arabic, Urdu, Korean,
 * Japanese and every other script pass through unchanged.
 */
object PlainLyrics {
    private val TAGS = Regex("(?is)<[^>]+>")
    private val SECTION = Regex(
        "^\\s*[\\[【(]\\s*(verse|chorus|bridge|intro|outro|hook|pre[- ]?chorus|refrain|instrumental|interlude|spoken|translation)\\b.*[\\]】)]\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val SECTION_TEXT = Regex(
        "^\\s*(verse|chorus|bridge|intro|outro|hook|pre[- ]?chorus|refrain)\\s*(\\d+)?\\s*:?\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val INLINE_SECTION = Regex(
        "(?i)\\[\\s*(verse|chorus|bridge|intro|outro|hook|pre[- ]?chorus|refrain|instrumental|interlude)\\b[^\\]]*\\]"
    )
    /** Credit/annotation tags such as [Jungkook] or [version romanisée]: metadata, not a lyric line. */
    private val TAG_LINE = Regex("^\\[[^\\]]{1,48}\\]$")
    private val ENTITY = Regex("&(#x?[0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});")

    fun split(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        var t = text.replace("\r\n", "\n").replace('\r', '\n')
        t = t.replace(Regex("(?i)<br\\s*/?>"), "\n")
        t = TAGS.replace(t, " ")
        t = INLINE_SECTION.replace(t, "\n")
        t = decode(t)
        val out = ArrayList<String>()
        for (raw in t.split('\n')) {
            val line = raw.replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()
            if (line.isEmpty()) continue
            if (SECTION.matches(line) || SECTION_TEXT.matches(line) || TAG_LINE.matches(line)) continue
            if (line == "..." || line == "\u2026" || line.all { it == '\u266A' || it == ' ' }) continue
            out.add(line)
            if (out.size >= 500) break
        }
        return if (out.size >= 2) out else emptyList()          // one line is a fragment, not lyrics
    }

    private fun decode(s: String): String {
        val named = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")
        return ENTITY.replace(s) { m ->
            val e = m.groupValues[1]
            if (e.startsWith("#x") || e.startsWith("#X")) {
                e.substring(2).toIntOrNull(16)?.let { cp -> codePoint(cp) } ?: m.value
            } else if (e.startsWith("#")) {
                e.substring(1).toIntOrNull()?.let { cp -> codePoint(cp) } ?: m.value
            } else named[e.lowercase(Locale.ROOT)] ?: m.value
        }
    }

    private fun codePoint(cp: Int): String = try {
        if (cp <= 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF) "" else String(Character.toChars(cp))
    } catch (e: Exception) { "" }
}

/**
 * Decides whether a provider's search result is really the track that is playing. Title, artist, album and length all count, and
 * versions that the playing track does not mention (live, remix, cover, acoustic, karaoke, sped up, ...) are rejected outright, so
 * a remix never shows the original's lyrics (or the other way round).
 */
object LyricsMatcher {
    class Candidate(
        val title: String,
        val artist: String = "",
        val album: String = "",
        val durationSec: Int = 0,
        val synced: String = "",
        val plain: String = "",
        val instrumental: Boolean = false
    )

    private val HARD_VERSIONS = listOf(
        "remix", "rmx", "live", "acoustic", "unplugged", "cover", "karaoke", "instrumental", "demo", "nightcore",
        "sped up", "slowed", "reverb", "8d", "mashup", "medley", "reprise", "extended", "radio edit", "orchestral",
        "piano version", "lullaby", "female version", "male version", "duet", "solo version", "tribute", "parody"
    )

    /** Lowercase + composition-preserving normal form. Combining marks are KEPT: Devanagari matras and Arabic harakat matter. */
    fun fold(s: String): String {
        if (s.isBlank()) return ""
        val n = Normalizer.normalize(s, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        return n.replace('\u2018', '\'').replace('\u2019', '\'').replace('\u201C', '"').replace('\u201D', '"')
            .replace(Regex("[^\\p{L}\\p{N}\\p{M}]+"), " ").trim()
    }

    /** Title with bracketed extras and " - ..." decorations removed, for comparison. Version words are checked separately. */
    fun baseTitle(s: String): String {
        val t = s.replace(Regex("\\s*[\\[(（【].*?[\\])）】]"), " ")
            .replace(Regex("\\s+-\\s+.*$"), " ")
            .replace(Regex("(?i)\\s+(feat\\.?|ft\\.?|featuring)\\s+.*$"), " ")
            .replace(Regex("\\s+"), " ").trim()
        return t.ifEmpty { s.trim() }
    }

    fun versionTokens(s: String): Set<String> {
        val f = " ${fold(s)} "
        val out = LinkedHashSet<String>()
        for (v in HARD_VERSIONS) if (f.contains(" $v ") || f.contains("($v") || f.contains("[$v")) out.add(v)
        return out
    }

    fun hasHardVersion(s: String): Boolean = versionTokens(s).isNotEmpty()

    /** True when the candidate's version markers are exactly the ones the playing title asks for. */
    fun versionCompatible(queryTitle: String, candidateTitle: String): Boolean {
        val q = versionTokens(queryTitle)
        val c = versionTokens(candidateTitle)
        if (!c.containsAll(q)) return false                      // playing "Song (Live)", candidate is the studio take
        return c.isEmpty() || q.isNotEmpty()                     // playing the studio take, candidate is a live/remix take
    }

    private fun tokens(s: String): Set<String> = fold(s).split(' ').filter { it.isNotEmpty() }.toSet()

    private fun bigrams(s: String): Set<String> {
        val f = fold(s).replace(" ", "")
        if (f.length < 2) return if (f.isEmpty()) emptySet() else setOf(f)
        val out = LinkedHashSet<String>()
        for (i in 0 until f.length - 1) out.add(f.substring(i, i + 2))
        return out
    }

    /** 0..1 text similarity: token Jaccard, with character bigrams for scripts that do not separate words with spaces. */
    fun similarity(a: String, b: String): Double {
        val fa = fold(a); val fb = fold(b)
        if (fa.isEmpty() || fb.isEmpty()) return 0.0
        if (fa == fb) return 1.0
        val ta = tokens(fa); val tb = tokens(fb)
        val inter = ta.intersect(tb).size.toDouble()
        val jaccard = if (ta.isEmpty() || tb.isEmpty()) 0.0 else inter / (ta.size + tb.size - inter)
        val ba = bigrams(fa); val bb = bigrams(fb)
        val bi = ba.intersect(bb).size.toDouble()
        val dice = if (ba.isEmpty() || bb.isEmpty()) 0.0 else 2.0 * bi / (ba.size + bb.size)
        return maxOf(jaccard, dice)
    }

    private fun splitArtists(s: String): List<String> =
        fold(s).split(Regex("\\s*(?:,|&|;|\\bfeat\\b|\\bft\\b|\\bx\\b|\\band\\b)\\s*")).map { it.trim() }.filter { it.isNotEmpty() }

    fun artistScore(queryArtist: String, candidateArtist: String): Double {
        if (queryArtist.isBlank()) return 1.0
        if (candidateArtist.isBlank()) return 0.0
        val q = splitArtists(queryArtist); val c = splitArtists(candidateArtist)
        if (q.isEmpty() || c.isEmpty()) return 0.0
        if (q.any { it in c } || c.any { it in q }) return 1.0
        var best = 0.0
        for (a in q) for (b in c) best = maxOf(best, similarity(a, b))
        return best
    }

    fun durationScore(querySec: Int, candidateSec: Int): Double {
        if (querySec <= 0 || candidateSec <= 0) return 0.5
        val gap = Math.abs(querySec - candidateSec)
        return when {
            gap <= 3 -> 1.0
            gap >= 90 -> 0.0
            else -> 1.0 - (gap - 3) / 87.0
        }
    }

    /** Combined match score. Returns -1 when the version markers or the title make the candidate unusable. */
    fun score(q: LyricsQuery, c: Candidate): Double {
        if (!versionCompatible(q.title, c.title)) return -1.0
        if (c.instrumental && !versionTokens(q.title).contains("instrumental")) return -1.0
        val title = similarity(baseTitle(q.title), baseTitle(c.title))
        val artist = artistScore(q.artist, c.artist)
        val album = if (q.album.isBlank() || c.album.isBlank()) 0.5 else similarity(baseTitle(q.album), baseTitle(c.album))
        val dur = durationScore(q.durationSec, c.durationSec)
        var s = 0.58 * title + 0.24 * artist + 0.08 * album + 0.10 * dur
        if (c.synced.isNotBlank()) s += 0.02
        return s
    }

    /** Best candidate that really is this track, or null. Requires a good title match and, when known, a plausible artist. */
    fun pick(q: LyricsQuery, candidates: List<Candidate>): Candidate? {
        var best: Candidate? = null
        var bestScore = 0.0
        val minTitle = if (q.artist.isBlank()) 0.72 else 0.55
        for (c in candidates) {
            if (similarity(baseTitle(q.title), baseTitle(c.title)) < minTitle) continue
            if (q.artist.isNotBlank() && artistScore(q.artist, c.artist) < 0.30) continue
            // a two-minute difference is not the same recording even when the title and artist match (extended/live edits)
            if (q.durationSec > 0 && c.durationSec > 0 && Math.abs(q.durationSec - c.durationSec) > 120) continue
            val s = score(q, c)
            if (s < 0.58) continue
            if (s > bestScore) { best = c; bestScore = s }
        }
        return best
    }
}

/**
 * Reading pagination for plain (untimed) lyrics: a three-line window that advances one line every [stepMs] of playback. This is
 * not synchronisation and is never flagged as such; it only keeps a long text readable on the three-line display.
 */
object PlainPager {
    class Window(val idx: Int, val prev: String, val cur: String, val next: String)

    fun at(lines: List<String>, posMs: Long, stepMs: Long = 6000L): Window {
        if (lines.isEmpty()) return Window(-1, "", "", "")
        val step = if (stepMs > 0) stepMs else 6000L
        val i = (posMs.coerceAtLeast(0L) / step).toInt().coerceIn(0, lines.size - 1)
        return Window(i, if (i > 0) lines[i - 1] else "", lines[i], if (i + 1 < lines.size) lines[i + 1] else "")
    }
}

/**
 * Tries every configured source in order. A synced answer wins at once; a plain answer is remembered and a later provider may still
 * supply a synced version. Only when no source answered at all and at least one failed on the network is the result OFFLINE.
 */
class LyricsService(private val providers: List<LyricsProvider>) {
    fun fetch(query: LyricsQuery): LyricsResult {
        if (query.title.isBlank()) return LyricsResult(DeviceProtocol.LY_UNAVAILABLE, detail = "no title")
        var plain: LyricsResult? = null
        var reached = false
        var networkError = false
        var detail = "no match"
        for (p in providers) {
            if (plain != null && !p.supportsSynced) continue
            val r = try { p.fetch(query) } catch (e: Exception) {
                LyricsResult(DeviceProtocol.LY_OFFLINE, detail = "error: " + e.javaClass.simpleName)
            }
            when (r.state) {
                DeviceProtocol.LY_AVAILABLE -> return r
                DeviceProtocol.LY_UNSYNCED -> { reached = true; if (plain == null) plain = r }
                DeviceProtocol.LY_OFFLINE -> { networkError = true; if (r.detail.isNotBlank()) detail = r.detail }
                else -> { reached = true; if (r.detail.isNotBlank()) detail = r.detail }
            }
        }
        plain?.let { return it }
        return if (!reached && networkError) LyricsResult(DeviceProtocol.LY_OFFLINE, detail = detail)
        else LyricsResult(DeviceProtocol.LY_UNAVAILABLE, detail = detail)
    }
}
