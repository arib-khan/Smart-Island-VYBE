package com.smartisland.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Phone-side album-art cache: a small in-memory LRU plus a bounded on-disk store of ready-to-send 80x80 RGB565 images.
 *
 * The cache key is the stable identity of the track (package + track id when the player exposes one + normalised title, artist and
 * album), so replaying a song shows its cover without decoding or scaling anything again and without asking the player for the
 * bitmap. Disk writes and bitmap scaling happen on one background thread; the BLE link only ever receives the finished 12 800
 * bytes. The ESP32's own art buffer is RAM-only, so the phone decides whether a transfer is still needed (it compares the CRC in
 * the device status with the cached bytes).
 */
object ArtCache {
    const val ART = 80
    const val BYTES = ART * ART * 2
    private const val DIR = "artcache"
    private const val MAX_FILES = 120
    private const val MAX_BYTES = 6L * 1024 * 1024

    private val mem = object : LinkedHashMap<String, ByteArray>(12, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 16
    }
    private val pending = HashMap<String, MutableList<(ByteArray?) -> Unit>>()
    private val exec = Executors.newSingleThreadExecutor()

    @Volatile var hits = 0; private set
    @Volatile var misses = 0; private set

    fun memory(key: String): ByteArray? = synchronized(this) { mem[key] }

    /** Removes the cached copy (used when the user clears app storage through Android settings only). */
    fun clear(ctx: Context) {
        synchronized(this) { mem.clear() }
        try { dir(ctx).listFiles()?.forEach { it.delete() } } catch (e: Exception) { }
    }

    /**
     * Returns the cover for [key] as fast as possible: memory first, then disk, and only when neither has it and a [source] bitmap
     * is available, it is scaled and stored. [done] always runs (on a background thread) exactly once per accepted request.
     */
    fun request(ctx: Context, key: String, source: Bitmap?, done: (ByteArray?) -> Unit) {
        if (key.isEmpty()) { done(null); return }
        synchronized(this) {
            val hit = mem[key]
            if (hit != null) { hits++; done(hit); return }
            val waiting = pending[key]
            if (waiting != null) { waiting.add(done); return }
            pending[key] = mutableListOf(done)
        }
        val app = ctx.applicationContext
        exec.execute {
            var out: ByteArray? = null
            try {
                val d = dir(app)
                val f = file(d, key)
                if (f.isFile && f.length() == BYTES.toLong()) out = f.readBytes()
                if (out == null && source != null && !source.isRecycled) {
                    val encoded = encode(source)
                    if (encoded != null) {
                        out = encoded
                        synchronized(this) { mem[key] = encoded }
                        write(d, key, encoded)
                    }
                }
            } catch (e: Exception) { out = null }
            val result = out?.takeIf { it.size == BYTES }
            if (result == null) misses++
            val callbacks = synchronized(this) {
                pending.remove(key) ?: emptyList()
            }
            if (result != null) synchronized(this) { mem[key] = result }
            for (cb in callbacks) {
                try { cb(result) } catch (e: Exception) { }
            }
        }
    }

    /** Stores already-encoded bytes (used after a successful transfer / by tests). */
    fun put(ctx: Context, key: String, bytes: ByteArray) {
        if (key.isEmpty() || bytes.size != BYTES) return
        synchronized(this) { mem[key] = bytes }
        val app = ctx.applicationContext
        exec.execute { try { write(dir(app), key, bytes) } catch (e: Exception) { } }
    }

    /** 80x80 RGB565 little-endian, transparent pixels composited on the display's black background. */
    fun encode(source: Bitmap): ByteArray? {
        if (source.isRecycled) return null
        var scaled: Bitmap? = null
        return try {
            scaled = if (source.width == ART && source.height == ART) source else Bitmap.createScaledBitmap(source, ART, ART, true)
            val px = IntArray(ART * ART)
            scaled.getPixels(px, 0, ART, 0, 0, ART, ART)
            val out = ByteArray(BYTES)
            for (i in px.indices) {
                val c = px[i]
                val a = Color.alpha(c)
                var r = Color.red(c); var g = Color.green(c); var b = Color.blue(c)
                if (a < 255) { r = r * a / 255; g = g * a / 255; b = b * a / 255 }
                val v = ((r shr 3) shl 11) or ((g shr 2) shl 5) or (b shr 3)
                out[2 * i] = v.toByte(); out[2 * i + 1] = (v shr 8).toByte()
            }
            out
        } catch (e: Exception) {
            null
        } finally {
            if (scaled != null && scaled !== source) try { scaled.recycle() } catch (e: Exception) { }
        }
    }

    private fun dir(ctx: Context): File = File(ctx.filesDir, DIR).also { if (!it.isDirectory) it.mkdirs() }

    private fun file(dir: File, key: String): File = File(dir, sha256(key) + ".rgb")

    private fun write(dir: File, key: String, bytes: ByteArray) {
        try {
            dir.mkdirs()
            val target = file(dir, key)
            val tmp = File(dir, target.name + ".tmp")
            FileOutputStream(tmp).use { it.write(bytes); it.flush() }
            if (!tmp.renameTo(target)) { target.delete(); if (!tmp.renameTo(target)) tmp.delete() }
            prune(dir, target.name)
        } catch (e: Exception) { }
    }

    private fun prune(dir: File, keep: String) {
        try {
            val files = dir.listFiles()?.filter { it.isFile } ?: return
            if (files.size <= MAX_FILES && files.sumOf { it.length() } <= MAX_BYTES) return
            var count = files.size
            var total = files.sumOf { it.length() }
            for (f in files.sortedBy { it.lastModified() }) {
                if (count <= MAX_FILES && total <= MAX_BYTES) break
                if (f.name == keep) continue
                val n = f.length(); if (f.delete()) { count--; total -= n }
            }
        } catch (e: Exception) { }
    }

    private fun sha256(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(32)
        for (i in 0 until 16) { val b = d[i].toInt() and 0xFF; sb.append(HEX[b ushr 4]).append(HEX[b and 15]) }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
