package com.smartisland.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Data sources for the new device screens: phone screen time, the notification queue, lyrics state and album-art bytes.
 * Everything here reuses what the app already has (UsageUtil, MediaListener, Cfg, Provider). Settings live in their own preference
 * file ("meowextras") because "Forget device" wipes the device preferences, and these are the user's own choices.
 */
object DeviceExtras {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("meowextras", Context.MODE_PRIVATE)

    // ================================================================== screen time (phone usage today; NOT the device's display-on time)
    const val DEFAULT_GOAL_MIN = 240

    class ScreenTime(val access: Boolean, val ok: Boolean, val totalMin: Int, val goalMin: Int, val apps: List<DeviceProtocol.AppUse>, val day: String, val error: String)

    fun normalizeGoal(m: Int): Int = if (m <= 0) 0 else (Math.round(m / 15.0) * 15).toInt().coerceIn(15, 960)
    fun goalMin(ctx: Context): Int = prefs(ctx).getInt("stGoalMin", DEFAULT_GOAL_MIN)
    fun setGoalMin(ctx: Context, min: Int): Int { val v = normalizeGoal(min); prefs(ctx).edit().putInt("stGoalMin", v).apply(); return v }
    fun dayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())          // the PHONE's local date: the summary resets when it changes

    // The home screen / system UI are "foreground" for Android but are not screen time in Digital Wellbeing either.
    private val IGNORED = setOf("com.android.systemui", "com.google.android.apps.nexuslauncher", "com.sec.android.app.launcher", "com.miui.home", "com.android.launcher",
        "com.android.launcher3", "com.oppo.launcher", "com.huawei.android.launcher", "com.nothing.launcher", "com.mi.android.globallauncher", "com.transsion.hilauncher")
    private fun homePackage(ctx: Context): String? = try {
        ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    } catch (e: Exception) { null }

    private val labels = ConcurrentHashMap<String, String>()
    private fun lastSegment(pkg: String) = pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    fun appName(ctx: Context, pkg: String): String = labels.getOrPut(pkg) {
        val l = try { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { "" }
        if (DeviceProtocol.ascii(l, 14).isNotEmpty()) l else lastSegment(pkg)       // the display only has ASCII: fall back to the package name when the label has none
    }

    /** Reads today's usage through the existing [UsageUtil]. Never throws: denied permission, no data and errors are explicit states. */
    fun readScreenTime(ctx: Context): ScreenTime {
        val goal = goalMin(ctx); val day = dayKey()
        if (!UsageUtil.hasAccess(ctx)) return ScreenTime(false, false, 0, goal, emptyList(), day, "")
        return try {
            val home = homePackage(ctx)
            var totalMs = 0L
            val apps = ArrayList<DeviceProtocol.AppUse>()
            for ((pkg, ms) in UsageUtil.todayAll(ctx)) {
                if (pkg in IGNORED || pkg == home || pkg == "android" || ms <= 0L) continue
                totalMs += ms
                if (ms >= 60_000L) apps.add(DeviceProtocol.AppUse(appName(ctx, pkg), (ms / 60_000L).toInt()))
            }
            apps.sortByDescending { it.min }
            ScreenTime(true, true, (totalMs / 60_000L).toInt().coerceIn(0, 1440), goal, apps.take(10), day, "")
        } catch (e: Exception) {
            ScreenTime(true, false, 0, goal, emptyList(), day, "Could not read usage data from Android.")
        }
    }
    fun screenTimeFrame(s: ScreenTime): ByteArray = DeviceProtocol.screenTime(s.ok, !s.access, s.access && !s.ok, s.totalMin, s.goalMin, s.apps)
    fun screenTimeKey(s: ScreenTime) = "${s.access}|${s.ok}|${s.totalMin}|${s.goalMin}|${s.day}|" + s.apps.take(DeviceProtocol.ST_MAX_APPS).joinToString(",") { it.name + ":" + it.min }
    fun screenTimeJson(s: ScreenTime): String {
        val a = JSONArray(); for (x in s.apps) a.put(JSONObject().put("name", x.name).put("min", x.min))
        return JSONObject().put("access", s.access).put("ok", s.ok).put("totalMin", s.totalMin).put("goalMin", s.goalMin).put("apps", a).put("day", s.day).put("error", s.error).toString()
    }

    // ================================================================== notifications
    class NotifItem(val key: String, val postedAt: Long, val app: String, val title: String, val text: String)

    /**
     * Bounded, in-memory only (never written to disk, never sent anywhere but to the paired display over Bluetooth): the newest [MAX] entries,
     * each dropped after [TTL_MS] or when the phone's own notification is dismissed. The same app+title+text within [DEDUP_MS] is not added twice.
     * Nothing here can dismiss or alter a notification on the phone.
     */
    object NotifStore {
        const val MAX = 20; const val TTL_MS = 6L * 3600_000L; const val DEDUP_MS = 60_000L
        private val items = ArrayList<NotifItem>()     // newest first

        @Synchronized fun add(n: NotifItem): Boolean {
            items.removeAll { it.key == n.key }                                                                 // an update of the same notification replaces it
            if (items.any { it.app == n.app && it.title == n.title && it.text == n.text && n.postedAt - it.postedAt < DEDUP_MS }) return false
            items.add(0, n)
            while (items.size > MAX) items.removeAt(items.size - 1)
            return true
        }
        @Synchronized fun remove(key: String): Boolean = items.removeAll { it.key == key }
        @Synchronized fun clear() { items.clear() }
        @Synchronized fun snapshot(now: Long): List<NotifItem> { items.removeAll { now - it.postedAt > TTL_MS }; return ArrayList(items) }
    }

    fun notifsEnabled(ctx: Context) = prefs(ctx).getBoolean("notifsOn", false)           // opt-in
    fun setNotifsEnabled(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("notifsOn", on).apply(); if (!on) NotifStore.clear() }
    fun hasNotifAccess(ctx: Context) = try { NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName) } catch (e: Exception) { false }

    /** Called by the notification listener for every notification that already passed its filters (not ongoing, not a group summary, not our own). */
    fun onNotificationPosted(ctx: Context, key: String, app: String, title: String, text: String, secret: Boolean) {
        if (!notifsEnabled(ctx) || secret) return                     // notifications marked private by the app are never shown on the display
        val showText = Cfg.read(ctx).optBoolean("notifPreview", true)  // the existing "show previews" privacy switch also governs the display
        val added = NotifStore.add(NotifItem(key, System.currentTimeMillis(), app, if (showText) title else "", if (showText) text else ""))
        if (added) DeviceLink.notifsChanged()
    }
    fun onNotificationRemoved(key: String) { if (NotifStore.remove(key)) DeviceLink.notifsChanged() }

    fun notifFrame(ctx: Context, now: Long): ByteArray {
        val on = notifsEnabled(ctx)
        val list = if (on) NotifStore.snapshot(now).map { DeviceProtocol.Notif(((now - it.postedAt) / 60_000L), it.app, it.title, it.text) } else emptyList()
        return DeviceProtocol.notifs(on, on && !hasNotifAccess(ctx), list)
    }
    /** Identity of what the display should show, WITHOUT the ages (they advance on the device by themselves): unchanged content is not sent again. */
    fun notifKey(ctx: Context, now: Long): String {
        val on = notifsEnabled(ctx)
        return "$on|${on && !hasNotifAccess(ctx)}|" + (if (on) NotifStore.snapshot(now).take(DeviceProtocol.NT_MAX).joinToString("~") { it.key + "\u0001" + it.title + "\u0001" + it.text } else "")
    }

    // ================================================================== media source + album art
    const val ART = ArtCache.ART
    fun isSpotify(pkg: String) = Provider.kind(pkg) == Provider.Kind.SPOTIFY
    /** ASCII source pill name for any provider (SPOTIFY / AMAZON MUSIC / YOUTUBE / the app's own label). */
    fun sourceLabel(ctx: Context, pkg: String): String = Provider.sourceLabel(pkg, if (pkg.isEmpty()) "" else appName(ctx, pkg))

    /** 80x80 RGB565 little-endian (12 800 bytes), the format the display expects. Encoding runs on ArtCache's background thread. */
    fun artBytes(src: Bitmap): ByteArray = ArtCache.encode(src) ?: ByteArray(0)

    // ================================================================== lyrics
    fun lyricsProvider(ctx: Context): String {
        val v = prefs(ctx).getString("lyricsProvider", "off") ?: "off"
        return if (v == "online" || v == "lrclib" || v == "on") "online" else "off"
    }
    fun setLyricsProvider(ctx: Context, name: String) {
        val v = if (name == "online" || name == "lrclib" || name == "on") "online" else "off"
        prefs(ctx).edit().putString("lyricsProvider", v).apply()
        Lyrics.reset()
    }

    /**
     * Lyrics for whatever music app is playing (YouTube and video apps are excluded by [NowPlaying.lyricsEligible]). Opt-in: with
     * the switch off nothing leaves the phone. State machine per track:
     *   provider off -> PROVIDER_OFF; fetching -> LOADING; timestamped lines -> AVAILABLE; plain text only -> UNSYNCED
     *   (shown without pretending it is in time); no source found -> UNAVAILABLE; network failure -> OFFLINE.
     */
    object Lyrics {
        // One background lookup at a time. Only the newest song waits in the queue, so skipping tracks quickly cannot build a backlog
        // of slow lookups; the running request is left to finish and its result is discarded by the key check below.
        private val exec = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue<Runnable>(1), ThreadPoolExecutor.DiscardOldestPolicy())
        private val service = LyricsService(listOf(LrcLibProvider(), LyricsOvhProvider()))
        @Volatile private var key = ""
        @Volatile var state = DeviceProtocol.LY_NA; private set
        @Volatile var detail = ""; private set                 // what the last lookup answered (shown in the app to diagnose problems)
        @Volatile var providerName = ""; private set
        @Volatile private var syncedLines: List<Lrc.Line> = emptyList()
        @Volatile private var plainLines: List<String> = emptyList()
        @Volatile private var offlineAt = 0L
        private val cache = object : LinkedHashMap<String, LyricsResult>(16, 0.75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<String, LyricsResult>?) = size > 32 }

        fun reset() { key = ""; state = DeviceProtocol.LY_NA; detail = ""; providerName = ""; syncedLines = emptyList(); plainLines = emptyList() }
        fun hasBand(): Boolean = state == DeviceProtocol.LY_AVAILABLE || state == DeviceProtocol.LY_UNSYNCED

        fun update(ctx: Context, eligible: Boolean, title: String, artist: String, album: String, durationSec: Int) {
            if (!eligible || title.isBlank()) { reset(); return }
            if (lyricsProvider(ctx) != "online") {
                key = ""; state = DeviceProtocol.LY_PROVIDER_OFF; detail = ""; providerName = ""; syncedLines = emptyList(); plainLines = emptyList(); return
            }
            val q = LyricsQuery(title, artist, album, durationSec)
            val k = Provider.cacheKey("lyrics", "", title, artist, album) + "|" + durationSec
            val retryOffline = state == DeviceProtocol.LY_OFFLINE && System.currentTimeMillis() - offlineAt > 60_000L
            if (k == key && !retryOffline) return
            key = k; syncedLines = emptyList(); plainLines = emptyList(); providerName = ""
            val hit = synchronized(cache) { cache[k] }
            if (hit != null) { apply(hit); return }
            state = DeviceProtocol.LY_LOADING; detail = "looking up…"
            exec.execute {
                val r = try { service.fetch(q) } catch (e: Exception) { LyricsResult(DeviceProtocol.LY_OFFLINE, detail = "error: " + e.javaClass.simpleName) }
                if (key == k) {
                    apply(r)
                    if (r.state == DeviceProtocol.LY_OFFLINE) offlineAt = System.currentTimeMillis() else synchronized(cache) { cache[k] = r }
                    DeviceLink.requestSync()
                }
            }
        }

        private fun apply(r: LyricsResult) {
            // lines first, state last: a reader that sees AVAILABLE also sees the lines (volatile write ordering)
            syncedLines = r.synced; plainLines = r.plain; detail = r.detail; providerName = r.provider; state = r.state
        }

        class Current(val idx: Int, val prev: String, val cur: String, val next: String)

        /** Only meaningful when [state] == AVAILABLE. Before the first timestamp the current line is empty and the first line is "next". */
        fun current(posMs: Long): Current {
            val l = syncedLines; val i = Lrc.indexAt(l, posMs)
            return if (i < 0) Current(-1, "", "", l.firstOrNull { it.text.isNotBlank() }?.text ?: "")
            else Current(i, Lrc.neighbour(l, i, -1), l[i].text, Lrc.neighbour(l, i, 1))
        }

        /**
         * A moving three-line window over plain lyrics. The lines have no timestamps, so this is reading pagination (one line every
         * six seconds of playback), never presented as synchronised; the display keeps the UNSYNCED state.
         */
        fun plainCurrent(posMs: Long): Current {
            val w = PlainPager.at(plainLines, posMs)
            return Current(w.idx, w.prev, w.cur, w.next)
        }
    }
}
