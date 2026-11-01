package com.smartisland.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import java.util.Locale

/**
 * Renders the three lyric lines on the PHONE into a 1-bit, 224x80 mask for the TFT. Android's text stack does the shaping,
 * bidi ordering and font fallback, so Devanagari conjuncts and matras, Arabic/Urdu right-to-left text, Korean, Japanese and every
 * other script the phone supports come out correctly. The display only has ASCII bitmap fonts, which is why the old text path
 * dropped those scripts (and showed question marks); a mask has no font or encoding problem at all.
 *
 * Layout mirrors the old screen: one line above, the current line (up to two wrapped lines) in the middle, one line below. The
 * firmware draws the three strips in the existing colours, so the music screen's design does not change.
 */
object LyricBandRenderer {
    const val W = 224
    const val H = 80
    const val ROW = W / 8
    const val PREV_Y = 0
    const val PREV_H = 18
    const val CUR_Y = 20
    const val CUR_H = 40
    const val NEXT_Y = 64
    const val NEXT_H = 16

    private const val TEXT_SIZE = 14f
    private const val ALPHA_CUTOFF = 80

    /** 1-bit mask, MSB first, one row = 28 bytes. Empty array when all three lines are blank. */
    fun render(prev: String, cur: String, next: String): ByteArray {
        val text = listOf(prev, cur, next)
        if (text.all { it.isBlank() }) return ByteArray(0)
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        try {
            val c = Canvas(bmp)
            c.drawColor(Color.TRANSPARENT)
            drawOne(c, prev, PREV_Y, PREV_H, false)
            drawWrapped(c, cur, CUR_Y, CUR_H, true)
            drawOne(c, next, NEXT_Y, NEXT_H, false)
            return pack(bmp)
        } catch (e: Exception) {
            return ByteArray(0)
        } finally {
            try { bmp.recycle() } catch (e: Exception) { }
        }
    }

    private fun paint(bold: Boolean, text: String): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        textSize = TEXT_SIZE
        color = Color.WHITE
        isSubpixelText = true
        isLinearText = true
        textLocale = localeFor(text)          // correct Han/Kana glyph variants and CJK line breaking
    }

    /** Android's font fallback picks glyph variants by locale; a Japanese line should not use Chinese forms. */
    private fun localeFor(t: String): Locale = when {
        t.any { it in '\u3040'..'\u30FF' } -> Locale.JAPANESE
        t.any { it in '\uAC00'..'\uD7AF' } -> Locale.KOREAN
        t.any { it in '\u0600'..'\u06FF' } -> Locale("ar")
        t.any { it in '\u0900'..'\u097F' } -> Locale("hi", "IN")
        else -> Locale.getDefault()
    }

    private fun drawOne(c: Canvas, raw: String, top: Int, height: Int, bold: Boolean) {
        val t = raw.trim()
        if (t.isEmpty()) return
        val p = paint(bold, t)
        val fitted = try {
            TextUtils.ellipsize(t, p, W.toFloat(), TextUtils.TruncateAt.END).toString()
        } catch (e: Exception) { t }
        val fm = p.fontMetrics
        val textH = fm.descent - fm.ascent
        val baseline = top + (height - textH) / 2f - fm.ascent
        p.textAlign = Paint.Align.CENTER
        c.save()
        c.clipRect(0, top, W, top + height)
        c.drawText(fitted, W / 2f, baseline, p)
        c.restore()
    }

    private fun drawWrapped(c: Canvas, raw: String, top: Int, height: Int, bold: Boolean) {
        val t = raw.trim()
        if (t.isEmpty()) return
        val p = paint(bold, t)
        val layout = try {
            StaticLayout.Builder.obtain(t, 0, t.length, p, W)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setIncludePad(false)
                .setLineSpacing(0f, 1f)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .build()
        } catch (e: Exception) {
            drawOne(c, t, top, height, bold); return
        }
        val y = top + (height - layout.height) / 2
        c.save()
        c.clipRect(0, top, W, top + height)
        c.translate(0f, y.toFloat())
        layout.draw(c)
        c.restore()
    }

    private fun pack(bmp: Bitmap): ByteArray {
        val px = IntArray(W * H)
        bmp.getPixels(px, 0, W, 0, 0, W, H)
        val out = ByteArray(ROW * H)
        for (y in 0 until H) {
            val row = y * ROW
            for (x in 0 until W) {
                if (((px[y * W + x] ushr 24) and 0xFF) >= ALPHA_CUTOFF) {
                    out[row + (x ushr 3)] = (out[row + (x ushr 3)].toInt() or (0x80 ushr (x and 7))).toByte()
                }
            }
        }
        return out
    }
}
