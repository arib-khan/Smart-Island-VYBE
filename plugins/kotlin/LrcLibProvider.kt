package com.smartisland.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Lyrics source 1: LRCLIB (https://lrclib.net), a free community lyrics database with a documented public API. It is the only
 * source used here that returns time-synchronised LRC. Only the song title, artist, album and length are sent over HTTPS; no
 * account, notification content or device identifier. The provider is only called while the user has online lyrics switched on.
 *
 * A search answer is not trusted blindly: [LyricsMatcher] compares title, artist, album and length and rejects live/remix/cover
 * versions the playing track does not mention. A server that is merely busy (HTTP 429/5xx) is reported as OFFLINE; a missing song
 * (404) is UNAVAILABLE, so the display never claims the phone is offline when the lookup simply found nothing.
 */
class LrcLibProvider : LyricsProvider {
    override val name = "lrclib"
    override val supportsSynced = true

    private class Reply(val code: Int, val body: String)

    companion object {
        // LRCLIB asks clients to serialise requests, leave 200-500 ms between them and honour Retry-After. The app submits at
        // most one lookup at a time (see DeviceExtras.Lyrics), and this gate keeps a 429 from turning into a retry storm.
        private const val MIN_GAP_MS = 300L
        @Volatile private var lastRequestAt = 0L
        @Volatile private var blockedUntil = 0L
    }

    private fun waitTurn() {
        val now = System.currentTimeMillis()
        if (now < blockedUntil) throw IOException("rate limited by lyrics service")
        val since = now - lastRequestAt
        if (lastRequestAt > 0 && since < MIN_GAP_MS) {
            try { Thread.sleep(MIN_GAP_MS - since) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        }
        lastRequestAt = System.currentTimeMillis()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): Reply {
        waitTurn()
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 6000; conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "MeowIsland/1.1 (Android TFT lyrics display; https://github.com/arib-khan/MEOW-Island)")
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code == 429 || code == 503) {
                val wait = (conn.getHeaderField("Retry-After")?.trim()?.toIntOrNull() ?: 30).coerceIn(1, 300)
                blockedUntil = System.currentTimeMillis() + wait * 1000L
            }
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Reply(code, body)
        } finally {
            try { conn?.disconnect() } catch (e: Exception) { }
        }
    }

    private fun primaryArtist(a: String): String =
        a.split(Regex("""\s*(,|&|;|\sfeat\.?\s|\sft\.?\s|\sx\s)\s*""", RegexOption.IGNORE_CASE)).firstOrNull()?.trim()?.ifEmpty { a } ?: a

    /** Every object of a /search array (or the single object of /get) as a matcher candidate. */
    private fun candidates(body: String, isSearch: Boolean): List<LyricsMatcher.Candidate> {
        val items = ArrayList<LyricsMatcher.Candidate>()
        if (isSearch) {
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) addCandidate(arr.optJSONObject(i), items)
        } else {
            addCandidate(JSONObject(body), items)
        }
        return items
    }

    private fun addCandidate(o: JSONObject?, out: MutableList<LyricsMatcher.Candidate>) {
        if (o == null) return
        val title = o.optString("trackName", "")
        if (title.isBlank()) return
        out.add(
            LyricsMatcher.Candidate(
                title = title,
                artist = o.optString("artistName", ""),
                album = o.optString("albumName", ""),
                durationSec = o.optDouble("duration", 0.0).toInt(),
                synced = if (o.isNull("syncedLyrics")) "" else o.optString("syncedLyrics", ""),
                plain = if (o.isNull("plainLyrics")) "" else o.optString("plainLyrics", ""),
                instrumental = o.optBoolean("instrumental", false)
            )
        )
    }

    override fun fetch(query: LyricsQuery): LyricsResult {
        if (query.title.isBlank()) return LyricsResult(DeviceProtocol.LY_UNAVAILABLE, detail = "no title")
        val clean = LyricsMatcher.baseTitle(query.title)
        val artist = primaryArtist(query.artist)
        val base = "https://lrclib.net/api/"
        val album = if (query.album.isNotBlank()) "&album_name=" + enc(query.album) else ""
        val dur = if (query.durationSec in 1..3600) "&duration=" + query.durationSec else ""
        val urls = ArrayList<String>()
        urls.add(base + "get?track_name=" + enc(query.title) + "&artist_name=" + enc(query.artist) + album + dur)
        urls.add(base + "search?track_name=" + enc(query.title) + "&artist_name=" + enc(query.artist))
        if (clean != query.title || artist != query.artist) urls.add(base + "search?track_name=" + enc(clean) + "&artist_name=" + enc(artist))
        urls.add(base + "search?q=" + enc("$clean $artist".trim()))

        var plain: LyricsResult? = null
        var reached = false
        var detail = "no match"
        try {
            for (u in urls) {
                val r = get(u)
                if (r.code == 429 || r.code >= 500) {
                    return plain ?: LyricsResult(DeviceProtocol.LY_OFFLINE, detail = "lyrics service busy (HTTP ${r.code})")
                }
                if (r.code != 200) { detail = "HTTP ${r.code}"; continue }
                reached = true
                val list = try { candidates(r.body, u.contains("/search")) } catch (e: Exception) { emptyList() }
                val c = LyricsMatcher.pick(query, list) ?: continue
                val lines = Lrc.parse(c.synced)
                if (lines.size >= 2) return LyricsResult(DeviceProtocol.LY_AVAILABLE, synced = lines, detail = "found (${lines.size} lines)", provider = name)
                val p = PlainLyrics.split(c.plain)
                if (p.isNotEmpty()) {
                    detail = "plain lyrics only"
                    if (plain == null) plain = LyricsResult(DeviceProtocol.LY_UNSYNCED, plain = p, detail = detail, provider = name)
                } else {
                    detail = "found, but without lyrics text"
                }
            }
            return plain ?: if (!reached) LyricsResult(DeviceProtocol.LY_OFFLINE, detail = detail)
            else LyricsResult(DeviceProtocol.LY_UNAVAILABLE, detail = detail)
        } catch (e: IOException) {
            return plain ?: LyricsResult(DeviceProtocol.LY_OFFLINE, detail = "network: " + e.javaClass.simpleName + (e.message?.let { " (" + it.take(60) + ")" } ?: ""))
        } catch (e: Exception) {
            return plain ?: LyricsResult(DeviceProtocol.LY_UNAVAILABLE, detail = "error: " + e.javaClass.simpleName)
        }
    }
}
