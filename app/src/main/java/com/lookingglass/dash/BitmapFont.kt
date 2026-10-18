package com.lookingglass.dash

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import org.json.JSONObject

/**
 * Bitmap-font renderer for the game's own Asrafel font.
 *
 * Loads `hud/font/atlas.png` (white glyphs on transparent — an alpha mask)
 * and `hud/font/font.json` (height, char→glyph indirections, glyph rects).
 *
 * The atlas is white, so each requested colour is baked into a cached tinted
 * copy of the atlas (one saveLayer per colour, done once). After that,
 * [drawText] just blits glyph rectangles — no per-frame layer allocation.
 *
 * Glyphs are top-aligned (matching the game's `ui.rs` `draw()`): a glyph's
 * source rect maps to (cursorX, y, w*scale, h*scale), advance = w*scale, no
 * kerning.
 */
class BitmapFont(context: Context) {

    val height: Float
    private val indirections: IntArray
    private val glyphs: Array<FloatArray>   // [x, y, w, h] per glyph
    private val baseAtlas: Bitmap
    private val tintCache = HashMap<Int, Bitmap>()

    init {
        val json = HudAssets.open(context, "hud/font/font.json").bufferedReader().use { it.readText() }
        val o = JSONObject(json)
        height = o.optDouble("height", 30.0).toFloat()

        val ind = o.optJSONArray("indirections")
        indirections = IntArray(256)
        for (i in 0 until 256) indirections[i] = ind?.optInt(i, -1) ?: -1

        val gl = o.optJSONArray("glyphs")
        glyphs = Array(256) { i ->
            val g = gl?.optJSONArray(i)
            floatArrayOf(
                g?.optDouble(0, 0.0)?.toFloat() ?: 0f,
                g?.optDouble(1, 0.0)?.toFloat() ?: 0f,
                g?.optDouble(2, 0.0)?.toFloat() ?: 0f,
                g?.optDouble(3, 0.0)?.toFloat() ?: 0f,
            )
        }

        baseAtlas = BitmapFactory.decodeStream(HudAssets.open(context, "hud/font/atlas.png"))
    }

    /** Measure the advance width of [text] at [size]. */
    fun measureText(text: String, size: Float): Float {
        val scale = size / height
        var w = 0f
        for (c in text) {
            val idx = if (c.code in 0..255) indirections[c.code] else -1
            w += if (idx >= 0) glyphs[idx][2] * scale else size * 0.35f
        }
        return w
    }

    /**
     * Draw [text] top-aligned at (x, y) at [size], tinted [color].
     * Returns the advance width consumed.
     */
    fun drawText(canvas: Canvas, text: String, x: Float, y: Float, size: Float, color: Int): Float {
        if (text.isEmpty()) return 0f
        val atlas = tinted(color)
        val scale = size / height
        var cursorX = x
        for (c in text) {
            val idx = if (c.code in 0..255) indirections[c.code] else -1
            if (idx < 0) { cursorX += size * 0.35f; continue }
            val g = glyphs[idx]
            val src = Rect(g[0].toInt(), g[1].toInt(), (g[0] + g[2]).toInt(), (g[1] + g[3]).toInt())
            val dst = RectF(cursorX, y, cursorX + g[2] * scale, y + g[3] * scale)
            canvas.drawBitmap(atlas, src, dst, null)
            cursorX += g[2] * scale
        }
        return cursorX - x
    }

    private fun tinted(color: Int): Bitmap {
        tintCache[color]?.let { return it }
        val bmp = Bitmap.createBitmap(baseAtlas.width, baseAtlas.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawBitmap(baseAtlas, 0f, 0f, null)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = color
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_ATOP)
        c.drawRect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), p)
        tintCache[color] = bmp
        return bmp
    }
}
