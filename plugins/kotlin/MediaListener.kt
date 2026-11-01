package com.smartisland.app

import android.annotation.SuppressLint
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * One service, three jobs (all need notification-listener access):
 *  1. active MediaSessions -> provider-independent track info, artwork, playback state, transport controls
 *  2. other apps' notifications -> island preview (privacy-gated by config)
 *  3. this app's own reminder notifications -> island, even while the JS layer is asleep
 *
 * The media session is Android's supported cross-app interface: Spotify, Amazon Music, YouTube Music and other players all expose
 * it, though each app decides which fields it publishes. Everything is normalised into [NowPlaying]; no per-app code is needed.
 */
@SuppressLint("NewApi")
class MediaListener : NotificationListenerService() {
    companion object {
        @Volatile var controller: MediaController? = null
        @Volatile var snapshot: Map<String, Any> = mapOf("active" to false)
        @Volatile var inst: MediaListener? = null
        /** The current track as one provider-independent model (null when nothing is playing). */
        @Volatile var current: NowPlaying? = null
        /** The 96x96 artwork of the current track (null when the app provides none); the island and widget use it. Never persisted. */
        @Volatile var artSmall: Bitmap? = null
        /** Bumps whenever the artwork bitmap changes, so consumers can tell a new cover from a repeated metadata update. */
        @Volatile var artRev: Int = 0
    }

    private var msm: MediaSessionManager? = null
    private var registered = false
    private val seen = HashMap<String, String>()
    private var artFrom: Bitmap? = null
    private var artScaled: Bitmap? = null
    private var artB64From: Bitmap? = null
    private var artB64: String = ""

    private val cb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { pushMedia() }
        override fun onPlaybackStateChanged(state: PlaybackState?) { pushMedia() }
        override fun onSessionDestroyed() { repick() }
    }
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list) }

    private fun cn() = ComponentName(this, MediaListener::class.java)

    private fun repick() {
        try { pick(msm?.getActiveSessions(cn())) } catch (e: SecurityException) { }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        try { DeviceLink.ensureStarted(this) } catch (e: Exception) { }    // this service stays alive in the background: keeps the Meow Island display in sync
        inst = this
        try {
            val m = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            msm = m
            m.addOnActiveSessionsChangedListener(sessionsListener, cn())
            registered = true
            pick(m.getActiveSessions(cn()))
        } catch (e: SecurityException) {
            IslandModule.emit("error", mapOf("where" to "media", "message" to (e.message ?: "no access")))
        }
    }

    override fun onListenerDisconnected() { cleanup(); super.onListenerDisconnected() }
    override fun onDestroy() { cleanup(); super.onDestroy() }

    private fun cleanup() {
        controller?.unregisterCallback(cb); controller = null
        if (registered) { try { msm?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (e: Exception) { } }
        registered = false
        IslandBus.remove("music")
        snapshot = mapOf("active" to false)
        current = null
        artSmall = null
        IslandModule.emit("media", snapshot)
        MeowMusic.refresh(this)
        if (inst === this) inst = null
    }

    /**
     * Picks the session that is actually playing. If several are (some players keep a paused session alive), the one with a title
     * and the longest known track wins; a paused session with metadata is still shown when nothing plays.
     */
    private fun pick(list: List<MediaController>?) {
        controller?.unregisterCallback(cb)
        val titled = list?.filter { c -> !c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank() } ?: emptyList()
        val playing = titled.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        controller = playing.maxByOrNull { it.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L }
            ?: titled.maxByOrNull { it.playbackState?.lastPositionUpdateTime ?: 0L }
            ?: list?.firstOrNull()
        controller?.registerCallback(cb)
        pushMedia()
    }

    fun refresh() { pushMedia() }

    private fun pushMedia() {
        val c = controller
        val cfg = Cfg.read(this)
        if (c == null || !cfg.optBoolean("musicEnabled", true)) { publishNone(); return }
        val m = c.metadata
        val st = c.playbackState
        val title = (m?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: "").trim()
        val artist = (m?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: "").trim()
        val album = (m?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "").trim()
        val trackId = (m?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
            ?: m?.getString(MediaMetadata.METADATA_KEY_MEDIA_URI) ?: "").trim().take(120)
        val state = st?.state ?: PlaybackState.STATE_NONE
        val playing = state == PlaybackState.STATE_PLAYING
        if (title.isEmpty() || state == PlaybackState.STATE_NONE || state == PlaybackState.STATE_STOPPED) { publishNone(); return }

        val bmp: Bitmap? = m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: m?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: bitmapFromUri(m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI))
            ?: bitmapFromUri(m?.getString(MediaMetadata.METADATA_KEY_ART_URI))
            ?: bitmapFromUri(m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI))
        val small: Bitmap? = when {
            bmp == null -> null
            bmp === artFrom && artScaled != null -> artScaled
            else -> try { Bitmap.createScaledBitmap(bmp, 96, 96, true).also { artFrom = bmp; artScaled = it } } catch (e: Exception) { null }
        }
        if (small != null && small !== artSmall) artRev++

        val actions = st?.actions ?: 0L
        val canNext = (actions and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L
        val canPrev = (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L
        var pos = st?.position ?: 0L
        val speed = st?.playbackSpeed ?: 1f
        if (playing && st != null && st.lastPositionUpdateTime > 0L)
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * (if (speed > 0f) speed else 1f)).toLong()
        val dur = (m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(0L)
        if (dur > 0L) pos = pos.coerceIn(0L, dur) else pos = pos.coerceAtLeast(0L)

        val nowPlaying = NowPlaying(
            pkg = c.packageName ?: "", app = appLabel(c.packageName ?: ""), title = title, artist = artist, album = album,
            trackId = trackId, durationMs = dur, positionMs = pos, positionAt = System.currentTimeMillis(),
            playing = playing, canPrev = canPrev, canNext = canNext, hasArt = bmp != null, speed = if (speed > 0f) speed else 1f
        )
        IslandBus.post(Act("music", 50, title, artist, art = small, spinning = playing, playing = playing,
            canPrev = canPrev, canNext = canNext, posMs = pos, durMs = dur, posAt = nowPlaying.positionAt))
        val snap = HashMap<String, Any>()
        snap["active"] = true; snap["title"] = title; snap["artist"] = artist; snap["playing"] = playing
        snap["album"] = album; snap["trackId"] = trackId; snap["app"] = c.packageName ?: ""; snap["appName"] = nowPlaying.app
        snap["source"] = nowPlaying.source; snap["video"] = nowPlaying.isVideo; snap["hasArt"] = bmp != null
        snap["position"] = pos.toDouble()
        snap["duration"] = dur.toDouble()
        snap["posAt"] = nowPlaying.positionAt.toDouble()      // when `position` was true, so the widget can keep counting
        snap["canNext"] = canNext; snap["canPrev"] = canPrev
        snap["art"] = if (small == null) "" else if (small === artB64From) artB64 else Img.b64(small, false).also { artB64From = small; artB64 = it }
        snap["artRev"] = artRev
        snapshot = snap
        current = nowPlaying
        artSmall = small
        IslandModule.emit("media", snap)
        MeowMusic.refresh(this)
        try { DeviceLink.requestSync() } catch (e: Exception) { }   // start lyrics/artwork for the new track immediately, not on the next 1 s tick
    }

    private fun publishNone() {
        IslandBus.remove("music")
        artSmall = null
        current = null
        snapshot = mapOf("active" to false)
        IslandModule.emit("media", snapshot)
        MeowMusic.refresh(this)
        try { DeviceLink.requestSync() } catch (e: Exception) { }
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    /** Some players publish only a local content:// URI instead of a bitmap. Loading it here is a local read, never a web fetch. */
    private fun bitmapFromUri(uri: String?): Bitmap? {
        if (uri.isNullOrBlank()) return null
        return try {
            val u = android.net.Uri.parse(uri)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: Exception) { null }
    }

    private fun appIcon(pkg: String): Bitmap? = try {
        Img.fromDrawable(packageManager.getApplicationIcon(pkg), 64)
    } catch (e: Exception) { null }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val own = sbn.packageName == packageName
        if ((n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0) return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if (!own && ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0 || n.category == Notification.CATEGORY_TRANSPORT)) return
        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
        val sig = title + "\u0001" + text
        if (seen[sbn.key] == sig) return          // updates (progress ticks, re-posts) are not new events
        seen[sbn.key] = sig
        if (seen.size > 200) seen.clear()
        val cfg = Cfg.read(this)
        if (own) {
            val body = if (text.isNotEmpty()) text else title
            IslandBus.post(Act("reminder", 90, "\u23F0 " + body, "Reminder", expireAt = System.currentTimeMillis() + 15000L, expand = true, pulse = System.currentTimeMillis()))
            return
        }
        // the notifications screen on the display has its own switch (off by default) and does not depend on the island preview setting
        DeviceExtras.onNotificationPosted(this, sbn.key, appLabel(sbn.packageName), title, text, n.visibility == Notification.VISIBILITY_SECRET)
        if (!cfg.optBoolean("notifEnabled", true)) return
        val app = appLabel(sbn.packageName)
        val preview = cfg.optBoolean("notifPreview", true)
        val t = if (preview && title.isNotEmpty()) title else app
        val sub = if (preview) (app + (if (text.isNotEmpty()) " \u00B7 $text" else "")) else "New notification"
        IslandBus.post(Act("notif", 60, t, sub, icon = appIcon(sbn.packageName),
            expireAt = System.currentTimeMillis() + cfg.optInt("expandMs", 5000) + 1500L, expand = true, pulse = System.currentTimeMillis()))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) { seen.remove(sbn.key); DeviceExtras.onNotificationRemoved(sbn.key) }
}
