package com.smartisland.app

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Base64
import com.facebook.react.bridge.ActivityEventListener
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod

/** JS bridge for the Meow Island TFT device. All the real work is in [DeviceLink]; this only forwards calls. */
class DeviceModule(private val ctx: ReactApplicationContext) : ReactContextBaseJavaModule(ctx) {
    override fun getName() = "DeviceModule"

    private val main = Handler(Looper.getMainLooper())
    private val PICK_REQ = 7711
    private var pickPromise: Promise? = null
    private val listener = object : ActivityEventListener {
        override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
            if (requestCode != PICK_REQ) return
            val p = pickPromise ?: return; pickPromise = null
            val uri = data?.data
            if (resultCode != Activity.RESULT_OK || uri == null) { p.resolve(null); return }
            Thread { readPicked(uri, p) }.start()
        }
        override fun onNewIntent(intent: Intent) { }
    }
    init { ctx.addActivityEventListener(listener) }

    @ReactMethod fun deviceStart() { DeviceLink.ensureStarted(ctx) }
    @ReactMethod fun deviceHasPermission(p: Promise) { p.resolve(DeviceLink.hasPermission(ctx)) }
    @ReactMethod fun deviceGetState(p: Promise) { DeviceLink.ensureStarted(ctx); p.resolve(com.facebook.react.bridge.Arguments.makeNativeMap(DeviceLink.state())) }
    @ReactMethod fun deviceScanStart(p: Promise) { DeviceLink.ensureStarted(ctx); p.resolve(DeviceLink.startScan()) }
    @ReactMethod fun deviceScanStop() { DeviceLink.stopScan() }
    @ReactMethod fun deviceScanResults(p: Promise) { p.resolve(DeviceLink.scanResultsJson()) }
    @ReactMethod fun devicePair(address: String, p: Promise) { DeviceLink.ensureStarted(ctx); p.resolve(DeviceLink.pair(address)) }
    @ReactMethod fun deviceDisconnect() { DeviceLink.disconnect() }
    @ReactMethod fun deviceCancelPair() { main.post { DeviceLink.cancelPair() } }
    @ReactMethod fun deviceRepair(p: Promise) { main.post { p.resolve(DeviceLink.repair()) } }
    @ReactMethod fun deviceReconnect() { DeviceLink.ensureStarted(ctx); DeviceLink.reconnect() }
    /** Resolves true when the device was reachable and told to forget this phone too. */
    @ReactMethod fun deviceUnpair(clearWifi: Boolean, p: Promise) { p.resolve(DeviceLink.unpair(clearWifi)) }
    @ReactMethod fun deviceSetScreen(id: Int, p: Promise) { p.resolve(DeviceLink.setScreen(id)) }
    @ReactMethod fun deviceSyncNow(p: Promise) { DeviceLink.syncAll(true); p.resolve(true) }
    @ReactMethod fun deviceSetWifi(ssid: String, pass: String, p: Promise) {
        if (!DeviceLink.state().let { it["ready"] == true }) { p.reject("NOT_CONNECTED", "The device is not connected right now."); return }
        if (DeviceLink.setWifi(ssid, pass)) p.resolve(true) else p.reject("INVALID_WIFI", "The Wi-Fi name must be 1 to 32 characters and the password empty (open network) or 8 to 63 characters.")
    }
    @ReactMethod fun deviceClearWifi(p: Promise) { p.resolve(DeviceLink.clearWifi()) }
    @ReactMethod fun deviceOpenBluetoothSettings() { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    // ------------------------------------------------------------------ screen time on the display
    @ReactMethod fun deviceScreenTimeGet(p: Promise) { try { p.resolve(DeviceExtras.screenTimeJson(DeviceExtras.readScreenTime(ctx))) } catch (e: Exception) { p.reject("SCREEN_TIME", e.message ?: "failed") } }
    @ReactMethod fun deviceScreenTimeSetGoal(minutes: Int, p: Promise) { val v = DeviceExtras.setGoalMin(ctx, minutes); DeviceLink.screenTimeChanged(); p.resolve(v) }

    // ------------------------------------------------------------------ notifications on the display (opt-in; nothing is stored on disk or sent anywhere but to the paired display)
    @ReactMethod fun deviceNotifsGet(p: Promise) {
        p.resolve(JSONObject().put("enabled", DeviceExtras.notifsEnabled(ctx)).put("access", DeviceExtras.hasNotifAccess(ctx))
            .put("count", DeviceExtras.NotifStore.snapshot(System.currentTimeMillis()).size).put("preview", Cfg.read(ctx).optBoolean("notifPreview", true)).toString())
    }
    @ReactMethod fun deviceNotifsSetEnabled(on: Boolean, p: Promise) { DeviceExtras.setNotifsEnabled(ctx, on); DeviceLink.notifsChanged(); p.resolve(on) }
    /** Clears the list on the display only. Notifications on the phone are not touched. */
    @ReactMethod fun deviceNotifsClear(p: Promise) { DeviceExtras.NotifStore.clear(); DeviceLink.notifsChanged(); p.resolve(true) }

    // ------------------------------------------------------------------ online lyrics source (any music provider except video apps)
    @ReactMethod fun deviceLyricsGet(p: Promise) {
        p.resolve(JSONObject().put("provider", DeviceExtras.lyricsProvider(ctx)).put("state", DeviceExtras.Lyrics.state)
            .put("detail", DeviceExtras.Lyrics.detail).put("source", DeviceExtras.Lyrics.providerName).toString())
    }
    @ReactMethod fun deviceLyricsSetProvider(name: String, p: Promise) { DeviceExtras.setLyricsProvider(ctx, name); DeviceLink.requestSync(); p.resolve(DeviceExtras.lyricsProvider(ctx)) }

    // ------------------------------------------------------------------ custom HTML screen
    /** Opens Android's document picker. Resolves null when cancelled, otherwise a JSON string {name,size,tooLarge,html}. Nothing is uploaded anywhere. */
    @ReactMethod fun customPick(p: Promise) {
        val act = currentActivity
        if (act == null) { p.reject("NO_ACTIVITY", "Open the app on screen first."); return }
        if (pickPromise != null) { p.reject("BUSY", "A file picker is already open."); return }
        pickPromise = p
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/html", "application/xhtml+xml", "text/plain", "application/octet-stream"))
            act.startActivityForResult(i, PICK_REQ)
        } catch (e: Exception) { pickPromise = null; p.reject("PICKER", "Could not open the file picker: " + (e.message ?: "")) }
    }
    private fun readPicked(uri: android.net.Uri, p: Promise) {
        try {
            val cr = ctx.contentResolver
            var name = "design.html"; var size = -1L
            cr.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni) ?: name
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
            val buf = ByteArrayOutputStream(); var total = 0L; var tooLarge = false
            cr.openInputStream(uri)?.use { ins ->
                val b = ByteArray(8192)
                while (true) {
                    val n = ins.read(b); if (n < 0) break
                    total += n
                    if (total > CustomScreen.MAX_HTML) { tooLarge = true; break }       // never read more than the limit into memory
                    buf.write(b, 0, n)
                }
            }
            val html = if (tooLarge) "" else String(buf.toByteArray(), Charsets.UTF_8).removePrefix("\uFEFF")
            p.resolve(JSONObject().put("name", name).put("size", if (size >= 0) size else total).put("tooLarge", tooLarge).put("html", html).toString())
        } catch (e: Exception) { p.reject("READ_FAILED", "Could not read that file: " + (e.message ?: e.javaClass.simpleName)) }
    }

    /** Renders the HTML in an isolated WebView; resolves base64 of 240x320 RGB565 (little-endian). Rejects with a code and a readable message. */
    @ReactMethod fun customRender(html: String, allowScripts: Boolean, p: Promise) {
        if (html.length > CustomScreen.MAX_HTML) { p.reject("TOO_LARGE", "That file is too large."); return }
        CustomRender.render(currentActivity, html, allowScripts) { r ->
            val bytes = r.rgb565
            if (bytes == null) p.reject(r.code, r.message) else p.resolve(Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
    }
    /** Stores the encoded design (and the HTML it came from) in the app's private storage. Replaces the previous one only when the new one was written completely. */
    @ReactMethod fun customSave(imageB64: String, html: String, name: String, p: Promise) {
        try {
            val img = Base64.decode(imageB64, Base64.DEFAULT)
            if (img.size < 19 || img.size > 65536 || img[0] != 'M'.code.toByte() || img[1] != 'I'.code.toByte()) { p.reject("BAD_IMAGE", "That is not a valid display image."); return }
            CustomScreen.save(ctx, img, html, name)
            p.resolve(CustomScreen.info(ctx).toString())
        } catch (e: Exception) { p.reject("SAVE_FAILED", "Could not save the design: " + (e.message ?: e.javaClass.simpleName)) }
    }
    /** JSON: saved-design info + transfer state; with full=true also the encoded image (base64) and the HTML, for showing the preview after a restart. */
    @ReactMethod fun customGetSaved(full: Boolean, p: Promise) {
        try {
            val j = CustomScreen.info(ctx)
            if (full) { CustomScreen.savedImage(ctx)?.let { j.put("image", Base64.encodeToString(it, Base64.NO_WRAP)) }; CustomScreen.savedHtml(ctx)?.let { j.put("html", it) } }
            p.resolve(j.toString())
        } catch (e: Exception) { p.reject("READ_FAILED", e.message ?: "failed") }
    }
    @ReactMethod fun customSend(p: Promise) { main.post { val err = CustomScreen.send(ctx); if (err == null) p.resolve(true) else p.reject("SEND_FAILED", err) } }
    @ReactMethod fun customCancel() { main.post { CustomScreen.cancel() } }
    /** Deletes the design from the phone and the device. The device's normal screens are unaffected. */
    @ReactMethod fun customRemove(p: Promise) { main.post { CustomScreen.remove(ctx); p.resolve(true) } }
    @ReactMethod fun customSetAuto(on: Boolean, p: Promise) { CustomScreen.setAuto(ctx, on); p.resolve(on) }
}
