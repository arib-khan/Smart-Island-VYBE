package com.smartisland.app

import java.text.Normalizer
import java.util.Locale

/**
 * Provider-independent snapshot of what a media app is playing. Nothing here imports android.*: it is unit-tested on a desktop
 * (tests/kotlin/run_device.sh) and every consumer (island, Dancing Cat widget, ESP32 link) works with this one model instead of
 * having a code path per music app.
 *
 * Android's MediaSession is the only supported source. A player decides how much it exposes: title is almost always there, artist,
 * album, track id, duration, position, artwork and transport actions depend on the app. Missing fields stay empty/0 and are never
 * invented.
 */
class NowPlaying(
    val pkg: String = "",
    val app: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val trackId: String = "",
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val positionAt: Long = 0L,
    val playing: Boolean = false,
    val canPrev: Boolean = false,
    val canNext: Boolean = false,
    val hasArt: Boolean = false,
    val speed: Float = 1f
) {
    val active: Boolean get() = title.isNotBlank()
    val source: String get() = Provider.sourceLabel(pkg, app)
    val isVideo: Boolean get() = Provider.isVideo(pkg)

    /** Lyrics are looked up for every music provider except apps that play video (YouTube, streaming video). */
    val lyricsEligible: Boolean get() = active && !isVideo

    /** Stable identity of the cover for the phone-side cache; empty when the player exposes no artwork. */
    val artKey: String get() = if (active && hasArt) key() else ""

    /** Package + track id (when exposed) + normalised title/artist/album. Stable across replays and app restarts. */
    fun key(): String = Provider.cacheKey(pkg, trackId, title, artist, album)

    /** Playback position extrapolated to [nowMs], clamped to the track length. */
    fun positionNow(nowMs: Long): Long {
        var p = positionMs
        if (playing && positionAt > 0L && nowMs > positionAt) {
            val s = if (speed.isFinite() && speed > 0f) speed else 1f
            p += ((nowMs - positionAt) * s).toLong()
        }
        p = p.coerceAtLeast(0L)
        return if (durationMs > 0L) p.coerceAtMost(durationMs) else p
    }
}

/** The music apps Meow Island knows by name. Unknown apps still work: their own label is shown and their metadata is used as-is. */
object Provider {
    enum class Kind { SPOTIFY, YOUTUBE, YOUTUBE_MUSIC, AMAZON, APPLE, TIDAL, DEEZER, SOUNDCLOUD, JIOSAAVN, GAANA, WYNK, VLC, POWERAMP, MUSICOLET, PLEX, BANDCAMP, OTHER }

    /** Apps that play video, not music: no lyrics lookup for them (a video's title is not a song title). */
    private val VIDEO = setOf(
        "com.google.android.youtube", "com.google.android.youtube.tv", "com.google.android.apps.youtube.kids",
        "com.google.android.apps.youtube.music", "com.google.android.apps.youtube.music.tv",
        "com.netflix.mediaclient", "com.amazon.avod.thirdpartyclient", "com.google.android.videos",
        "com.disney.disneyplus", "com.hulu.plus", "com.hbo.hbonow", "tv.twitch.android.app", "com.crunchyroll.crunchyroid"
    )

    fun kind(pkg: String): Kind = when {
        pkg == "com.spotify.music" || pkg == "com.spotify.lite" -> Kind.SPOTIFY
        pkg == "com.google.android.youtube" || pkg == "com.google.android.youtube.tv" -> Kind.YOUTUBE
        pkg == "com.google.android.apps.youtube.music" || pkg == "com.google.android.apps.youtube.music.tv" -> Kind.YOUTUBE_MUSIC
        pkg == "com.amazon.mp3" -> Kind.AMAZON
        pkg == "com.apple.android.music" -> Kind.APPLE
        pkg == "com.aspiro.tidal" -> Kind.TIDAL
        pkg == "deezer.android.app" -> Kind.DEEZER
        pkg == "com.soundcloud.android" -> Kind.SOUNDCLOUD
        pkg == "com.jio.media.jiobeats" || pkg == "com.jio.media.jioplay" -> Kind.JIOSAAVN
        pkg == "com.gaana" -> Kind.GAANA
        pkg == "com.bsbportal.music" -> Kind.WYNK
        pkg == "org.videolan.vlc" -> Kind.VLC
        pkg == "com.maxmpz.audioplayer" -> Kind.POWERAMP
        pkg == "in.krosbits.musicolet" -> Kind.MUSICOLET
        pkg.startsWith("com.plexapp.android") -> Kind.PLEX
        pkg == "com.bandcamp.android" -> Kind.BANDCAMP
        else -> Kind.OTHER
    }

    fun isVideo(pkg: String): Boolean = pkg in VIDEO

    /** Short ASCII label for the 240x320 display's source pill (at most 14 characters). */
    fun sourceLabel(pkg: String, app: String = ""): String = when (kind(pkg)) {
        Kind.SPOTIFY -> "SPOTIFY"
        Kind.YOUTUBE -> "YOUTUBE"
        Kind.YOUTUBE_MUSIC -> "YT MUSIC"
        Kind.AMAZON -> "AMAZON MUSIC"
        Kind.APPLE -> "APPLE MUSIC"
        Kind.TIDAL -> "TIDAL"
        Kind.DEEZER -> "DEEZER"
        Kind.SOUNDCLOUD -> "SOUNDCLOUD"
        Kind.JIOSAAVN -> "JIOSAAVN"
        Kind.GAANA -> "GAANA"
        Kind.WYNK -> "WYNK"
        Kind.VLC -> "VLC"
        Kind.POWERAMP -> "POWERAMP"
        Kind.MUSICOLET -> "MUSICOLET"
        Kind.PLEX -> "PLEX"
        Kind.BANDCAMP -> "BANDCAMP"
        Kind.OTHER -> DeviceProtocol.ascii(app, 14).uppercase(Locale.ROOT).ifEmpty {
            val tail = pkg.substringAfterLast('.').replaceFirstChar { it.uppercase(Locale.ROOT) }
            DeviceProtocol.ascii(tail, 14).uppercase(Locale.ROOT).ifEmpty { "MUSIC" }
        }
    }

    /**
     * Cache key for artwork and lyrics. NFC + case folding keeps the same Unicode text stable; punctuation and spacing are
     * normalised so "Song (feat. X)" and "Song  (feat. X)" share a cover. trackId is included when the player exposes one.
     */
    fun cacheKey(pkg: String, trackId: String, title: String, artist: String, album: String): String {
        fun fold(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC).lowercase(Locale.ROOT)
            .replace('\u2018', '\'').replace('\u2019', '\'').replace('\u201C', '"').replace('\u201D', '"')
            .replace(Regex("\\s+"), " ").trim()
        return fold(pkg) + "|" + fold(trackId) + "|" + fold(title) + "|" + fold(artist) + "|" + fold(album)
    }
}
