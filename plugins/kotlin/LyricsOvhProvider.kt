package com.smartisland.app

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Lyrics source 2: lyrics.ovh (https://api.lyrics.ovh), a free, documented lyrics API with no account or key. It returns PLAIN
 * text only, so it is the fallback used when LRCLIB has no answer (or is unreachable). It is never presented as synchronised.
 *
 * The API does not return the matched title/artist/duration, so it cannot prove which version it answered. To avoid showing a
 * live/remix/cover take for the studio track (or the reverse), this provider is skipped when the playing title itself carries a
 * version marker; LRCLIB (which does carry metadata) is the source for those.
 */
class LyricsOvhProvider : LyricsProvider {
    override val name = "lyrics.ovh"
    override val supportsSynced = false

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private class Reply(val code: Int, val body: String)

    private fun get(url: String): Reply {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 6000; conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "MeowIsland/1.1 (Android TFT lyrics display; https://github.com/arib-khan/MEOW-Island)")
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Reply(code, body)
        } finally {
            try { conn?.disconnect() } catch (e: Exception) { }
        }
    }

    override fun fetch(query: LyricsQuery): LyricsResult {
        if (query.title.isBlank()) return LyricsResult(DeviceProtocol.LY_UNAVAILABLE, detail = "no title")
        if (query.artist.isBlank()) return LyricsResult(DeviceProtocol.LY_UNAVAILABLE, detail = "no artist")
        if (LyricsMatcher.hasHardVersion(query.title)) {
            return LyricsResult(DeviceProtocol.LY_UNAVAILABLE, detail = "plain source cannot verify this version")
        }
        val url = "https://api.lyrics.ovh/v1/" + enc(query.artist) + "/" + enc(LyricsMatcher.baseTitle(query.title))
        try {
            val r = get(url)
            if (r.code == 429 || r.code >= 500) return LyricsResult(DeviceProtocol.LY_OFFLINE, detail = "lyrics service busy (HTTP ${r.code})")
            if (r.code != 200) return LyricsResult(DeviceProtocol.LY_UNAVAILABLE, detail = "HTTP ${r.code}")
            val text = try { JSONObject(r.body).optString("lyrics", "") } catch (e: Exception) { "" }
            // Some community entries are transliterations or translations rather than the song's own text. Never present those as
            // the original lyrics: LRCLIB (which carries metadata) is the source for that track instead.
            if (Regex("(?i)\\[\\s*(version\\s+romanis[ée]e|romanized|romanisation|transliteration|translation)\\b[^\\]]*\\]").containsMatchIn(text)) {
                return LyricsResult(DeviceProtocol.LY_UNAVAILABLE, detail = "source returned a transliteration, not the original text")
            }
            val lines = PlainLyrics.split(text)
            if (lines.isEmpty()) return LyricsResult(DeviceProtocol.LY_UNAVAILABLE, detail = "no lyrics text")
            return LyricsResult(DeviceProtocol.LY_UNSYNCED, plain = lines, detail = "plain lyrics (${lines.size} lines)", provider = name)
        } catch (e: IOException) {
            return LyricsResult(DeviceProtocol.LY_OFFLINE, detail = "network: " + e.javaClass.simpleName)
        } catch (e: Exception) {
            return LyricsResult(DeviceProtocol.LY_UNAVAILABLE, detail = "error: " + e.javaClass.simpleName)
        }
    }
}
