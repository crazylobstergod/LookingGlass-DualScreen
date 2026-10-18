package com.lookingglass.dash

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.View
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The dash-display HUD for LookingGlass Alice.
 *
 * A single self-contained [View] that draws the whole companion panel with a
 * [Canvas], using the game's own art: the Asrafel bitmap font, the 10 weapon
 * icons, the 4 power-up icons, the sanity/will meter textures, and a
 * pre-computed top-down minimap of the current level with a live player dot
 * and heading arrow.
 *
 * Refresh model (proven on the Thor dash, where Choreographer/vsync never
 * fire): a [Handler.postDelayed] tick invalidates the view every 100 ms; each
 * [onDraw] reads the latest [StateFeed.latest]. The feed thread only writes
 * that volatile reference, so no locking is required.
 */
class HudView(context: Context, private val feed: StateFeed) : View(context) {

    // ---- palette (the game's own: INK / PAPER_TEXT / SELECTED) ----
    private val bgTop = Color.parseColor("#1A0505")
    private val bgBottom = Color.parseColor("#330B0B")
    private val panel = Color.parseColor("#2A0E0E")
    private val panelStroke = Color.parseColor("#5A2018")
    private val text = Color.parseColor("#F0E3C8")   // PAPER_TEXT
    private val muted = Color.parseColor("#A58868")
    private val gold = Color.parseColor("#D4A94E")
    private val accent = Color.parseColor("#821C12") // SELECTED
    private val sanity = Color.parseColor("#E0526B")
    private val will = Color.parseColor("#5B8FD4")
    private val ok = Color.parseColor("#6BBF59")
    private val bad = Color.parseColor("#EF5350")
    private val cellOn = Color.parseColor("#3A1A14")
    private val cellOff = Color.parseColor("#241010")

    // ---- fonts & assets ----
    private val font = BitmapFont(context)
    private val weaponIcons: Array<Bitmap?> = WEAPON_ICON_NAMES.map { load(it) }.toTypedArray()
    private val powerIcons: Array<Bitmap?> = POWER_ICON_NAMES.map { load(it) }.toTypedArray()
    private val sanityFill = load("hud/meters/skin_health02.png")
    private val willFill = load("hud/meters/skin_mana01.png")

    // minimap metadata: map -> [w, h, minx, miny, maxx, maxy]
    private val mapBounds = HashMap<String, FloatArray>()
    private val minimapCache = HashMap<String, Bitmap>()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val barFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dimIcon = Paint(iconPaint).apply { alpha = 90 }

    private val weaponHitRects = Array(10) { RectF() }
    private var touchSlot = -1
    private var mapZoom = 3f
    private var draggingZoom = false
    private val zoomHitRect = RectF()
    private var zoomTrackLeft = 0f
    private var zoomTrackRight = 1f
    private fun updateZoom(x: Float) {
        val fraction = ((x - zoomTrackLeft) / (zoomTrackRight - zoomTrackLeft)).coerceIn(0f, 1f)
        mapZoom = 1f + fraction * 5f
        invalidate()
    }
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        val scale = min(width / 620f, height / 540f)
        if (scale <= 0f) return false
        val x = (event.x - (width - 620f * scale) / 2f) / scale
        val y = (event.y - (height - 540f * scale) / 2f) / scale
        // Capture the slider gesture before weapon hit testing, including drags
        // beyond the track. A slider release can never equip a weapon.
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN && zoomHitRect.contains(x, y)) {
            draggingZoom = true
            touchSlot = -1
            parent?.requestDisallowInterceptTouchEvent(true)
            updateZoom(x)
            return true
        }
        if (draggingZoom) {
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_MOVE -> updateZoom(x)
                android.view.MotionEvent.ACTION_UP -> {
                    updateZoom(x)
                    android.util.Log.d("AliceDash", "Map zoom: $mapZoom")
                    draggingZoom = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    performClick()
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    android.util.Log.d("AliceDash", "Map zoom: $mapZoom")
                    draggingZoom = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            return true
        }
        val slot = weaponHitRects.indexOfFirst { it.contains(x, y) }
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                touchSlot = slot
                return slot >= 0
            }
            android.view.MotionEvent.ACTION_UP -> {
                if (slot >= 0 && slot == touchSlot && feed.equip(slot)) {
                    performClick()
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                }
                touchSlot = -1
                return true
            }
            android.view.MotionEvent.ACTION_MOVE -> { if (slot != touchSlot) touchSlot = -1 }
            android.view.MotionEvent.ACTION_CANCEL -> touchSlot = -1
        }
        return touchSlot >= 0
    }
    override fun performClick(): Boolean { super.performClick(); return true }

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        try {
            val json = HudAssets.open(context, "hud/minimaps.json").bufferedReader().use { it.readText() }
            val o = JSONObject(json)
            val names = o.keys()
            while (names.hasNext()) {
                val n = names.next()
                val b = o.optJSONObject(n) ?: continue
                mapBounds[n] = floatArrayOf(
                    b.optDouble("w", 0.0).toFloat(),
                    b.optDouble("h", 0.0).toFloat(),
                    b.optDouble("minx", 0.0).toFloat(),
                    b.optDouble("miny", 0.0).toFloat(),
                    b.optDouble("maxx", 0.0).toFloat(),
                    b.optDouble("maxy", 0.0).toFloat(),
                )
            }
        } catch (_: Exception) { /* no minimaps.json — minimap panel degrades gracefully */ }
    }

    private fun load(path: String): Bitmap? = try {
        BitmapFactory.decodeStream(HudAssets.open(context, path))
    } catch (_: Exception) { null }

    private fun minimapFor(name: String): Bitmap? {
        if (name.isEmpty()) return null
        minimapCache[name]?.let { return it }
        val bmp = load("hud/minimaps/$name.png") ?: return null
        if (minimapCache.size >= 3) minimapCache.clear()
        minimapCache[name] = bmp
        return bmp
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.postDelayed(ticker, TICK_MS)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    // A fixed design canvas keeps every section within the actual Presentation bounds.
    private fun dp(v: Float): Float = v
    private val typePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private fun label(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int, maxWidth: Float = 10000f) {
        typePaint.textSize = size
        typePaint.color = color
        var shown = value
        while (shown.length > 1 && typePaint.measureText(shown) > maxWidth) shown = shown.dropLast(1)
        if (shown != value && shown.length > 1) shown = shown.dropLast(1) + "\u2026"
        c.drawText(shown, x, y - typePaint.fontMetrics.ascent, typePaint)
    }

    override fun onDraw(canvas: Canvas) {
        val scale = min(width / 620f, height / 540f)
        canvas.save()
        canvas.translate((width - 620f * scale) / 2f, (height - 540f * scale) / 2f)
        canvas.scale(scale, scale)
        val w = 620f
        val h = 540f
        fill.shader = LinearGradient(0f, 0f, 0f, h, bgTop, bgBottom, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
        val s = feed.latest
        if (s == null) {
            drawWaiting(canvas, w, h, 50f)
            canvas.restore()
            return
        }
        drawMinimapPanel(canvas, 8f, 8f, 392f, 412f, s)
        drawStatusPanel(canvas, 412f, 14f, 200f, s)
        arsenal(canvas, 16f, 424f, 588f, s)
        label(canvas, "ITEMS", 18f, 511f, 12f, gold)
        label(canvas, s.inventory.joinToString("  /  ").ifEmpty { "None carried" }, 69f, 509f, 15f, text, 532f)
        canvas.restore()
    }

    // ---------------------------------------------------------------- top bar
    private fun drawWaiting(canvas: Canvas, w: Float, h: Float, top: Float) {
        val cx = w / 2f
        val cy = (top + h) / 2f
        val a = "WAITING FOR THE GAME"
        val b = "Start LookingGlass Alice to feed this HUD"
        font.drawText(canvas, a, cx - font.measureText(a, dp(26f)) / 2f, cy - dp(16f), dp(26f), muted)
        font.drawText(canvas, b, cx - font.measureText(b, dp(18f)) / 2f, cy + dp(18f), dp(18f), muted)
    }

    // ------------------------------------------------------------- minimap
    private fun drawMinimapPanel(
        canvas: Canvas, x: Float, top: Float, w: Float, h: Float, s: Snapshot,
    ) {
        // Panel
        fill.color = panel
        canvas.drawRoundRect(RectF(x, top, x + w, top + h), dp(14f), dp(14f), fill)
        stroke.color = panelStroke
        stroke.strokeWidth = dp(1.5f)
        canvas.drawRoundRect(RectF(x, top, x + w, top + h), dp(14f), dp(14f), stroke)

        // Header — proper in-game level name (not the dev asset name like "gvillage")
        label(canvas, MapNames.display(s.map).uppercase(), x + 14f, top + 10f, 17f, text, w - 28f)

        // Compact zoom control has its own touch-safe strip below the map.
        val readoutH = 44f
        val boxTop = top + dp(36f)
        val boxBottom = top + h - readoutH - dp(4f)
        val boxLeft = x + dp(6f)
        val boxRight = x + w - dp(6f)
        val boxW = boxRight - boxLeft
        val boxH = boxBottom - boxTop
        val cx = x + w / 2f
        val cy = boxTop + boxH / 2f

        val bmp = minimapFor(s.map)
        val bounds = mapBounds[s.map]
        if (bmp != null && bounds != null) {
            // At 1x show the whole level; higher zoom follows Alice. Clamp the
            // camera at map edges so the viewport does not expose empty space.
            val scale = min(boxW / bmp.width, boxH / bmp.height) * mapZoom
            val dw = bmp.width * scale
            val dh = bmp.height * scale
            val u = ((s.pos.getOrElse(0) { 0f } - bounds[2]) / (bounds[4] - bounds[2])).coerceIn(0f, 1f)
            val v = ((s.pos.getOrElse(1) { 0f } - bounds[3]) / (bounds[5] - bounds[3])).coerceIn(0f, 1f)
            val dx = if (dw <= boxW) cx - dw / 2f else (cx - u * dw).coerceIn(boxRight - dw, boxLeft)
            val dy = if (dh <= boxH) cy - dh / 2f else (cy - v * dh).coerceIn(boxBottom - dh, boxTop)

            // --- HORIZONTAL FLIP ---
            // The world->screen projection mirrors the heading relative to the
            // in-game view (facing right in game showed the arrow pointing left).
            // Mirror the map bitmap, the player dot, and the heading arrow
            // together around the box centre so they stay mutually consistent.
            canvas.save()
            canvas.clipRect(boxLeft, boxTop, boxRight, boxBottom)
            canvas.translate(cx, 0f)
            canvas.scale(-1f, 1f)
            canvas.translate(-cx, 0f)

            canvas.drawBitmap(bmp, null, RectF(dx, dy, dx + dw, dy + dh), iconPaint)

            // Frame
            stroke.color = panelStroke
            stroke.strokeWidth = dp(1.5f)
            canvas.drawRect(dx, dy, dx + dw, dy + dh, stroke)

            // Player dot + heading arrow (projected into the drawn rect).
            val px = (s.pos.getOrElse(0) { 0f } - bounds[2]) / (bounds[4] - bounds[2]) * dw
            val py = (s.pos.getOrElse(1) { 0f } - bounds[3]) / (bounds[5] - bounds[3]) * dh
            val pxc = dx + px
            val pyc = dy + py
            if (s.alive) {
                val ax = cos(s.yaw.toDouble()).toFloat()
                val ay = sin(s.yaw.toDouble()).toFloat()
                val len = dp(20f)
                stroke.color = Color.parseColor("#181014")
                stroke.strokeWidth = dp(7f)
                canvas.drawLine(pxc, pyc, pxc + ax * len, pyc + ay * len, stroke)
                fill.color = Color.parseColor("#181014")
                canvas.drawCircle(pxc, pyc, dp(9f), fill)
                stroke.color = gold
                stroke.strokeWidth = dp(3.5f)
                canvas.drawLine(pxc, pyc, pxc + ax * len, pyc + ay * len, stroke)
                // arrowhead
                val hx = pxc + ax * len
                val hy = pyc + ay * len
                val ah = dp(9f)
                val pxn = -ay
                val pyn = ax
                fill.color = gold
                canvas.drawPath(
                    Path().apply {
                        moveTo(hx, hy)
                        lineTo(hx - ax * ah + pxn * ah * 0.6f, hy - ay * ah + pyn * ah * 0.6f)
                        lineTo(hx - ax * ah - pxn * ah * 0.6f, hy - ay * ah - pyn * ah * 0.6f)
                        close()
                    },
                    fill,
                )
                // dot
                fill.color = text
                canvas.drawCircle(pxc, pyc, dp(6f), fill)
                stroke.color = accent
                stroke.strokeWidth = dp(2f)
                canvas.drawCircle(pxc, pyc, dp(6f), stroke)
            }
            canvas.restore()
        } else {
            // No minimap for this map — placeholder.
            font.drawText(canvas, "NO MAP DATA", cx - font.measureText("NO MAP DATA", dp(18f)) / 2f, cy, dp(18f), muted)
        }

        drawZoomSlider(canvas, x, top + h - 24f, w)
        if (!s.alive) label(canvas, "ALICE HAS FALLEN", x + 14f, boxBottom - 26f, 16f, bad, w - 28f)
    }

    private fun drawZoomSlider(canvas: Canvas, x: Float, cy: Float, w: Float) {
        val left = x + 42f
        val right = x + w - 42f
        zoomHitRect.set(left - 12f, cy - 22f, right + 12f, cy + 22f)
        zoomTrackLeft = left + 35f
        zoomTrackRight = right - 70f
        val knob = zoomTrackLeft + (mapZoom - 1f) / 5f * (zoomTrackRight - zoomTrackLeft)
        fill.color = Color.parseColor("#190C0D")
        canvas.drawRoundRect(RectF(left, cy - 17f, right, cy + 17f), 17f, 17f, fill)
        stroke.color = panelStroke
        stroke.strokeWidth = 1f
        canvas.drawRoundRect(RectF(left, cy - 17f, right, cy + 17f), 17f, 17f, stroke)
        label(canvas, "−", left + 12f, cy - 12f, 18f, muted)
        label(canvas, "+", zoomTrackRight + 12f, cy - 12f, 18f, muted)
        label(canvas, String.format(java.util.Locale.US, "%.1fx", mapZoom), right - 36f, cy - 8f, 12f, gold)
        fill.color = panelStroke
        canvas.drawRoundRect(RectF(zoomTrackLeft, cy - 2f, zoomTrackRight, cy + 2f), 2f, 2f, fill)
        fill.color = gold
        canvas.drawRoundRect(RectF(zoomTrackLeft, cy - 2f, knob, cy + 2f), 2f, 2f, fill)
        fill.color = Color.argb(35, 212, 169, 78)
        canvas.drawCircle(knob, cy, if (draggingZoom) 14f else 11f, fill)
        fill.color = gold
        canvas.drawCircle(knob, cy, 7f, fill)
        fill.color = text
        canvas.drawCircle(knob, cy, 3f, fill)
    }

    private fun drawStatusPanel(canvas: Canvas, x: Float, top: Float, w: Float, s: Snapshot) {
        var y = barRow(canvas, x, top, w, "SANITY", s.health, sanity, sanityFill)
        y = barRow(canvas, x, y, w, "WILL", s.will, will, willFill)
        label(canvas, "EQUIPPED", x, y + 2f, 12f, muted)
        label(canvas, if (s.alive) s.weaponName.ifEmpty { "Unarmed" } else "Fallen", x, y + 21f, 23f, gold, w)
        y += 62f
        label(canvas, "POWER-UPS", x, y, 12f, muted)
        y += 24f
        val names = arrayOf("RAGE", "TEA", "TIME", "RECHARGE")
        for (i in 0..3) {
            val cx = x + (i % 2) * 102f
            val cy = y + (i / 2) * 72f
            val active = s.powers.getOrElse(i) { 0f } > 0f
            fill.color = if (active) cellOn else cellOff
            canvas.drawRoundRect(RectF(cx, cy, cx + 94f, cy + 66f), 8f, 8f, fill)
            powerIcons[i]?.let { canvas.drawBitmap(it, null, RectF(cx + 4f, cy + 4f, cx + 44f, cy + 44f), if (active) iconPaint else dimIcon) }
            label(canvas, if (active) "${s.powers[i].toInt()}s" else "--", cx + 46f, cy + 12f, 18f, if (active) gold else muted, 46f)
            label(canvas, names[i], cx + 8f, cy + 46f, 11f, if (active) text else muted, 80f)
        }
    }

    private fun barRow(
        canvas: Canvas, x: Float, y: Float, w: Float,
        label: String, value: Float, color: Int, texture: Bitmap?,
    ): Float {
        label(canvas, label, x + 2f, y + 4f, 16f, text)
        val valText = value.toInt().toString()
        label(canvas, valText, x + w - 54f, y - 2f, 29f, color, 54f)

        val barY = y + dp(36f)
        val barH = dp(15f)
        val r = barH / 2f
        // track
        fill.color = cellOff
        canvas.drawRoundRect(RectF(x, barY, x + w, barY + barH), r, r, fill)
        // fill (textured with the game's meter art, tinted by the colour)
        val frac = (value / 100f).coerceIn(0f, 1f)
        if (frac > 0f) {
            val fw = w * frac
            if (texture != null) {
                barFill.shader = BitmapShader(texture, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                barFill.color = color
                canvas.save()
                val clip = RectF(x, barY, x + fw, barY + barH)
                canvas.clipRect(clip)
                canvas.drawRect(clip, barFill)
                val tint = Paint().apply {
                    this.color = color
                    alpha = 120
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
                }
                canvas.drawRect(clip, tint)
                canvas.restore()
                barFill.shader = null
            } else {
                fill.color = color
                canvas.drawRoundRect(RectF(x, barY, x + fw, barY + barH), r, r, fill)
            }
        }
        // outline
        stroke.color = panelStroke
        stroke.strokeWidth = dp(1.5f)
        canvas.drawRoundRect(RectF(x, barY, x + w, barY + barH), r, r, stroke)
        return barY + barH + dp(14f)
    }

    private fun arsenal(canvas: Canvas, x: Float, y: Float, w: Float, s: Snapshot): Float {
        var yy = y
        label(canvas, "ARSENAL", x + 2f, yy, 12f, muted)
        yy += dp(21f)
        val cols = 10
        val gap = dp(8f)
        val cellW = (w - gap * (cols - 1)) / cols
        val cellH = dp(52f)
        for (i in 0 until 10) {
            val c = i % cols
            val r = i / cols
            val cx = x + c * (cellW + gap)
            val cy = yy + r * (cellH + gap)
            weaponHitRects[i].set(cx, cy, cx + cellW, cy + cellH)
            val owned = if (i < s.owned.size) s.owned[i] else 0
            val isSel = s.selected == i
            val isEq = s.weapon == i

            // cell bg
            fill.color = if (owned > 0) cellOn else cellOff
            canvas.drawRoundRect(RectF(cx, cy, cx + cellW, cy + cellH), dp(8f), dp(8f), fill)

            // icon (centred)
            val icon = weaponIcons[i]
            val iconSize = min(cellW, cellH) - dp(8f)
            val ix = cx + (cellW - iconSize) / 2f
            val iy = cy + dp(4f)
            if (icon != null) {
                canvas.drawBitmap(icon, null, RectF(ix, iy, ix + iconSize, iy + iconSize), if (owned > 0) iconPaint else dimIcon)
            }

            // owned count (top-right)
            if (owned > 0) {
                val cnt = if (owned >= 9) "9+" else owned.toString()
                val cc = if (isEq) gold else muted
                label(canvas, cnt, cx + cellW - 13f, cy + 1f, 12f, cc)
            }

            // selected / equipped outline
            if (isSel || isEq) {
                stroke.color = if (isEq) gold else accent
                stroke.strokeWidth = dp(2.5f)
                canvas.drawRoundRect(RectF(cx, cy, cx + cellW, cy + cellH), dp(8f), dp(8f), stroke)
            }
        }
        return yy + cellH
    }

    companion object {
        private const val TICK_MS = 100L

        // Indexed to match the game's WEAPONS order.
        private val WEAPON_ICON_NAMES = arrayOf(
            "hud/icons/knife.png",
            "hud/icons/cards.png",
            "hud/icons/mallet.png",
            "hud/icons/jackbomb.png",
            "hud/icons/icewand.png",
            "hud/icons/jacks.png",
            "hud/icons/demondice.png",
            "hud/icons/eyestaff.png",
            "hud/icons/blunderbuss.png",
            "hud/icons/watch.png",
        )
        // [rage, tea, stopped, recharge]
        private val POWER_ICON_NAMES = arrayOf(
            "hud/icons/rage.png",
            "hud/icons/tea.png",
            "hud/icons/deadtime.png",
            "hud/icons/watch.png",
        )
    }
}

